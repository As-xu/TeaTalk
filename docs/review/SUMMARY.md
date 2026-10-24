# M0 代码 / UX 审阅汇总（Lead 复核版）

日期：2026-10-24 · 审阅对象：M0 骨架（提交 `8f6bdd4`）
详细报告：[code-core.md](code-core.md) · [code-app.md](code-app.md) · [ux.md](ux.md) · 截图 [ux-shots/](ux-shots/)

## 结论

- **无 P0 崩溃、无架构返工**。三份报告共 70+ 条 findings，去重后 **P1 12 条**（core 4 + app 5 + UX 3 的重叠项已合并）。
- 关键事实：`:core`/`:app` 构建、lint、21 个单测全绿；M0 功能链路（导入→详情→聊天→持久化）可用。
- **进入 M1 前建议先修 P1**：合计约 **1.2 人日**，其中 6 条是"用户一天内就会撞到"的体验/兼容问题。

## P1 清单（已复核，含证据）

| # | 来源 | 位置 | 问题 | 修法 | 工时 |
|---|------|------|------|------|------|
| 1 | core | `core/.../worldinfo/WorldInfo.kt:69-92` | V2 卡的 `position/depth/role/probability/group/case_sensitive/match_whole_words` 在 `character_book.entries[].extensions.*`，现只读顶层字段 → 全落默认值 | 按 ST `convertCharacterBook`（`public/scripts/world-info.js:5617-5674`）**extensions 优先、顶层兜底** | 1h |
| 2 | core | `WorldInfo.kt:85/182` | `use_regex` 被当开关；ST 导出无条件写 `true` 且 `convertCharacterBook` 根本不映射该字段 → 含 `( [ .` 的键按正则处理会崩/误匹配 | 不采纳该字段；正则入口留给 M2 独立字段 | 0.5h |
| 3 | core | `core/.../card/PngTextReader.kt:38-41` | `dataStart + length` 用 Int 溢出 → 构造 0x7FFFFFF8 长度的 tEXt 触发 `ArrayIndexOutOfBoundsException`（已实测） | length 转 Long 校验，越界即 break | 0.2h |
| 4 | core | `core/.../card/CardParser.kt` | CHARX `zip.readBytes()` 无解压上限（压缩炸弹/OOM）；V1 卡缺 `creatorcomment/creator` 映射 | 条目/解压上限 32 MB；补 V1 映射 | 1h |
| 5 | app | `ui/HomeScreen.kt:55-80` | 外层 Scaffold padding 未 consume → 内层三个 Scaffold 再补一遍 systemBars，顶部空白翻倍、网格底部被截 66px | `NavHost(Modifier.padding(innerPadding).consumeWindowInsets(innerPadding))` | 0.5h |
| 6 | app | `ui/character/CharacterDetailScreen.kt:71-78` | `CardViewParser.parse` + 世界书解析跑在 `stateIn(viewModelScope)` 的 Main 收集协程 → 大卡主线程解析掉帧/ANR | `.flowOn(Dispatchers.Default)` 或 `withContext(Default)` | 0.5h |
| 7 | app | `AndroidManifest.xml:8` | `allowBackup=true` 且无 `dataExtractionRules` → M1 的 Key/聊天记录会进云备份，违背"数据只存本机" | `allowBackup=false` + 备份规则文件 | 0.2h |
| 8 | app | `ui/characters/CharactersViewModel.kt` / `ui/chat/ChatViewModel.kt` | 开场白 count→insert 竞态（detail 点两次"开始聊天"可插两条）；chat 路由无 `singleTop` | `@Transaction` 内判空插入 + 唯一索引 + 路由 `launchSingleTop` | 0.5h |
| 9 | UX | `ui/characters/CharactersViewModel.kt` | 冷启动先闪 0.4s「还没有角色卡」空态再出卡片（见 `ux-shots/02-coldstart-empty-flash.png`） | 增加 `isLoading` 三态，首帧不渲染空态 | 1h |
| 10 | UX | `ui/HomeScreen.kt:78` | 冷启动永远落「角色」Tab，违反方案 §3.1「有会话进聊天」 | 启动时查会话表决定 startDestination（或启动后 navigate） | 1h |
| 11 | UX | `ui/chat/ChatScreen.kt` 顶栏 | 字体 1.3× 时角色名换行溢出、"离线示例模式"被气泡压住（`ux-shots/33-font13-chat-overflow.png`） | 标题 `maxLines=1` + ellipsis + `TopAppBar` 高度自适应 | 1h |
| 12 | UX | `ui/characters/CharactersScreen.kt` | 删除只靠长按、无提示、按钮非 error 色、无撤销；「我的>模型与接口」点击无反应也无"即将上线" | 长按 + 卡片菜单双入口、删除按钮 `error` 色、Snackbar 撤销、「即将上线」标签 | 2h |

