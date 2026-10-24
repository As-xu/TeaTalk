# TeaTalk `:app` 模块代码审阅报告

- 审阅对象：`D:\Project\TeaTalkApp\TeaTalk`（git HEAD `8f6bdd4`），只覆盖 `:app` 模块与 `app/build.gradle.kts`、`gradle/libs.versions.toml`、`AndroidManifest.xml`、工作区根 `build.sh`
- 审阅时间：2026-10-24
- 方法：源码通读（18 个 Kotlin 文件 / 1722 行）+ `:app:assembleDebug` + `:app:lintDebug` + `:core:test` + 只读设备 dump/截图（未点按设备）
- 下表「位置」均相对仓库根 `D:\Project\TeaTalkApp\TeaTalk`（`build.sh` 除外，它位于工作区根）

## 1. 结论

`:app` 的 M0 数据链路（导入 → 角色列表 → 详情 → 聊天 → 会话列表）已闭环，构建与 lint 均通过，未发现 P0 级缺陷；但存在 **4 个 P1**：双层 `Scaffold` 导致系统栏内边距上下各算了两次（所有页面可见）、详情页在主线程解析 `cardJson`、`allowBackup=true` 会把 M1 的 Key 与聊天记录带进云备份、卡片导入路径内存峰值约为文件大小的 2 倍（64MB 上限下可到 ~128MB），且 CHARX 解压无上限。

另有 14 项 P2，集中在：聊天入口竞态（重复开场白）、删除非事务 + 文件删除失败被吞、列表查询带全量 `cardJson`、`collectAsState` 全量、聊天滚动副作用、发送失败无兜底、无 Loading 三态、返回栈行为、硬编码文案、无障碍细节、Room schema/迁移缺失、构建可复现性与 M1 扩展点缺口。

**总体评级：需先修**——修完第 3 节的 5 条必修项即可进入 M1；无架构性返工，剩余 P2 建议随 M1 的「连接与设置」第一批迭代一起消化（数据层改造 + 设置页/Key + 出包配置三组）。

## 2. Findings

