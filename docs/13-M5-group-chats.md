# M5 群聊（Group chats）进度与验收

> 版本 0.12.3 · DB v10 · 2026-11-02 · 真机验收通过

## 目标

把「多个角色在同一会话里轮流说话」做成一等公民，同时保持与 SillyTavern 的 prompt 兼容性：
群聊不是多会话拼接，而是**一条会话 + 多个成员 + 每条助手消息带 speaker**。

## 数据模型（DB v10）

| 变更 | 说明 |
| --- | --- |
| `conversations.kind` | `single` / `group`，默认 `single`；迁移 9→10 时补列 |
| `conversation_members` | `conversationId` + `characterId` + `position`，`(conversationId, characterId)` 唯一 |
| `messages.speakerId` | 群聊助手消息的发言人 id（单聊为 null） |

删除角色时级联清理成员行；群聊至少 2 人、上限 `CharacterRepository.MAX_GROUP_MEMBERS`。

## Prompt 组装（PromptAssembler.assembleGroup）

顺序（与单聊共用常量与 substitute）：

1. 群说明（system）：`This group chat includes: A, B. Write the next reply in a fictional group chat between A, B and User. Answer only as one of them, and never put a character name before the reply. Do not speak or act for the other members.`（0.12.1 加了「不要自称」约束：历史里的 `Name: ` 前缀会被模型模仿，表现为回复正文里又出现一次「角色名：」，气泡上已有名字时重复；0.12.3 再加「不要替其他成员发言」硬约束 —— 有 speaker 时是 `Do not speak or act for the other members; only <speaker> reacts.`，减少正文中段冒出别的角色台词）
2. 每个成员一个块（按加入顺序）：成员 system（空则回退 `Write X's next reply in a fictional chat between X and User.`）+ description / personality / scenario
3. `beforeWorld` 世界书块
4. `[Start a new Chat]`
5. 历史：user 原文；assistant 已存为 `Name: 内容`，群聊再做 substitute
6. `afterWorld` 世界书块（插在 `[Start a new Chat]` 之前）
7. 每个成员的 post-history instructions（PHI），按成员顺序

群聊**不注入** `mesExample`（`[Example Chat]` 只在单聊出现）。

## 交互

- 角色页 `⋮ → New group`：多选（≥2）+ 可选群名；创建后直接进入会话
- 会话头部：群名 + `N members`；`⋮ → Group members` 打开成员面板（增/删，最少 2 人）
- 输入区 `@` 按钮：成员列表，点选把 `@Name ` 插入输入框
- 助手气泡：发言人名字 + 发言人头像；发言者按成员轮转（按历史 assistant 条数取模，确定性）
- 群聊不写开场白（`ensureGreeting` 对 group 直接返回）
- 0.12.1：群头带 `This turn, reply only as <speaker>`；落库前 `stripSpeakerPrefix` 会剥掉行首任意成员名 + 冒号（含连续多行），修复「气泡上已有名字、正文里又出现一次」
- 0.12.2：群聊回复按「行首成员名 + 冒号」拆成多条气泡（`GroupReplySplitter`），每段各自带 `speakerId` 落库；只有一段时仍走 `appendAssistantReply`（保留「重新生成 = 追加变体」语义），多段时走 `appendGroupReplies` 并先清掉锚点之后的旧助手消息
- 0.12.2：输入框 `@` 支持前缀补全 —— 只在输入尾部有 `@前缀` 时按前缀过滤候选；点选替换该前缀而不是叠加；无匹配时提示「没有匹配的成员」
- 0.12.2：成员面板在只剩 `MIN_GROUP_MEMBERS`（2）人时，移除按钮变灰并显示「群聊至少保留 2 位成员」
- 0.12.3：群说明加「不要替其他成员发言」硬约束（neutral / speaker 两版都带），降低正文中段出现「另一角色：台词」的概率；文本层仍不做强制拆分（避免误伤叙述里的冒号）

## 测试

`app/src/test/java/com/teatalk/xs/data/PromptAssemblerGroupTest.kt`（5 条）：群头与成员回退 system、world 块与 `[Start a new Chat]` 顺序、成员 PHI 顺序与历史前缀、群聊不含 example、空成员返回空。

