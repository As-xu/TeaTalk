# M5 群聊（Group chats）进度与验收

> 版本 0.12.0 · DB v10 · 2026-10-31

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

1. 群说明（system）：`This group chat includes: A, B. Write the next reply in a fictional group chat between A, B and User. Answer only as one of them, and never put a character name before the reply.`（0.12.1 起加了「不要自称」约束：历史里的 `Name: ` 前缀会被模型模仿，表现为回复正文里又出现一次「角色名：」，气泡上已有名字时重复）
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

## 测试

`app/src/test/java/com/teatalk/xs/data/PromptAssemblerGroupTest.kt`（5 条）：群头与成员回退 system、world 块与 `[Start a new Chat]` 顺序、成员 PHI 顺序与历史前缀、群聊不含 example、空成员返回空。

全量：`:core:test` + `:app:testDebugUnitTest` 通过。

## 设备验收（真机 XPLGL21A22000007，0.12.0/0.12.1）

1. 建群「Tea Trio」（Mira + Aster）→ 头部 `2 members`，进入会话
2. 发 `Tell me about Eldoria` → 回复气泡带 `Aster` 名字与头像；日志 `prompt.ready msgs=11 sysChars=115`
3. `View last request`：群说明 + 两成员块 + `[Start a new Chat]` + 历史 + PHI，逐条可见
4. 成员面板：Mira / Aster 可 Remove，`Add member` 保留（受最少 2 人约束）
5. `@` 菜单列出 Mira / Aster

真机（API28+ / 1080x2340）额外验证：建群（梁诗月 + 白沐桐）、真实 DeepSeek 流式回复（`chat.req.ok ms=2422 ttftMs=1818 chunks=77`）、发言人气泡头像、`⋮ → 查看本次请求`（系统提示词 109 字符 + 世界书 5 条 7874 字符）、成员面板、`@` 列表。

截图：`docs/screenshots/m5/01-create-group.png` … `06-group-prompt-panel.png`；真机截图 `docs/screenshots/m5-real/`。

## 已知待办

- 群聊 golden case 与单聊对拍脚本打通（当前 5 条断言覆盖结构，未接 ST 快照）
- 最后一名成员不可移除时的文案/禁用态更明确
- `@` 输入补全（当前为插入文本，不做过滤提示）

## 产物

- APK：`dist/TeaTalk-0.12.0-debug.apk`
- sha256：`74851599ffa1359d011d1ca940a5f57c53e777403d8b001b3447f074651a7c1f`