| 编号 | 严重度 | 位置（file:line） | 问题 | 触发场景 / 证据 | 修复建议 |
|---|---|---|---|---|---|
| F-01 | P1 | `app/src/main/java/com/teatalk/xs/ui/HomeScreen.kt:55-80`；`.../ui/characters/CharactersScreen.kt:79-142`；`.../ui/chat/ChatScreen.kt:104-159`；`.../ui/character/CharacterDetailScreen.kt:98-135` | 外层 `Scaffold` 的 `Modifier.padding(innerPadding)` 没有 `consumeWindowInsets`，三个页面各自的 `Scaffold`/`TopAppBar` 又补一遍 systemBars，顶部多一层状态栏、底部多一层导航栏 | 只读 uiautomator dump（`docs/review/ui-characters.xml`）：内容根 `[0,136][1080,2054]`，标题行 top=294 = 136（外层）+136（内层）+8dp（22px），标题文字 `[55,305]`；网格底 1988 = 2054−66px；`NavigationBar` 高 220px=80dp、density 2.75，即 66px 导航栏 inset 同样被算两次；截图 `.tools/shot_d5_chars.png` 顶部空白约为正常两倍，`.tools/shot_d4_chat.png` 顶栏同样偏低 | 给 `NavHost` 加 `Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)`；或各内层 `Scaffold` 设 `contentWindowInsets = WindowInsets(0)`，insets 只由外层处理；改完用同一 dump 验收标题 top ≈ 8dp+状态栏 |
| F-02 | P1 | `.../ui/character/CharacterDetailScreen.kt:71-78` | `CardViewParser.parse(cardJson)` 写在 `stateIn` 的 `.map {}` 中；Room flow 的 `flowOn` 只约束上游，map 在 `stateIn` 的收集协程（`viewModelScope` = Main.immediate）执行，每次发射都重新解析整卡 + 世界书 | 代码路径 `repository.observeCharacter(id).map { parse(...) }.stateIn(viewModelScope, ...)`；大卡/世界书条目多时首帧与每次 DB 更新都在主线程解析，可能掉帧甚至 ANR | `.mapLatest { withContext(Dispatchers.Default) { ... } }` 或整条 flow `.flowOn(Dispatchers.Default)`；按 `cardJson` `distinctUntilChanged` 并缓存解析结果（M1 卡片可编辑后更需要） |
| F-03 | P1 | `app/src/main/AndroidManifest.xml:8` | `android:allowBackup="true"` 且没有 `dataExtractionRules`/`fullBackupContent`；M1 的 API Key 与聊天记录会进 Auto Backup，与方案 §4.2「Key 不落地明文」及 Profile 页「数据只存在本机」的承诺冲突 | manifest 仅声明 INTERNET 权限、无任何备份规则；`docs/00-技术方案与演进路线.md` §4.2/§4.4 要求 M1 存 Key；debug 构建还可被 `adb backup` 导出（API<31） | `android:allowBackup="false"`（或 backup rules 显式排除 DB/DataStore/keystore 文件）；M1 后另做应用内 SAF 导出/导入 |
| F-04 | P1 | `.../data/CharacterRepository.kt:119-132`；`core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:52-68`；`.../data/CharacterRepository.kt:55,112-117` | 64MB 上限在文件读完之后才检查；`ByteArrayOutputStream` 反复扩容 + `toByteArray()` 产生第二份拷贝；CHARX 的 `zip.readBytes()` 对解压条目完全无上限（zip bomb）；PNG 卡之后还会把整份 bytes 写入头像。OOM 是 `Error`，`importCard` 的 `catch (e: Exception)` 捕不到 → 直接崩进程 | `readCardBytes` 用 64KB buffer 累积到 BAOS，`total > MAX` 才抛异常；`CardParser.kt:57` `zip.readBytes()` 读整个 entry；单卡内存峰值 ≈ 2 × 文件大小（64MB 上限对应约 128MB 常驻） | 上限降到 16MB 并先用 `OpenableColumns.SIZE` 预检；改为流式/临时文件解析（PNG 只扫 chunk 表，CHARX 限制单 entry 解压上限）；`catch (Throwable)` 兜底提示「文件过大/损坏」；头像写盘失败要回滚 |
| F-05 | P2 | `.../data/CharacterRepository.kt:83-94`；`.../ui/HomeScreen.kt:84,109` | `ensureGreeting` 的 `countMessages` 与 `insertMessage` 是两次独立事务；`chat` 路由的 `navigate` 没有 `launchSingleTop` → 快速返回重进、双击「开始聊天」、分别从会话列表和详情各开一个 chat 实例，都可能插入两条开场白 | `ChatViewModel.init` 无条件调用 `ensureGreeting`；`viewModel(key = "chat-$id")` 按 NavBackStackEntry 建 VM，两个 entry = 两个 VM 并发 `count=0` | DAO 加 `@Transaction` 方法或单条 `INSERT ... SELECT ... WHERE NOT EXISTS`；两处 `navigate("chat/$id")` 都加 `launchSingleTop = true` |
| F-06 | P2 | `.../data/CharacterRepository.kt:75-80,112-117` | 删除非事务：先删消息、再删角色、最后删文件；文件删除用 `runCatching` 吞异常。导入时先 `saveAvatar` 再 `insert`，insert 失败会留下孤儿头像 | 进程崩溃或 IO 失败时留下孤儿 messages（角色已删）或 `filesDir/avatars/*.png` 永久不回收 | DAO 提供 `@Transaction deleteCascade(character)`；文件删除放到事务成功之后，失败记录待清理；启动时对 avatars 目录做一次孤儿 GC |
| F-07 | P2 | `.../data/db/CharacterDao.kt:12,41-51`；`.../data/db/Entities.kt:20,28` | 列表 `SELECT *` 把 `cardJson` 全量读进内存；`observeChats` 对 `lastContent`/`lastAt` 跑了两次同一子查询外加一次 `EXISTS`；messages 只有 `characterId` 单列索引，`ORDER BY createdAt` 需要临时排序 | Room 把每行所有列读进 CursorWindow；M1 卡多、消息多时首屏变慢且内存放大 | 列表查询改投影（id/name/avatarPath/createdAt）；`cardJson` 拆附表或压缩；`Index("characterId","createdAt")`；会话列表加 `lastMessageAt` 冗余列或单次窗口查询 |
| F-08 | P2 | `.../ui/characters/CharactersScreen.kt:150,166`；`.../ui/components/CharacterAvatar.kt:29-44` | 在 composition 中构造 `File` 并调用 `.exists()`（主线程 stat），每次重组都执行 | `CharacterAvatar:38` `if (file != null && file.exists())`；网格滚动时每帧多若干 syscall | `remember(avatarPath) { ... }` 包住存在性判断，或干脆交给 Coil（`error`/`fallback` placeholder）不预判；M1 可加缩略图缓存 |
| F-09 | P2 | `.../ui/characters/CharactersScreen.kt:62-64`；`.../ui/chat/ChatScreen.kt:95-96`；`.../ui/character/CharacterDetailScreen.kt:95`；`.../ui/chats/ChatListScreen.kt:60` | 全部使用 `collectAsState()`，未按生命周期收集；依赖列表中也没有 `lifecycle-runtime-compose` | 4 个文件共 8 处；`stateIn(WhileSubscribed(5_000))` 在页面 stop 后仍会收集 5 秒，M1 流式回复时后台可能继续收数据 | 添加 `androidx.lifecycle:lifecycle-runtime-compose`（2.8.2），改用 `collectAsStateWithLifecycle()` |
| F-10 | P2 | `.../ui/chat/ChatScreen.kt:100-102` | `LaunchedEffect(messages.size)` 无条件 `scrollToItem(lastIndex)`：用户上翻看历史时来新消息会被拽到底；M1 流式回复只更新同一条消息（size 不变）则完全不跟随；无「回到底部」入口 | 只看 `messages.size`，不判断是否接近底部；也没有用户滚动状态判断 | 用 `derivedStateOf` 判断最后可见项距底小于阈值才自动滚；流式时按内容增长跟随；加「新消息 ↓」悬浮按钮 |
| F-11 | P2 | `.../ui/chat/ChatScreen.kt:140-143`；`.../ui/chat/ChatScreen.kt:73-79` | 发送时先清空输入再入库；`send()` 没有 try/catch，`viewModelScope.launch` 中未捕获的 DB 异常会直接崩进程且用户输入丢失；`find()` 返回 null（角色已删）时静默丢弃 | `viewModel.send(input); input = ""`；`repository.sendMessage` 内两次 `insertMessage` 均无保护 | 发送走「成功才清空、失败回填 + snackbar」；`runCatching` 包裹并上报错误；M1 网络错误必须有可见的重试 |
| F-12 | P2 | `.../ui/character/CharacterDetailScreen.kt:78,111-119`；`.../ui/chat/ChatScreen.kt:63-67,123`；`.../ui/characters/CharactersScreen.kt:113` | 无 Loading 三态：详情初始 `CharacterDetailState()` → 首帧渲染「角色不存在或已删除」；聊天页初始 `character=null` → 标题显示「角色」；角色列表初始 `emptyList` → 空态文案闪烁 | `stateIn` 初始值分别是 `CharacterDetailState()` / `null` / `emptyList()`，Room 首发射出前必然渲染一次 | 改为 sealed UI state（Loading / NotFound / Ready），Loading 时不渲染空态；列表页初始显示骨架占位 |
| F-13 | P2 | `.../ui/HomeScreen.kt:47-53,78,84,109` | 返回栈：`startDestination` 是「角色」，从任意 Tab 按系统返回都会回到「角色」而不是退出/回聊天，与 QQ/微信习惯不一致；从详情开聊天再返回落到详情（这点合理）。产品注：startDestination 是否应改「聊天」只记录，不算 bug | `popUpTo(findStartDestination().id) { saveState = true }` 把角色保留为栈底；`TABS` 顺序为 聊天/角色/我的 | M1 明确返回策略：切 Tab 用 `popUpTo(startDestination){ inclusive = true }` 或把「聊天」设为 start；补进程死亡恢复与深链的手动回归 |
| F-14 | P2 | `app/src/main/res/values/strings.xml`（仅 `app_name`）；`.../ui/character/CharacterDetailScreen.kt:155`；`.../ui/characters/CharactersScreen.kt:101,108,226-238`；`.../ui/chat/ChatScreen.kt:123,127,214`；`.../ui/chats/ChatListScreen.kt:64,135-144`；`.../ui/profile/ProfileScreen.kt:30-53` | 中文字面量全部硬编码在 Compose 里；`cardSource` 直接显示枚举名（`PNG_V2`/`CHARX`）；没有任何 i18n 基础 | `strings.xml` 仅有 `app_name`；详情页 byline 拼接 `character.cardSource` 未做映射 | 抽 `strings.xml`（含无障碍文案），`cardSource` 映射为「PNG 卡 / CHARX / JSON」；趁 M0 文案量还小尽早做，避免 M1 全量 diff |
| F-15 | P2 | `.../ui/HomeScreen.kt:63-70`；`.../ui/components/CharacterAvatar.kt:41`；`.../ui/chats/ChatListScreen.kt:97-111,150-161`；`.../ui/chat/ChatScreen.kt:210-217`；`app/src/main/res/mipmap-anydpi-v26/` | Compose/无障碍小项：底部 Tab 的 icon `contentDescription=label` 与 label 文本重复朗读；头像 `contentDescription=name` 与旁边同名 Text 重复；`ChatRow` 未合并语义；输入框无 `imeAction=Send`（回车是换行，聊天习惯应发送）；`ChatRow` 姓名 `weight(1f, fill=false)` + `Spacer(weight(1f))` 平分剩余宽度，长名字提前省略；`formatTime` 每次重组 new 两个 `Calendar`、`String.format` 未指定 Locale；`-v26` 资源目录在 minSdk 28 下多余 | `gradle :app:lintDebug` → **0 errors, 2 warnings**：`ChatListScreen.kt:157` DefaultLocale、`mipmap-anydpi-v26` ObsoleteSdkInt | 装饰性 icon/头像 `contentDescription = null` 并给行加 `semantics(mergeDescendants = true)`；输入框 `keyboardOptions(imeAction = Send) + KeyboardActions`；姓名 `weight(1f)` 直接占满（去掉多余 Spacer）；时间用 `java.time` + 明确 Locale；合并 v26 目录 |
| F-16 | P2 | `.../data/db/Entities.kt:20-24,28-36`；`.../data/db/TeaTalkDatabase.kt:6-10`；`.../data/CharacterRepository.kt:60-64` | 数据模型债：`cardJson` 全量 JSON 存单 TEXT（单行 >2MB 有 CursorWindow 风险）；`firstMes`/`tagsJson` 与 `cardJson` 双源（`tagsJson` 目前无人读）；messages 无 `FK ON DELETE CASCADE`；`exportSchema=false` + `version=1` 且无迁移，M1 加 connection/settings 表后老安装升级会崩 | Room 默认 CursorWindow 2MB，超限抛 `SQLiteBlobTooBigException`；没有 schema 目录 = 无法写迁移测试；`@Database(version = 1, exportSchema = false)` | 开 `exportSchema = true` + `room.schemaLocation`，补 MigrationTestHelper；M1 写 1→2 迁移；列表投影；明确 `cardJson` 压缩/拆表策略；messages 加 FK+级联（或保留仓库层显式删） |
| F-17 | P2 | `app/build.gradle.kts:20-36,73,77`；`gradle/libs.versions.toml:8-9`；`build.sh:8-12,24,33`（工作区根，不在 git 仓库） | 构建/依赖：release 不混淆（M1 出包前必须开 minify + keep 规则）；okhttp 已引入但代码零引用（M1 预留，可接受）；`testImplementation(junit)` 但 `:app` 无测试源；BOM 2024.06.00 / nav 2.7.7 与任务描述里的 2024.10 / 2.8 不一致；`build.sh` 硬编码本机 JDK/SDK 默认路径、依赖仓库外 `.tools/gradle-8.9`、`keytool` 不保证在 PATH；debug keystore 打出的 dist APK 容易被误认为正式包 | `build.sh` 不在 `git ls-files`；`.keystore/` 已被 `.gitignore` 忽略；release 无 signingConfig（`assembleRelease` 产出未签名包）；dist 目录只存 APK 的 sha256 | 把 wrapper/`build.sh` 纳入仓库并去掉机器相关默认值；M1 前开 minify 并为 kotlinx.serialization/OkHttp/Coil 加 keep；统一版本口径（升级或改文档）；每次 dist 明确标注 debug 签名 |
| F-18 | P2 | `.../ui/HomeScreen.kt:95`；`.../ui/profile/ProfileScreen.kt:27,38,53`；`.../data/CharacterRepository.kt:96-110`；`app/src/main/AndroidManifest.xml:4` | M1 扩展点缺口：无设置路由/DataStore/Keystore 存储；「模型与接口」一行点击是空 lambda（无任何反馈）；`sendMessage` 把离线示例回复写死在仓储层，M1 需要拆成 persistence + generation（OkHttp SSE stream:true）；target 34 默认禁明文 http，自定义 Base URL 的局域网端点会直接失败且没有 `networkSecurityConfig`；Profile 文案承诺「M1 起提供导出备份」与方案 M1 范围（连接与设置）不一致 | `ProfileScreen(onOpenModelSettings = {})` 从未传入实际实现；`sendMessage` 里写死 `（离线示例回复）` 字符串；manifest 无 `networkSecurityConfig`；`docs/00-技术方案与演进路线.md` M1 只列连接/设置/深色/引导 | M1 新增 Settings 路由 + DataStore + Keystore 封装；`sendMessage` 拆为 `ChatStore`（持久化）与 `CompletionClient`（流式）；`network_security_config` 允许用户配置的 http 域或强制 https；修正 Profile 文案 |

