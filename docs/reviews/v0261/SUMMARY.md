# 0.26.1 代码检视记录

审阅对象：`0.26.1` 未提交改动（开场白选择 + 真实卡回归测试收紧），范围：

- `app/src/main/java/com/teatalk/xs/data/Greetings.kt`（新增）
- `app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt`（`startConversation` / `createConversationInternal` / `ensureGreeting`）
- `app/src/main/java/com/teatalk/xs/ui/character/CharacterDetailScreen.kt`（开场白选择弹层、按钮门槛）
- `app/src/main/java/com/teatalk/xs/ui/HomeScreen.kt`（接线）
- `app/src/test/java/com/teatalk/xs/data/CardCorpusTest.kt`、`GreetingsTest.kt`
- `res/values/strings.xml`、`res/values-en/strings.xml`

检视方式：独立 reviewer（只读、不构建），逐文件读实现并要求给出 `文件:行号` 证据。

## P0

无。

## P1（全部已修）

| # | 问题 | 证据 | 修法 |
| --- | --- | --- | --- |
| 1 | `hasConversation` 初值为 `false`，首帧点击会先弹选择弹层；随后 `startConversation` 复用已有会话，选中的开场白被静默丢弃 | `CharacterDetailScreen.kt:131`、`CharacterRepository.kt:249` | 改为 `StateFlow<Boolean?>`（`null` = 未知），按钮 `clickable(enabled = character != null && hasConversation != null)`，加载完成前不可点 |
| 2 | 角色数据未到齐时 `view` 为空 CardView，`needsChoice("")` 为 false，点击直插 `first_mes` 占位符 | `CharacterDetailScreen.kt:128`、`CharacterRepository.kt:278` | 按钮同时要求 `character != null`；仓库侧也做兜底（见下） |
| 3 | 占位模板 `first_mes` 仍是候选第 1 项，最容易误点 | `Greetings.kt:17` | 新增 `Greetings.isShell()`（剥离 `<…>`、`{{…}}`、`[…]` 后无字母数字，或去壳后 < 3 字）；有可读备用开场白时从候选中剔除 |
| 4 | 「新建会话」仍会插入占位符，导致老用户永远绕不过去 | `ChatScreen.kt:603` → `CharacterRepository.kt:262` | `createConversationInternal` / `ensureGreeting` 在 `greeting == null` 时用 `Greetings.defaultOpening()`，并对结果再做一次 `isShell` 过滤，最后退回 `greeting_fallback` |

## P2（已修）

- 摘要按码点截断，避免把 emoji 代理对切成孤立的一半（`Greetings.truncate`）。
- 弹层内 `options()` / `preview()` 用 `remember(...)` 记忆，避免大卡每次重组都重新切 100KB 字符串。
- `preview()` 找不到「像正文」的行时退回第一条非空行；正文阈值从 8 降到 4，短中文开场白不再被跳过。
- 弹层状态改 `rememberSaveable`，旋转不丢。
- 删除已无调用方的 `ensureConversation`，只留 `startConversation`。
- `CardCorpusTest` 增加「占位模板且没有可读备用开场白」的统计（不判失败，App 会退回 fallback），并把空壳卡与占位卡分开打印。
- 文案去掉「多段」措辞（只有一段时也会弹层），中英同步。

## 未修（记录在案）

- 弹层正文仍是 `Column + verticalScroll`，开场白很多（45 条）时一次性组合全部行；目前最大 45 条可接受，未改 `LazyColumn`。
- 没有 Room 层的 `startConversation` 集成测试（需要 Robolectric/仪器测试）；当前由单测覆盖纯函数 + 真机验证覆盖写库路径。

## 真机复验（TAS-AN00 / Android 12）

- 选「开场白 1」后 `Chat conversation.start characterId=1 conversationId=1 created=true greetingChars=1334`，气泡显示该段正文。
- 已有会话的卡再点「开始聊天」直接进聊天，不弹层。
- 首轮装包后在 `Greetings` 静态初始化崩溃：`PatternSyntaxException: \{ \{[^}]*}}`（Android ICU 比 JVM 严格）→ 改为转义 `\}` / `\]` 后复验通过。该问题 JVM 单测无法暴露，已写入文档的「已知限制」。