`app/src/test/java/com/teatalk/xs/data/PromptAssemblerGroupGoldenTest.kt`（2 条全量等值）：整条消息列表逐条钉死（群说明 → before 世界书 → 成员块 → after 世界书 → `[Start a new Chat]` → 历史 → PHI）；speaker 只改群说明、后续逐条不变。

`app/src/test/java/com/teatalk/xs/data/GroupReplySplitterTest.kt`（7 条）：两人分行并剥前缀、续行归属、无前缀归 fallback、行内出现名字不拆、相邻同说话者合并、未知说话者前缀不拆、空白回复 0 段。

全量：`:core:test` + `:app:testDebugUnitTest` 通过。

## 设备验收（真机 XPLGL21A22000007，0.12.0/0.12.1）

1. 建群「Tea Trio」（Mira + Aster）→ 头部 `2 members`，进入会话
2. 发 `Tell me about Eldoria` → 回复气泡带 `Aster` 名字与头像；日志 `prompt.ready msgs=11 sysChars=115`
3. `View last request`：群说明 + 两成员块 + `[Start a new Chat]` + 历史 + PHI，逐条可见
4. 成员面板：Mira / Aster 可 Remove，`Add member` 保留（受最少 2 人约束）
5. `@` 菜单列出 Mira / Aster

真机（API28+ / 1080x2340）额外验证：建群（梁诗月 + 白沐桐）、真实 DeepSeek 流式回复（`chat.req.ok ms=2422 ttftMs=1818 chunks=77`）、发言人气泡头像、`⋮ → 查看本次请求`（系统提示词 109 字符 + 世界书 5 条 7874 字符）、成员面板、`@` 列表。

截图：`docs/screenshots/m5/01-create-group.png` … `06-group-prompt-panel.png`；真机截图 `docs/screenshots/m5-real/`。

## 设备验收（真机 TAS-AN00 · Android 12 · 0.12.3，2026-11-02）

真机重连后（adb 走 `ANDROID_ADB_SERVER_PORT=5038`，避开默认 5037 的占用）对 0.12.2/0.12.3 的三处 UI 与硬约束做了实机确认：

| 项 | 操作 | 结果 | 截图 |
| --- | --- | --- | --- |
| `@` 前缀过滤 | 输入 `@z` 后点 `@` | 菜单只剩「没有匹配的成员」 | `m5-real-v2/01-mention-filtered-empty.png` |
| `@` 点选替换 | 尾部为 `@` 时点 `@` → 选「白沐桐」 | 输入框变成 `@白沐桐`，无重复前缀 | `m5-real-v2/02-mention-replaced.png` |
| 最少成员提示 | 2 人群 `⋮ → 群聊成员` | 顶部显示「群聊至少保留 2 位成员」，Remove 变灰 | `m5-real-v2/03-min-member-hint.png` |
| 多气泡拆分 | 发 `Liang Shiyue and Bai Mutong, each of you say one line about your mood now.` | 回复拆成两条 assistant 气泡，分别挂「白沐桐」「梁诗月」头像，正文不再带 `Name：` 前缀 | `m5-real-v2/04-multi-bubble.png` |
| 0.12.3 硬约束 | 发不点名任何人的 `What time is it now?` | 只有「梁诗月」一条气泡（未替白沐桐发言） | `m5-real-v2/05-single-responder.png` |

历史里 0.12.1 之前落库的多行「梁诗月：…」正文不会被改写（只影响显示），新消息按 0.12.2+ 规则处理。

## 已知待办

- 群聊 golden 仍需接 ST 快照做跨实现对拍（当前 `PromptAssemblerGroupGoldenTest` 是仓库内自锁）
- 0.12.1 之前落库的群聊历史仍含 `Name：` 前缀（只影响显示，不回写）
- 模型让「另一个角色」说话时（正文中段的 `Name：`）：0.12.3 只加了 prompt 硬约束降低概率，**文本层仍不拆**（放宽拆分容易误伤叙述中的冒号）；若实测仍频繁出现，再考虑「行首/换行后的 `Name：`」限定拆分

## 产物

- APK：`dist/TeaTalk-0.12.3-debug.apk`
- sha256：`095adc5a673d4d3d178734b9c8bd1d9437aa4696ada68160779c69e864a46b78`
