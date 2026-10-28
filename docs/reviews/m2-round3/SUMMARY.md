# M2 第三轮审阅汇总（对象 `f30d9b6` / 0.7.0）

四位专家（Software Architect / UX Architect / UI Designer / Reality Checker）于 2026-10-29 审阅 M2 代码与 UI。
原文见同目录 `01-`…`04-`。本文件只做去重与排序，修复计划见文末。

## 总判定

| 维度 | 判定 |
|---|---|
| 架构（写路径/事务/迁移） | NEEDS WORK：P0×2、P1×8、P2×12 |
| 交互（会话可见性/守卫/回执） | NEEDS WORK：P0×4、P1×7 |
| 视觉（分层/字阶/对比） | 文字对比度 AAA；层分离度与行高需修：P0×3 |
| 证据（文档 vs 工件） | NEEDS WORK：文档有 3 处夸大/过期，需修订 + 补测 |

## P0 修复状态（2026-10-29，0.7.2）

| # | 项 | 状态 |
|---|---|---|
| 1 | 停止/完成双写同一变体 | ✅ 0.7.1（`ChatEvent.Completed` 单次落库 + mutex） |
| 2 | `job=null` 早于 join | ✅ 0.7.1（Started 事件 + 锚点写库） |
| 3 | 聊天页看不到当前会话 | ✅ 0.7.2（头部会话名 + 自动命名） |
| 4 | 流式无出口守卫 | ✅ 0.7.2（BackHandler=停止；新建/删除/切换生成中拒绝） |
| 5 | 删除不可撤销、回执丢 | ✅ 0.7.2（条数确认 + 列表页 Undo + 快照恢复） |
| 6 | 视觉层差/行高/点击区 | ✅ 0.7.1（CodeSurfaces、LocalTextStyle、48dp） |

## P0（原始 6 条）

1. **停止与完成双写同一变体槽**：`Completed` 落库与 `stop()` 的 `appendReply(stopped)` 竞争，取消点落在写库内 → 完整回复被截断版覆盖或产生重复变体。根因：`appendAssistantReply` 非事务 RMW + `job` 提前置空。`ChatScreen.kt:262-278,326-346` / `CharacterRepository.kt:314-334`
2. **`job = null` 早于 `join()`**：单飞闸门失效，陈旧 stopped 回复插到新一轮 user 消息之后，且 `pendingProvider` 跨轮共享会写错来源。`ChatScreen.kt:265-277,188`
3. **聊天页看不到“当前会话”**：`ChatHeader` 只显示角色名，切换/新建/重命名后无任何可见回执；同一角色多会话在列表与面板里无法区分（都叫 Untitled / 都显示角色名）。`ChatScreen.kt:750` / `ChatListScreen.kt:154`
4. **流式生成无出口守卫**：返回/切换会话/新建/删除都不 `stop()`，也无 `BackHandler` → 半截回复静默丢失；删当前会话时 job 仍在跑。`HomeScreen.kt:226-231` / `ChatScreen.kt:308-314`
5. **删除会话不可撤销且回执会丢**：物理删除、无撤销；`onDeleted→onBack` 销毁 VM，`chat_conversation_deleted` 提示闪没；确认框不含消息条数、无目标区分。
6. **视觉层差 ≈1.0–1.09:1**：代码块/行内代码/下拉菜单/重命名输入框在暗色（浅色同样）下与背景几乎同色；需新增 `codeSurface`/`codeInlineSurface` 令牌。另：11sp 级文字继承 `bodyLarge` 24sp 行高导致行距倒挂；变体箭头 32dp < 48dp。

## P1 重点（合并同类）

- 写路径：`sendUserMessage` 丢 id（回复宿主靠“最后一条是 assistant”推断）；跨 DAO 组合写无 `@Transaction`（删会话/建会话/删角色）；生成中切会话丢在飞回复。
- 迁移：`exportSchema=false` 导致迁移测试“写不了”；`MIGRATION_6_7` 回填子查询可能产生 NULL → NOT NULL 失败即启动崩；`hasAnyChat()` 改 `countAll()>0` 与列表 `lastContent!=null` 口径不一致 → 只有卡没聊过的用户升级后落在空聊天页。
- 性能：`ChatEngine.stream` 在 Main 上解析整张卡 JSON（详情页已 `flowOn(Default)`，聊天路径漏）；`MarkdownText` 每次重组全量重解析（+ 流式字符串 O(n²) 拼接）。
- 交互：一个层级三种容器（下拉菜单/底部面板/对话框）叠加模态；长按面板 4 个等权按钮、危险操作夹中间；错误横幅不可关闭、preflight 错误点“重试”必然再失败；IME 弹起后停止跟随；来源脚注超宽/重复/孤儿行；会话面板无滚动（`forEach` 不是 `LazyColumn`）；破坏性操作四种不同形态（步数/文案/撤销/回执不一致）。
- 视觉：字阶 11/11.5/12/14/15/16/19.5sp 未落令牌；对话框标题 16sp vs 24sp 两套；Markdown H1 19.5sp 大于 AppBar；引用竖条 4.57:1 抢戏；列表圆点撑高；气泡 16dp 圆角与 13/10 内距不为 4 栅格。
- 无障碍：气泡动作面仅手势、TalkBack 不可达；变体箭头语义合并成一个节点；会话行“重命名/删除”同名按钮无上下文；死控件（`+`/emoji/搜索）可聚焦。