## P2 摘要（详见各报告）

- **core（15 条）**：base64 宽松性、未知 spec 误报"缺少 spec"、`1.0`/Long 数字被静默默认、对象 entries 的 key=uid 丢失、`fromCard` 吞错、预算字符 vs token、中文 `matchWholeWords` 边界、`selective` 缺省值相反、`@Transient raw` 与注释矛盾等。
- **app（14 条）**：列表聚合带全量 `cardJson`、`exportSchema=false` 且无 migration、release 不混淆、`build.sh` 未入仓库、缺 `networkSecurityConfig`、设置路由/Key 存储缺失等。
- **UX（21 条）**：会话列表双 `weight(1f)` 挤压、世界书只显示 comment 且 3 行硬截断、`take(1)` 遇 emoji 变豆腐块、Snackbar 近白色刺眼、详情页原样显示 `[Personality=…]`/`<START>`/`{{user}}`、横屏"开始聊天"被拉伸、矮屏 FAB 压卡片等。

## 修复顺序建议

1. **半天：core 兼容性 4 条**（#1-#4）——直接影响 V6 20 张真实卡的验收结论。
2. **半天：app 渲染与数据 4 条**（#5-#8）——一次改完再回归，避免反复安装。
3. **半天：UX 3 条 + 删除交互**（#9-#12）——有截图对照，验收直观。
4. 回归：`:core:test`（补 extensions/恶意文件用例）+ `:app:assembleDebug` + 模拟器冷启动/字体 1.3×/删除流程截图。
5. 之后进 M1。

## 复核说明

Lead 亲自核对过的事实：ST `convertCharacterBook` 的 extensions 映射、`WorldInfo.kt` 只读顶层字段、`HomeScreen` 的 `startDestination` 与缺失 `consumeWindowInsets`、空态闪烁与 1.3× 字体溢出两张截图；其余条目采纳审查员证据（含实测报错文本/截图编号），未逐条复现。

## 修复状态（2026-10-25，方案 A 执行完毕）

用户拍板方案 A（先修 12 条 P1，再用 29 张真实卡做 V6，暂不进 M1）。逐条状态：

| # | 结论 | 落点（改动文件） | 验证 |
|---|---|---|---|
| 1 | ✅ | `core/.../card/CardParser.kt`（base64 宽松 + 未知 spec 容忍 + V1 creatorcomment + 数字兜底） | 新增单测 |
| 2 | ✅ | 同上（PNG 只读 tEXt，不误读 zTXt/iTXt） | 单测 |
| 3 | ✅ | `core/.../worldinfo/WorldInfo.kt`（extensions 优先、对象 entries uid 兜底、selectiveLogic 缺省） | 11 条单测 |
| 4 | ✅ | `core/.../worldinfo/WorldInfo.kt`（order 降序）+ `core/.../card/PngTextReader.kt`（Long 长度）+ CHARX 16 MB 上限 | 单测 |
| 5 | ✅ | `ui/characters/CharactersViewModel.kt`（loading 三态，首帧不渲染空态） | 模拟器 |
| 6 | ✅ | `ui/character/CharacterDetailViewModel.kt`（`flowOn(Dispatchers.Default)`） | 代码 |
| 7 | ✅ | `AndroidManifest.xml`（`allowBackup=false`） | 构建 |
| 8 | ✅ | `CharacterDao.insertGreetingIfEmpty` `@Transaction` + `ChatScreen` 路由 `launchSingleTop`（chat 与外部导入回调） | 模拟器 |
| 9 | ✅ | `CharactersScreen.kt`（长按菜单 + 导入 scrim + `error` 色删除） | 模拟器 |
| 10 | ✅ | `HomeScreen.kt`（`startDestination` 按 `hasAnyChat()`） | 模拟器冷启动 |
| 11 | ✅ | `CharacterDetailScreen.kt` / `ChatScreen.kt` 顶栏（`maxLines=1` + ellipsis） | 1.3× 字体截图 |
| 12 | ✅ | `CharactersScreen.kt` / `ProfileScreen.kt`（删除入口双开 + “即将开放”标签 + Snackbar） | 模拟器 |
| — | ✅ 额外 | `HomeScreen` 外部 `VIEW`/`SEND` intent 导入；内层 Scaffold `consumeWindowInsets`（修双重 insets） | 模拟器 + 构建 |

**V6**：29 张真实卡 29/29 导入成功（10 V2 + 19 V3，19 张带世界书，计数正确）。
**测试**：`:core:test` 28/28 绿；`:app:assembleDebug` 通过。
**仍未做**：P2 清单（见各报告）与 `{{user}}` 宏、会话列表聚合冗余列、migration/混淆/横屏/撤销——按计划留 M1/M2。
