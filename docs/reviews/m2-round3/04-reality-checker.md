# Reality Checker — M2 审阅（对象 f30d9b6 / 0.7.0）

## 核查结论（先给判定）

M2 单个提交的**代码与测试数字是真的**，但文档在**三处证据链上站不住**：版本号是硬错误、v6→v7 迁移的“实测”没有任何工件、以及引用的会话截图里出现**同一会话两条开场白**，与“开场白不重复”直接冲突。整体判定：**NEEDS WORK（文档修订 + 一轮补证据），不是代码不可用**。

我实际核对的范围：commit `f30d9b6`、8 个 test-results XML、`build.gradle.kts`、dist APK 与 sha256、6 张会话截图 + 2 张 Markdown 截图、以及 `TeaTalkDatabase.kt` / `ConversationDao.kt` / `CharacterRepository.kt` / `ChatScreen.kt` / `ChatListScreen.kt` 代码。**未运行构建**（遵守约束）；本机无连接设备（`logcat_devices` 返回空），**无法读设备 DB**，这一点限制了下面对“迁移已执行”的最终确认强度。

---

## 一、文档陈述 → 证据 → 是否成立

| # | 文档陈述（行号） | 证据 | 是否成立 |
|---|---|---|---|
| 1 | L4「当前版本：0.4.0（M2 步骤 1 完成，审阅修复中）」 | [app/build.gradle.kts:17-18](TeaTalk/app/build.gradle.kts#L17-L18) `versionCode = 11` / `versionName = "0.7.0"`；`output-metadata.json` 同为 11/0.7.0；提交信息也是 0.7.0 | ❌ **不成立**（硬错误，头部停留在 0.4.0） |
| 2 | L31「全量 61 条绿：core 44（23/7/3/11）、app 17（9/3/5）」 | 当前 XML：core 23+7+3+11=44；`git show a65f2fe` 下 `MessageVariantsTest` 恰为 5 条 | ✅ 成立（这是 0.4.x 的历史快照，数字自洽） |
| 3 | L166「单测 75 条全绿（core 44 + app 31）」 | app XML：MessageVariants 8 + ChatClient 9 + FileLogSink 3 + Markdown 11 = 31；core 44；全部 `failures="0" errors="0" skipped="0"`，共 75；XML 时间戳 00:12 与 `app-debug.apk` 构建时间同分钟 | ✅ 成立（**计数完全对得上**） |
| 4 | L129「MarkdownTest 11 条：标题/段落合并/两种列表/代码块含空行/未闭合代码块/引用与分隔线/五种行内/未闭合行内/转义/相邻 span 合并/星号动作」 | [TEST-…MarkdownTest.xml](TeaTalk/app/build/test-results/testDebugUnitTest/TEST-com.teatalk.xs.ui.components.MarkdownTest.xml) 11 条，用例名逐条对应（`headings become heading blocks`、`paragraph lines merge until blank line`、`unclosed fence while streaming stays code block`…） | ✅ 成立 |
| 5 | L77「app 新增 3 条状态用例，合计 64 条」 | `statusesPadMissingEntriesWithComplete` / `statusesKeepStoppedAndFailed` / `appendAddsStatusForNewVariant` = +3；61+3=64 | ✅ 成立 |
| 6 | L130-131「截图 01-blocks-dark.png / 02-inline.png」 | 见图 1、图 2：标题/无序列表/引用竖条/代码块与 `**bold** *italic*` → 粗+斜 **且用户气泡保持原文** | ✅ 成立（视觉核实） |
| 7 | L131「5KB 内未见掉帧」 | 无任何测量工件（无 jank stats / Perfetto） | ⚠️ 主观断言，不可复核 |
| 8 | L142「conversations 两索引：characterId、(characterId, updatedAt)」 | [ConversationEntity.kt:15-18](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/ConversationEntity.kt#L15-L18) 与 [TeaTalkDatabase.kt:145-146](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt#L145-L146) 一致 | ✅ 成立 |
| 9 | L143「characterId 保留为冗余外键（列表/搜索不必 join）」 | [ConversationDao.kt:48-57](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/ConversationDao.kt#L48-L57) 列表查询 **仍然 `INNER JOIN characters`** 取名字/头像 | ⚠️ 列存在属实，“不必 join”的括注不成立 |
| 10 | L144「迁移 6→7 …实测旧库升级后旧消息完整、开场白不重复」 | 迁移 SQL 存在且 6 条迁移全部注册（[AppContainer.kt:49-56](TeaTalk/app/src/main/java/com/teatalk/xs/data/AppContainer.kt#L49-L56)），**但**：无迁移测试（app 无 androidTest、无 room-testing/Robolectric 依赖）、**文档未引用任何 `db.migrate from=6 to=7` 日志行**（对比 L71/L100 都引用了）、[05-switched.png](TeaTalk/docs/screenshots/m2-conversations/05-switched.png) 同一会话内出现**两条完全相同的 “Arrival” 开场白** | ❌ **“实测…开场白不重复”不成立**（截图反证；迁移是否执行只能间接推断） |
| 11 | L148-150 菜单「新建会话/全部会话」、面板列标题/时间/勾选、新建自动插 `firstMes` 并跳转 | [01-chat-menu.png](TeaTalk/docs/screenshots/m2-conversations/01-chat-menu.png)（New chat / All chats）、[02-all-chats.png](TeaTalk/docs/screenshots/m2-conversations/02-all-chats.png)（两行 Untitled chat + ✓ + 笔 + 桶）、代码 `createConversationInternal` + `onOpenConversation` | ✅ 成立 |
| 12 | L151「删除二次确认…删除当前会话时退出到聊天列表」 | 文案与按钮见 [04-delete-confirm.png](TeaTalk/docs/screenshots/m2-conversations/04-delete-confirm.png)（“This removes “Tea house” and all its messages. This cannot be undone.”）；退出逻辑见 [ChatScreen.kt:722-726](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L722-L726) | ✅（功能）／⚠️（**该截图删的是非当前会话**，最关键的“删当前会话→退回列表”无截图） |
| 13 | L152「仅显示有消息的会话；标题回退角色名；预览/时间取最后一条」 | [ChatListScreen.kt:79](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chats/ChatListScreen.kt#L79) `filter { it.lastContent != null }`；L154 `title.ifBlank { characterName }`；DAO 子查询取 `MAX(createdAt), id DESC` | ✅ 成立（[06-chat-list.png](TeaTalk/docs/screenshots/m2-conversations/06-chat-list.png) 两行均回退 “Old Card”） |
| 14 | L165「v6→v7 迁移成功，旧会话可继续对话；新建/切换/重命名/删除、**杀进程重进后会话与消息保持**；**删除当前会话退出到列表**」 | 一句里打包 6 项。截图只覆盖 新建/重命名/删除对话框/切换/列表；“杀进程重进”“删当前会话退出”**零工件**；迁移无日志无测试 | ❌ **打包夸大**，应拆开并标注证据状态 |
| 15 | L167「截图 01-chat-menu…06-chat-list」 | 6 张文件均存在、已入提交（`git show --stat` 可见），分辨率一致 1080×2340 | ✅ 文件层面成立 |
| 16 | L171-173 已知限制（标题需手动、无分页/搜索、无回收站） | 代码一致（`ConversationSheet` 无搜索；标题只在 `title` 列） | ✅ 成立 |
| 17 | L71 / L100「logcat `db.migrate from=4 to=5 ms=1` / `from=5 to=6 ms=16`」 | 代码里有对应 `TeaTalkLog.i` 埋点；**仓库内无任何 logcat 存档文件**（全仓 `*.log` 为空） | ⚠️ 不可复核（引用行可信但只是自述） |
| 18 | APK 版本与 sha256 | `sha256sum dist/TeaTalk-0.7.0-debug.apk` = `74de0f42e26f96f16eb26dc5bbe26f47d3d394b74a6b55641ca46ea0177ee842`，与其 `.sha256` 一致，且与 `app/build/outputs/apk/debug/app-debug.apk` **二进制完全相同**（`cmp` 通过） | ✅ 成立；但 `dist/` 被 `.gitignore` 忽略，**只有 sha256 在本机可复核，不在提交里**，文档也未记录该值 |

---

## 二、截图逐张对照（“是否对应所声称状态”）

| 截图 | 声称状态 | 实际画面 | 判定 |
|---|---|---|---|
| [01-chat-menu.png](TeaTalk/docs/screenshots/m2-conversations/01-chat-menu.png) | 聊天页 ⋯ 菜单（新建/全部会话） | New chat / All chats 弹出菜单 ✓；**但背景同一会话有两条 “Arrival” 开场白** | 菜单成立；背景数据异常 |
| [02-all-chats.png](TeaTalk/docs/screenshots/m2-conversations/02-all-chats.png) | 会话面板：标题/时间/当前项打勾 | 两行 “Untitled chat” 16:09(✓)/16:42，编辑+删除图标 ✓ | ✅ |
| [03-renamed.png](TeaTalk/docs/screenshots/m2-conversations/03-renamed.png) | 重命名后 | 首行变为 “Tea house”，时间刷新为 16:10 ✓ | ✅ |
| [04-delete-confirm.png](TeaTalk/docs/screenshots/m2-conversations/04-delete-confirm.png) | 删除二次确认（连带消息、不可恢复） | 文案/按钮正确 ✓；**被删对象是非当前会话**，且背景会话头部写 “Today 00:00”（1970 时间戳） | 对话框成立；**“删当前会话退回列表”未被证** |
| [05-switched.png](TeaTalk/docs/screenshots/m2-conversations/05-switched.png) | 切换回原会话 | 确为另一会话内容 ✓；**两条相同开场白** | 切换成立；数据自相矛盾 |
| [06-chat-list.png](TeaTalk/docs/screenshots/m2-conversations/06-chat-list.png) | 会话列表、标题回退、预览/时间取最后一条 | 2 行 “Old Card” ✓；但时间为 `15:17` 与 **`01-01`**（epoch 0），预览是**原始 Markdown**（`## Arrival…`、`**bold** *italic*`） | 主体成立；时间戳/预览暴露未记录的缺陷 |
| [01-blocks-dark.png](TeaTalk/docs/screenshots/m2-md/01-blocks-dark.png) | `#`/`-`/`>`/``` 渲染 | 全部正确渲染，且顺带显示 B2 来源提示 “Generated with DeepSeek · deepseek-flash (not the current provider)” ✓ | ✅ |
| [02-inline.png](TeaTalk/docs/screenshots/m2-md/02-inline.png) | `**bold** *italic*` 粗+斜 | 回复气泡显示 “**bold**（粗） *italic*（斜）”，用户气泡保留原文 ✓ | ✅ |

补充说明：`docs/screenshots/m2/04-delete-confirm.png`（步骤 1 的唯一删除确认图）是 **0.4.0 修复前**的界面——文案为 “Delete message / This cannot be undone. Delete it? / **Close** / Delete”，变体条是 `1/2` 而非 `Version 1 of 2`。它**无法支撑 L26“删除确认显示 This reply has 2 versions…、取消/删除均正常”**（该文案只在 [values-en/strings.xml:237](TeaTalk/app/src/main/res/values-en/strings.xml#L237) 里，属 0.4.1 之后的行为）。

---

## 三、需要修正的句子（可直接替换）

1. **L4**（硬错误，必须改）
   - 现：「当前版本：0.4.0（M2 步骤 1 完成，审阅修复中）」
   - 改：「当前版本：0.7.0（versionCode 11，dist `TeaTalk-0.7.0-debug.apk`，sha256 `74de0f42…ee842`）；本文件逐节记录 M2 0.4.0→0.7.0 各步，旧节的版本号/DB 版本为**当时状态**。」

2. **L144**（撤回“实测”+ 消除与截图的冲突）
   - 现：「…实测旧库升级后旧消息完整、开场白不重复。」
   - 改：「…迁移路径**仅有间接证据**：旧消息（15:17 / `**bold** *italic*`）在 0.7.0 上仍可见，说明 DB 被升级而非重建；**无 `db.migrate from=6 to=7` 日志存档、无迁移单测**。另：`05-switched.png` 中同一会话出现两条相同开场白，成因未定位（疑为升级前已存在的重复行被原样保留），**“开场白不重复”一句作废，待复测**。」

3. **L165**（拆包，标注证据状态）
   - 现：「AVD：v6→v7 迁移成功，旧会话可继续对话；新建/切换/重命名/删除、杀进程重进后会话与消息保持；删除当前会话退出到列表。」
   - 改：「AVD（0.7.0）：✅ 新建（02）/切换（05）/重命名（03）/删除确认（04）/会话列表（06）有截图。⚠️ **未取证**：v6→v7 迁移日志、杀进程重进后会话与消息保持、删除**当前**会话后退出到列表（04 删的是非当前会话，`ChatScreen.kt:722` 的 `onBack()` 逻辑仅代码可见）。」

4. **L26**（与现有截图不符）
   - 改：「删除确认文案已改为 `This reply has N versions…` + `Cancel`（`strings.xml:237`），**但 0.4.0 截图 `docs/screenshots/m2/04-delete-confirm.png` 是修复前的 “Close” 版，未重拍**。」

5. **L143**（括注不成立）
   - 改：「`characterId` 保留为冗余外键；**但聊天列表查询仍 `INNER JOIN characters`** 取名称/头像（`ConversationDao.kt:54`），冗余的价值在按角色取会话与消息，而非“列表免 join”。」

6. **L131 尾句**（去掉量化暗示）
   - 现：「性能：文本每变一次重解析，5KB 内未见掉帧」
   - 改：「性能：仅**肉眼观察**未见明显卡顿，未做帧率/jank 测量；流式每 60ms 全量重解析的成本未量化。」

7. **L71 / L100**（统一证据标准）
   - 建议补一句：「上述 logcat 行为当次设备输出，未随仓库归档；仓库内无 logcat 快照。」

8. **L10-13 / L59 等小节标题**（时态）
   - 「TeaTalkDatabase.VERSION = 4」等应写成「当时 VERSION = 4（现为 7）」，否则与 [TeaTalkDatabase.kt:17-27](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt#L17-L27) 直接冲突。

---

## 四、额外发现（文档没写、但证据/代码暴露）

1. **开场白重复（P1，需定位）**：`01/05` 同一会话两条 “Arrival”。现有插入路径是原子的（[CharacterDao.kt:75-97](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/CharacterDao.kt#L75-L97) `@Transaction` + `countMessages>0` 早退），`MIGRATION_6_7` 也不插开场白 → 最可能是**升级前已存在的重复行被原样保留**，但文档把它当“迁移正确性”的证据，属误用。
2. **1970 时间戳（P2）**：列表出现 `01-01`、聊天页头部 `Today 00:00`。头部文案 [values-en/strings.xml:79](TeaTalk/app/src/main/res/values-en/strings.xml#L79) 写死 “Today %1$s”，所以任何 `createdAt=0` 的消息都会显示成 “Today 00:00”，掩盖了数据问题。测试数据很可能是 SQL 注入（他们在 L46/L75 明说过注入），应在文档注明。
3. **`hasAnyChat()` 语义被改写（P2，回归风险）**：[CharacterRepository.kt:67](TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt#L67) 从 `dao.countChats()`（按消息）改为 `conversations.countAll()`；而迁移给**每个角色**都建了会话 → 只有卡、从没聊过的用户现在会启动进「聊天」标签页，且列表被 `lastContent != null` 过滤成空白（“还没有会话”）。文档未提。
4. **孤儿消息**：`MIGRATION_6_7` 回填用 `cv.characterId = messages.characterId`，characterId 无对应角色的旧行会停在 `conversationId=0`，从此不可见（无清理、无告警）。
5. **dist 未入库**：`.gitignore` 忽略 `dist/`，因此 commit `f30d9b6` 内**没有 APK 与 sha256**，交付物只存在于本机；建议在文档里固化 `versionCode/sha256` 文本（现已在本报告第 18 行给出）。

---

## 五、总体评级

| 维度 | 判定 |
|---|---|
| 测试数字与覆盖 | **真实但不等于“功能已验证”**：75/0/0 与 XML 完全吻合；可 31 条 app 测试**没有一条**触碰 conversations/DAO/ChatEngine/迁移（`grep` 全空，无 room-testing/Robolectric 依赖） |
| DB 迁移 v1→v7 | **间接证据成立，验证不成立**：迁移注册完整、SQL 合理、旧数据在 0.7.0 可见；但零测试、零日志存档、无 v1→v7 全链路演练，“实测”字样应撤回 |
| 截图与声称状态 | 8 张里 6 张对应良好；1 张（步骤 1 删除确认）是修复前旧 UI；会话组截图含两条开场白 + 1970 时间戳，属**证据污染** |
| 版本号/sha256 | APK 侧完全自洽（11/0.7.0/74de0f42…，与构建产物二进制相同）；**文档侧版本号错误** |
| 措辞夸大 | 集中在 L144 / L165 / L131；L166 的“已知缺口”披露是全文最诚实的一句，应作为其他小节的写法模板 |

**Production Readiness：NEEDS WORK** —— 不需要回滚代码，需要一次**文档修订 + 一次带 logcat 的复测**（迁移 from=6 to=7、杀进程重进、删除当前会话退出），并重拍删除确认与会话组截图。以本项目的节奏，预计 1 轮、约 1–2 小时可收敛。

*限制说明：本机无连接设备（`logcat_devices` 为空），无法 dump AVD 内的 v7 数据库来确认“旧消息条数/重复开场白”的真实成因；若需要把第 1 条额外发现定死，请在连上设备后跑一次 `adb shell run-as com.teatalk.xs` 取 `databases/teatalk.db` 复核。*