## 3. M1 前必修清单（5 条）

| # | 对应 | 工作内容 | 验收标准 |
|---|---|---|---|
| 1 | F-01 | 统一 insets：`NavHost` 加 `consumeWindowInsets(innerPadding)`，或内层 `Scaffold` 设 `contentWindowInsets = WindowInsets(0)` | `uiautomator dump` 中标题 top ≈ 8dp+状态栏（不再 ×2）；角色/聊天/详情三页顶底留白一致；键盘弹起时输入栏不叠加导航栏高度 |
| 2 | F-02 | 详情页 JSON 解析移出主线程（`flowOn(Default)` / `mapLatest+withContext`），按 `cardJson` 缓存 | 用一张大卡（JSON >100KB、世界书 >200 条）冷启动详情页，`Choreographer` 掉帧 < 2 帧，无 ANR |
| 3 | F-03 | `allowBackup=false` + 备份规则显式排除 DB/DataStore/Keystore；把「云备份」与「应用内导出」的边界写进方案 §4.2 | `adb backup` 无法导出聊天 DB；M1 设置页 Key 不出现于 `bmgr` 备份集；文档口径一致 |
| 4 | F-04 | 导入上限 16MB + `OpenableColumns.SIZE` 预检 + 流式/临时文件解析 + CHARX 单 entry 解压上限；`catch (Throwable)` | 64MB/伪造 zip bomb 文件导入时给「文件过大」提示且内存峰值 < 64MB；头像写盘失败能回滚且不产生孤儿文件 |
| 5 | F-05 | 开场白原子化（`@Transaction` 或 `INSERT ... WHERE NOT EXISTS`）+ 两处 `chat` 导航加 `launchSingleTop` | 快速进出聊天 20 次、双击「开始聊天」、从列表与详情交叉进入，每个角色始终只有 1 条开场白 |