## 证据问题（Reality Checker，需先修）

1. `docs/09-M2-进度与验收.md` 头部版本仍是 0.4.0（实为 0.7.0 / versionCode 11）。
2. v6→v7 迁移「实测…开场白不重复」不成立：无迁移测试、无 logcat 存档，且 `05-switched.png` 同一会话出现两条开场白（测试数据注入造成，需清理后复测）。
3. 「删当前会话退出列表」「杀进程重进保持」无截图/日志工件。
4. `dist/` 被 gitignore，交付物只在本地；文档应固化 versionCode 与 sha256 文本。
5. 「5KB 内未见掉帧」为主观断言，应改为“肉眼未见卡顿，未做帧率测量”。
6. `01-chat-menu.png`/`05-switched.png` 背景含重复开场白与 1970 时间戳（`Today 00:00`），属测试数据污染，需重拍。

## 建议修复顺序（两批）

**批次 A（P0，约 1 天）**
1. 停止/完成竞态：`appendAssistantReply` 改 `@Transaction` DAO 单方法 + `stop()` 一次性提交守卫 + `job` 只在 finally 置空。
2. 写路径带宿主 id：`sendUserMessage` 返回 id → `streamReply(replyToUserMessageId)` → `appendReply(targetAssistantId, provider)`，删掉 `pendingProvider` 旁路；`ChatEvent.Started(provider)` 回报实际 Provider。
3. 生成中守卫：`BackHandler` + 切会话/新建/删除前 `stop()`；删除时先 cancel。
4. 会话身份可见：头部改「会话名 + 角色名 · 状态」，点击开面板；面板/列表行“会话名+角色名+相对时间+消息数”；新建会话自动命名「新会话 n」。
5. 删除会话：确认框补条数/角色名，结果改 nav result 在 HomeScreen 弹，加 5s「撤销」（软删除或延迟物理删除）。
6. 视觉令牌：`codeSurface` / `codeInlineSurface` / 菜单容器描边；新增 `TeaTalkTypography` 并把 11sp 族行高固定；变体箭头 48dp 触控。

**批次 B（P1/P2，约 1–1.5 天）**
迁移测试基建（`exportSchema=true` + `MigrationTestHelper` 1→7）+ 降级策略显式化；`hasAnyChat` 下沉 SQL；卡片 JSON 解析挪到 Default；Markdown `remember(text)` + 流式 StringBuilder；长按面板改锚定动作面板（复制/重新生成/查看全文/删除分区）；IME 感知滚动跟随；错误横幅可关闭 + preflight 给“去设置”；面板 `LazyColumn`；列表预览去 Markdown；英文单复数与标题文案；无障碍 desc 与语义拆分。

## 文档修订（随批次 A 一起提交）

见 `04-reality-checker.md` §三的 8 条替换建议；重点是头部版本、迁移“实测”措辞、L165 的打包陈述、L26 旧截图说明。

## 修复状态（0.7.1，2026-10-28）

| 编号 | 问题 | 状态 |
|---|---|---|
| P0-0 | 停止/完成双写覆盖、重复变体 | ✅ 已修（commit/stop 单次落库 + 锚点校验） |
| P0-1 | 单飞闸门失效、stopped 片段插到新问题下 | ✅ 已修（stopping 闸门 + 用户消息锚点） |
| P0-2 | provider 来源跨轮共享 | ✅ 已修（`ChatEvent.Started`） |
| P0-3 | 聊天页看不到当前会话 / 同角色会话难区分 | ✅ 已修（头部会话名 + 自动命名 + 列表标题） |
| P0-4 | 流式无出口守卫（返回/切换静默丢失） | ✅ 已修（BackHandler 停止并留在页 + 切换/新建/删除拒绝） |
| P0-5 | 删会话不可撤销、回执会丢、无条数 | ⏳ 未做（记录在 `docs/09` §5.7） |
| P0-6 | 层差 ~1.05:1、行高倒挂、点击区 32dp | ◑ 部分（代码面层差 + LocalTextStyle + 48dp 已修；下拉/输入框层差待验） |

证据与截图：`docs/09-M2-进度与验收.md` §5、`docs/screenshots/m2-b0/`。