> 进入 M1 后第一周顺带消化：F-16（exportSchema + 1→2 迁移 + FK + 索引 + 列表投影，与 connection 表一起做）、F-18（Settings 路由 + Key 存储 + `CompletionClient` 流式 + 明文配置）、F-17（minify/keep、`:app` 首批测试：Repository 与 ChatViewModel 单测）。

## 4. 附录：跑过的命令与结果

环境：`JAVA_HOME=D:/DevEnv/java/jdk21/ms-21.0.9`、`ANDROID_HOME=D:/DevEnv/Android/Sdk`、`GRADLE_USER_HOME=D:/Project/TeaTalkApp/.gradle-home`、`TMPDIR=TMP=TEMP=D:/Project/TeaTalkApp/.tmp`、`D:/Project/TeaTalkApp/.tools/gradle-8.9/bin/gradle.bat`。

| 命令 | 结果 |
|---|---|
| `git -C TeaTalk rev-parse --show-toplevel` / `git log --oneline -3` / `git status --porcelain` | 仓库根 `D:/Project/TeaTalkApp/TeaTalk`；HEAD `8f6bdd4 feat(d5) ...`（`1617045`、`57158f2` 在前）；工作区仅 `?? docs/review/`（审阅产物，未改源码） |
| `gradle -p TeaTalk :app:assembleDebug --no-daemon --console=plain` | **BUILD SUCCESSFUL in 14s**，37 tasks（22 from cache）；产物 `app/build/outputs/apk/debug/app-debug.apk`，26,096,930 B，sha256 前 16 位 `4ca86d4063fd0ca6` |
| `gradle -p TeaTalk :app:lintDebug --no-daemon --console=plain` | **BUILD SUCCESSFUL in 1m17s**；`app/build/reports/lint-results-debug.{html,txt,xml}`：**0 errors, 2 warnings**（`ChatListScreen.kt:157` DefaultLocale；`res/mipmap-anydpi-v26` ObsoleteSdkInt） |
| `./build.sh :core:test --no-daemon` | **BUILD SUCCESSFUL in 9s**（任务 UP-TO-DATE，上游已执行；`core` 测试 13/13 见 `docs/01-M0-开工清单.md` V3）。`:app` 无测试源，跳过 |
| `logcat_devices` | **无设备连接**（同事在做 UX 实操，按约束不抢设备、不点按） |
| 只读设备证据 | `docs/review/ui-characters.xml`（他人采集的 `uiautomator dump`，本次仅读取）；`.tools/shot_d5_chars.png`、`.tools/shot_d4_chat.png`、`.tools/shot_d4_kb.png`（仅查看，未操作） |
| 未执行 | 无 instrumentation/UI 测试可跑；未跑 `assembleRelease`；未做真机内存/帧率采集（无设备） |

补充说明：

- 任务描述中的「Compose BOM 2024.10 / Navigation 2.8」与仓库实际值（`composeBom=2024.06.00`、`navigationCompose=2.7.7`）不一致，已记录在 F-17；如属有意降级请同步文档。
- 本次未修改任何源码、测试或 git 状态；仅新增本报告（`docs/review/` 本就未跟踪，见 `git status`）。
