# UX Architect — M2 审阅（对象 f30d9b6 / 0.7.0）

## M2 交互设计审阅 — `f30d9b6`（0.7.0 多会话）

**审阅范围**：`app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt`（1031 行）、`app/src/main/java/com/teatalk/xs/ui/chats/ChatListScreen.kt`、`app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt`、`app/src/main/java/com/teatalk/xs/data/db/{ConversationDao.kt,TeaTalkDatabase.kt}`、`app/src/main/java/com/teatalk/xs/ui/HomeScreen.kt`、`values/strings.xml`、`values-en/strings.xml`、`docs/design/tokens.md`、`docs/09-M2-进度与验收.md`、截图 `docs/screenshots/{m2,m2-fix,m2-conversations,m2-b1,m2-b2,m1}/`。**未修改任何代码。**

**方法说明**：本机没有连接设备（`adb: no devices`），所以结论 = 源码静态推演 + 既有截图取证。为了避免沿用错误判断，我用像素采样复核了一处视觉疑点：`m2/01-retry-dialog.png` 和 `m2-fix/03` 里浅色对话框压深色聊天页，实测背景为 (100,99,96) = 奶油底 ×60% 遮罩，说明那是**浅色主题 + 对话框默认 dim 0.6**；`m2-conversations/*` 则是深色主题。因此“深色模式对话框配色错”不成立，不列入问题。

**总判定：NEEDS WORK（4 条 P0）。** 数据层（会话表、级联删除、`updatedAt` 维护）是干净的；问题集中在**层级可见性**——M2 把“会话”做成了数据与操作的对象，却没有把它做成**屏幕上可见的对象**。

---

## P0 — 破坏信任 / 会做错事

### P0-1 聊天页完全看不到“我在哪个会话”，切换也没有任何确认

`ChatScreen.kt:750` `ChatHeader` 只渲染角色名 + 固定副标题 `chat_online_status`（“在线 · 角色扮演模式”）。会话标题**在聊天页没有出现位置**：状态机里有 `conversation` flow，UI 却一个字段都没用。

证据：`m2-conversations/01-chat-menu.png` 与 `05-switched.png` 是**两个不同会话**的截图，头部逐像素一致。

影响（这是 M2 的核心损失）：
- 点「新建会话」→ 只能靠消息内容变了才知道成功了；新会话与旧会话开场白同为 `firstMes`，看起来就是“没反应”。
- 重命名后回到聊天页，看不到自己起的名字是否生效。
- 「⋯ → 全部会话 → 切换」之后，界面没有任何“当前会话”标识，误以为切换失败。
- 空标题回落到角色名（`ChatListScreen.kt:154`），于是标题这一层在 3 个界面里全部退化掉。

建议：头部改成**会话优先**——主标题 = 会话名（空白时用 `chat_conversation_untitled`），副标题 = 角色名（+ 可选 online 状态）；主标题整体可点，直接开 `ConversationSheet`。这样「⋯」里就只剩破坏性/次要动作，同时把“当前会话”变成常驻可见状态。切换后标题文本变化本身就是最好的回执。

### P0-2 同一角色的多个会话在列表与面板里不可区分

`ChatListScreen.kt:154` 用 `title.ifBlank { characterName }`；`ChatScreen.kt:826` 面板行只有 `title` + `chat_conversation_updated(timeOf(updatedAt))`，而 `timeOf` 只输出 `HH:mm`。

证据：
- `06-chat-list.png`：两行都是 **Old Card**、同头像、预览分别是 `**bold** *italic*` / `## Arrival…`，时间 `15:17` / `01-01`。
- `02-all-chats.png`：两行都是 **Untitled chat**，只有 `16:09` / `16:42` 不同。

影响：选错会话、删错会话的**唯一防线就是两位数字的时间**。而这正是 P0-3 的入口。

建议（三处一起改，成本很小）：
1. 行结构改为 **会话名（主） + 角色名 · 最新消息预览（次）**，而不是二选一糊在一起；
2. 面板行加**消息条数**与**相对日期**（“今天 16:09 / 昨天 / 10-27”），不要只有 `HH:mm`；
3. `createConversation` 时自动命名（首条用户消息截断，或“会话 n”），`M2 文档`里记的“标题只能手填”是当前最大的可发现性/可分性缺口，自动命名能一次解决列表、面板、聊天页三处的身份问题。

### P0-3 删除会话：目标不唯一 + 无撤销 + 确认后无回执

三件事叠在一条路径上：

1. **确认框无法识别目标**：`ChatScreen.kt:731-741` 正文只有 `chat_conversation_delete_message(target.title.ifBlank{未命名会话})`——两个会话都叫“未命名会话”时，这句中文对两者同时成立。对比消息删除（`chat_delete_message_variants` 会报版本数），会话删除连**消息条数**都不给。
2. **没有撤销**：`ChatViewModel.deleteConversation` → `repository.deleteConversation` 先 `deleteMessages` 再 `deleteById`，物理删除。全 app 搜不到任何 `actionLabel`/「撤销」（角色卡、接口、消息、会话都没有）。
3. **回执丢失**：`deleteConversation` 里 `_notice.value = chat_conversation_deleted` 之后立刻 `onDeleted()` → `onBack()`。snackbar 由**即将被销毁的这个 ViewModel/Composition** 持有，HomeScreen 的 `snackbarHostState` 拿不到这个事件。「已删除会话」实际是闪一下或根本不出。`createConversation` 的 `chat_conversation_created` 同理（`ChatScreen.kt:444-446`）。

另外面板里 `Icons.Filled.Delete`（`ChatScreen.kt:866`）与 `Icons.Filled.Edit` 相邻、同为 `onSurfaceVariant`、常显、48dp——破坏性入口与安全入口等权相邻。

建议：① 会话删除确认框补「共 N 条消息 · 创建于…」并把会话名做成可区分的（依赖 P0-2 的自动命名）；② 会话行改成**一个「⋯」→ 动作面板**（或滑动删除），把重命名/删除从常显图标里拿掉；③ 删除做成**软删除 + Snackbar「撤销」**（`docs/09` 已把“回收站”列为已知缺口，先给 5 秒撤销即可，不必建表）；④ 新建/删除的结果改为 **nav result / 一次性事件**，在 HomeScreen 弹，别放在被销毁的 VM 上。

### P0-4 流式生成没有守卫：返回 / 切换会话 / 新建会话会静默丢弃半个回复

`HomeScreen` 的 `onOpenConversation` 用 `popUpTo(CHAT){inclusive=true}` 重建 ChatScreen；整个工程**没有任何 `BackHandler`**（grep 为空）。老 entry 销毁 → `viewModelScope` 取消 → 流取消，而 `stop()` 里那段 `cancel + join + append(STATUS_STOPPED) + chat_stopped` 永远不会执行。

后果链：用户消息已落库 → 半截回复丢失且**不留任何痕迹**（既没有“已停止”气泡，也没有 toast）→ 用户回到这个会话只看到自己那条孤零零的消息，会以为“我刚才发送失败了”。如果在面板里删掉当前正在生成的会话，还会出现“删了会话但 job 仍在跑”的窗口期（`deleteConversation` 不 cancel `job`）。

顺带：`⋯` 菜单与面板在 `streaming == true` 时完全可用；`retry()` 有 `job != null` 守卫（上一轮 F1 修的），但**切会话/返回/新建/删除**这四个出口没有。

建议：① 加 `BackHandler(enabled = streaming) { viewModel.stop(); onBack() }`，让返回键走“停止并保存半截”；② `onOpenConversation`/`createConversation`/`deleteConversation` 之前先 `stop()`（或统一在 VM 的 `onCleared()` 里落盘一次半截——但 `onCleared` 不能 suspend，只能 `runBlocking`,不建议）；③ 生成中禁用面板里的删除，或删除时先 cancel。

---

## P1 — 交互模型与状态可见性

### P1-1 一个层级用了三种容器，且「⋯」承担的是导航而不是菜单

现状：会话级切换 = 底部面板；会话级新建 = 下拉菜单；消息级动作 = AlertDialog；消息级版本 = 气泡下方内联条。四种形态对应两种手势、三层模态。

`m2-conversations/01-chat-menu.png` 暴露两个具体问题：`DropdownMenu` 浮在第一条消息上（遮挡正文），两项无图标无分隔，位置在右上角拇指最远处。

更糟的是**叠加模态**：`ChatScreen.kt:824-834` 里点重命名/删除时 `showConversations` 仍为 `true`，于是 AlertDialog 叠在 ModalBottomSheet 之上（`04-delete-confirm.png` 可见：对话框 + 半透明面板 + 聊天页三层同屏）。旋转、遮挡、返回键要按三次才能回到消息。

建议：会话标题（P0-1）直接开面板 → 面板内自带「+ 新建会话」和每行「⋯」→「⋯」菜单取消或只保留“会话设置”。需要保留 ⋯ 时至少给它 `leadingIcon` 并移到标题右侧的会话上下文里。打开确认框前先 `showConversations = false`（或把确认做成面板内的一次性行内态）。

### P1-2 消息长按面板 = 4 个等权按钮的模态框

`ChatScreen.kt:609-670`：`confirmButton = 复制`，`dismissButton = Row { 重试 · 删除 · 关闭 }`。截图 `m2/01-retry-dialog.png` 的视觉顺序是 `Retry | Delete | Close | Copy` —— **破坏性动作夹在中间**，主次完全没有区分，且内层 `Row` 不可换行（1.3× 字号或德语长度会挤压/裁切）。对话框正文 `Box(heightIn max 420.dp).verticalScroll` 不可选中（无 `SelectionContainer`），角色扮演的长回复只能整条复制。

建议：改为锚定气泡的 popup / 底部动作面板（图标 + 文字行）：`复制` / `重新生成` / `查看全文`（仅当截断时）/ 分隔线 / `删除`（error 色，单独一区）。「查看全文」再开长文本页并启用 `SelectionContainer`。这条与上一轮 `02-ux-architect.md` #5/#6 一致，仍未落地——而且它和 P0-1 的「⋯」改法可以一次性解决“手势隐藏 + 模态过重”两个问题。

### P1-3 一次失败，两处信号，横幅既不可关也不可解

`ChatScreen.kt:881` `ErrorBanner`：`errorContainer` 底 + 文案 + 单个「重试」，**没有关闭**，也不随滚动/发送以外的事件消失（`_error` 只在 `send/retry` 清空）。`m1/08-chat-error.png` 里它悬在输入框上方，与真正失败的回合隔着两条用户消息。

两个具体缺陷：
- **preflight 错误点重试必然再失败**：`NO_PROVIDER` 的文案是“还没有配置模型，去「我的 → 模型与接口」添加一个”，但横幅只给「重试」，`retry()` 会重新走 `preflightError()` → 同一条横幅。应给「去设置」跳转 + 可关闭。
- **流式失败点在历史里**：失败后保存的 failed 片段在气泡上有 `生成失败`（`chat_variant_label` 状态位），但失败回合通常已经滚出视口，用户只看到底部一条横幅。建议把流式错误做成**气泡下方的内联行**（红字 + 重试），横幅只保留 preflight 类错误。

### P1-4 键盘一开，流式输出就不再跟随

`ChatScreen.kt:407-419`：`stickToBottom = lastVisible >= totalItemsCount - 2`，`LaunchedEffect(messages.size, streamText.length, streaming)` 只在 `stickToBottom` 为真时 `scrollToItem(count)`。

IME 弹出会让 `Column` 收缩 → LazyColumn 视口变矮，但滚动偏移不变 → 最后几条落到键盘后面 → `lastVisible` 不再是 `total-2` → **`stickToBottom` 变 false 且再也不会自动恢复**。用户一打字，正在流式增长的文字就永久跑到键盘底下（需要手动滑回底部）。同理：错误横幅出现、输入框从 1 行长到 4 行，都会让最新气泡被推入遮挡区，而这些变化都不在 `LaunchedEffect` 的 key 里。

建议：改成“内容溢出才滚”的增量跟随（比较 `layoutInfo` 的 `viewportEndOffset` 与最后一项的 `offset+size`），并把 IME 高度/输入框高度纳入触发；或给列表加 `reverseLayout` 之外更简单的做法——在 `LaunchedEffect` 里同时监听 `WindowInsets.ime` 的高度变化，变化时若原本贴底则重贴一次。

### P1-5 版本条：能看见，但既不好点也说不清

`ChatScreen.kt:961`：`semantics(mergeDescendants = true) { contentDescription = label }` 包住两个 32dp `IconButton`。32dp 低于 48dp 触控标准（`tokens.md` 自己写了 48dp），在 14dp 行距的列表里，一次向上滚动很容易落在箭头行上；而 `mergeDescendants` 会把两个箭头**并成一个可聚焦节点**，TalkBack 下有极大概率只剩一个点击动作 —— 逐版本切换对读屏用户不可用。

其余缺口：
- 状态只在**当前**版本上显示（“版本 2/2 · 已停止”），看不到“版本 1 = 失败、版本 2 = 完整”，选择时缺少依据；
- 版本条没有时间/模型/长度，无法区分两次重试的差异；
- `重试` 只对最后一条 assistant 消息出现在长按面板（`ChatScreen.kt:609+` 的 `id == lastAssistantId` 条件），其他消息上这个动作**静默消失**而不是灰显/解释；
- 切换后气泡高度突变，列表不回锚，可能整段跳走；
- 切换无任何过渡反馈（文本瞬间替换），无法确认“我确实换了一版”。

建议：箭头改 40–48dp，**不要合并语义**（父节点只给 `contentDescription`，两个 `IconButton` 各自带 `contentDescription`，文本节点用 `stateDescription` 报 “2/3”）；多版本时在长文案气泡的版本条上补“其他版本”入口（一次看到全部候选 + 状态 + 时间）。

### P1-6 会话面板不随内容增长，也没有滚动

`ChatScreen.kt:817-870`：`ModalBottomSheet { Column { conversations.forEach { Row(...) } } }` —— 没有 `LazyColumn`，没有 `verticalScroll`，也没有 `heightIn`。`M2 文档`写“单角色会话量通常很小”，但新建会话是无限动作；十几条之后超出面板可视高度，下半部分**不可达**（M3 sheet 不会替内容滚动）。

另外：当前项的 `Icons.Filled.Check`（`ChatScreen.kt:849`）`contentDescription = null`，读屏用户无法知道哪条是当前会话；点击当前行只做了 `showConversations = false`（`ChatScreen.kt:675`），点了像“没反应”。

建议：改 `LazyColumn` + 面板内 `heightIn(max = …)`；当前项加文字“当前”或 `stateDescription`；当前行点击直接关面板即可（符合预期）。

### P1-7 破坏性操作四处不同形（一致性问题）

| 操作 | 入口 | 确认步数 | 确认文案要点 | 撤销 | 回执 |
|---|---|---|---|---|---|
| 删消息 | 长按 → 预览框 → `删除` | **2 步** | 报版本数（`chat_delete_message_variants`） | 无 | `已删除` snackbar（VM 存活，可靠） |
| 删会话 | 面板行内 trash（常显） | 1 步 | 只有标题，无条数 | 无 | 目标为当前会话时**丢失** |
| 删角色卡 | 卡片菜单 | 1 步 | 提示“聊天记录会一并删除” | 无 | 有 |
| 删接口 | 列表菜单 | 1 步 | 名称 | 无 | — |

三个缺陷：消息删除比其他多一步（从预览框里再去确认）；会话删除的破坏性入口最廉价（常显图标、与重命名等权相邻、无撤销）；会话删除是对**角色全部上下文**的清除，却比删一条消息还轻。

另：对话框标题字号也不一致——`chat_delete_title` 被硬写成 `fontSize = 16.sp`（`ChatScreen.kt:644`、`611`），会话删除用 M3 默认 `headlineSmall`（≈24sp），截图 `m2-fix/03` vs `m2-conversations/04` 一眼可见。关闭按钮命名也不统一：预览框用 `关闭`，其余用 `取消`。

建议：定一条规则——**破坏性操作 = 1 步确认 + 允许撤销**；唯一存疑的“删会话”用信息补足（条数/时间/角色名）而不是加一步；所有对话框标题统一走 token（16sp 或 `headlineSmall`，二选一并落到 `tokens.md`）；`关闭` 全部改 `取消`。

---

## P2 — 文案、i18n、细节一致性

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | 英文复数 “1 chats” | `values-en/strings.xml:31-33` 只保留 `<item quantity="other">%d chats</item>`，删掉了 `one` | 补回 `one: %d chat`；中文只需 `other` |
| 2 | “全部会话”其实是“该角色的会话”，标题与范围不符（中英同病） | `chat_conversations_title` / “All chats” | 「此角色的会话」/“Chats with Old Card” |
| 3 | 聊天列表预览渲染原始 Markdown | `06-chat-list.png` 的 `**bold** *italic*`、`## Arrival…` | 预览先剥 Markdown 语法（或取纯文本字段） |
| 4 | 时间格式两套且都缺上下文 | 列表 `MM-dd` 无年份；面板只有 `HH:mm`（`ChatScreen.kt:842` + `timeOf`） | 统一相对时间：今天 HH:mm / 昨天 / MM-dd / yyyy-MM-dd |
| 5 | 未配置模型时，**所有**历史消息都会显示“与当前接口不同” | `providerHint = providerId?.takeIf { it != activeProviderId }`，`activeProviderId` 为 null 时每条都成立 | 空 provider 时隐藏该提示，改为顶部一条“当前未配置模型” |
| 6 | provider 提示的可读性 | `m2-b2/01`：11sp、两行、`?` 兜底名、与 `已停止`/版本条三行堆叠 | 合并成一行元数据（n/m · 状态 · 模型），点开看详情 |
| 7 | 首屏落点与列表口径矛盾 → 升级用户落到空列表 | `HomeScreen.kt:78` 用 `hasAnyChat()` = `conversations.countAll() > 0`，但 `ChatListScreen.kt:79` 过滤 `lastContent != null`；`MIGRATION_6_7` 为**每个**角色无条件建空会话 | `hasAnyChat` 改为“有消息的会话数” |
| 8 | 切换会话时输入框草稿丢失 | `popUpTo(CHAT){inclusive=true}` 重建 entry，草稿是 `rememberSaveable` 的页面内状态 | 切会话前把草稿回填到输入框或提示“草稿已保留/丢弃” |
| 9 | 面板/对话框状态用 `remember` 非 `rememberSaveable` | `showConversations`、`renameTarget`、`deleteTarget`、`preview` | 旋转即消失，改成存 id 并回查 |
| 10 | 死控件仍在 a11y 树里 | 输入框 `+`（`contentDescription=更多`，`onClick={}`）、emoji 图标有 desc 无 click、列表搜索按钮 `onClick={}` | 移除或加“敬请期待”态；至少这次别让 `+` 和头部 `⋯` 共用同一个“更多”标签 |
| 11 | Snackbar 用 88dp 魔法值定位 | `ChatScreen.kt:748` | 放进 input 上方的 Column 流式布局，随输入框高度自适应 |
| 12 | 只能整条复制 | 预览框 body 无 `SelectionContainer` | 加选择能力 |

## P3 — 值得顺手处理

- 重命名对话框：`BasicTextField` 无 `FocusRequester`（打开不聚焦、键盘不弹）、无最大长度、`保存` 恒可用；清空标题会**静默**回落到角色名（`ifBlank`），用户会以为重命名失败。
- `chat_busy_generating` 走 snackbar，但停止按钮就在旁边；生成中的「重试」用提示而非禁用态（上一轮 F1 修了空跑，仍不是“看得见的状态”）。
- 发送框无 `imeAction = Send`/`keyboardActions`，多行时只能点按钮。
- 头部头像 `contentDescription = 角色名` 与标题重复播报（“Old Card，Old Card”）。
- `+` 与 `⋯` 的 `contentDescription` 都是“更多”，同一屏两个同名控件。

---

## 信息架构：会话 vs 角色 vs 消息版本的层级

现在三层的**可见性**与**可达性**是错配的：

```
角色 (Character)        ← 头像/名字：处处可见
└─ 会话 (Conversation)  ← 只在“全部会话”面板里存在；聊天页不可见
   └─ 消息 (Message)
      └─ 版本 (Variant)  ← 气泡下方可见（1/n）
```

- **顶层缺失**：会话层是 M2 的主角，却是唯一在聊天页没有着落点的层（P0-1）。
- **可达性倒挂**：唯一能切会话的入口在一段会话**内部**。角色详情页的「开始聊天」→ `ensureConversation` → `conversations.latest()`（`ConversationDao.kt:14`，按 `updatedAt DESC`）。也就是说：一个角色有 5 个会话，从角色页永远只能进“最近那一条”；想进别的必须先随便进一条再开面板。建议角色详情页显示「N 个会话」入口，或让卡片长按直接列出会话。
- **版本层是死路**：只能左右翻，不能并列查看、不能只删某一版、不能对旧消息“以这一条重新生成”（`重试` 限最后一条）。上一轮 #7 已提，仍未落地。

建议的收敛形态（改动量小、一次解决 P0-1/P1-1/P1-2）：

```
┌ Old Card · Tea house          ⋯ │   ← 会话名为 H1，可直接点开切换面板
│  角色扮演 · 最近生成 16:42       │
├─────────────────────────────────┤
│ [气泡] 长按 → 锚定 popup 动作面板 │
│ [气泡] 下方元数据单行：2/3 · 已停止 · deepseek-flash │
└─────────────────────────────────┘
```

## 键盘与滚动 checklist（当前状态）

| 场景 | 现状 | 期望 |
|---|---|---|
| IME 弹出 | 视口收缩，`stickToBottom` 变 false，**停止跟随**（`ChatScreen.kt:407-419`） | 原本贴底则重贴底 |
| 流式中收到 token | 每次 delta 触发 `scrollToItem`（瞬时跳，无动画） | 增量 `scrollBy(overflow)`，只在溢出时滚 |
| 新消息插入 | `scrollToItem(count)` 直接跳 | 同左 + 150ms 动画（可选） |
| 错误横幅出现 | 不重贴 → 最新气泡被挤到横幅后面 | 出现/消失时重贴一次 |
| 输入框 1→4 行 | 同上 | 同上 |
| 切换会话 | 滚动位置重置，草稿丢失 | 保留草稿；或明确清空并提示 |
| 返回键（流式中） | 无 `BackHandler` → 丢半截（P0-4） | 停止并保存半截 |
| 版本切换后 | 高度突变、不回锚 | `animateScrollToItem(该条)` |

## 无障碍清单

| 严重度 | 问题 | 位置 |
|---|---|---|
| 高 | 消息动作面**完全不可达**：气泡只有 `pointerInput{detectTapGestures}`，没有 `onLongClick` 语义、没有 `customActions`、没有长按标签；TalkBack 用户无法复制/重试/删除任何消息 | `ChatScreen.kt` AssistantBubble/UserBubble |
| 高 | 版本箭头 32dp + `mergeDescendants` 合并成单节点，逐个切换大概率不可用 | `ChatScreen.kt:961` |
| 中 | 当前会话的 Check 无文本替代 | `ChatScreen.kt:849` |
| 中 | 面板每行两个同名按钮（“重命名会话”×N/“删除会话”×N），不含会话名 | `ChatScreen.kt:859,867` |
| 中 | 死控件可聚焦可点击（`+`、emoji、搜索） | `ChatScreen.kt:524,558`、`ChatListScreen.kt` 头部 |
| 低 | 头部头像与标题重复播报；两处 `contentDescription = 更多` | `ChatScreen.kt:524,787` |
| 低 | 对话框正文不可选中；错误横幅小字 12.5sp | 预览框 / `ErrorBanner` |

## 与上一轮审阅（`docs/reviews/m2/02-ux-architect.md`，对象 `a65f2fe`）的收敛

| 上轮项 | 现状 |
|---|---|
| F1 retry 无目标/无预检 | ✅ 已修 |
| F2 版本条 26dp 无标签无 desc | ⚠️ 部分：有了“版本 n/m”和 desc，仍是 32dp + 合并语义（P1-5） |
| F3 多版本删除文案 | ✅ 已修 |
| F4 滚动 off-by-one | ✅ 已修（但 IME 场景仍有 P1-4） |
| #5 手势隐藏动作面 / #6 四按钮对话框 | ❌ 未修，本轮因 M2 新增会话层而更严重（P1-2） |
| #7 版本身份/单版本删除 | ❌ 未修 |
| #9 原地重生成状态 | ❌ 未修 |
| #10 TalkBack / #11 Snackbar 88dp / #12 死控件 / #13 `remember` / #14 en 标题 | ❌ 全部未修 |
| 已记录的 B3–B10 | 未见对应提交 |

## 建议顺序（都不大，按“信任收益 / 行数”排）

1. **P0-1 头部会话身份**（~1h）：`Conversation + Character` 双行标题 + 点击开面板。一次性给会话层可发现性、切换回执、重命名回执。
2. **P0-3 + P0-4 守卫与回执**（~2h）：`BackHandler`/切会话前 `stop()`；删除确认补条数；结果改 nav result 在 HomeScreen 弹；会话删除加「撤销」snackbar。
3. **P1-2 + P1-1 动作面收敛**（~3h）：长按 → 锚定 popup/sheet（复制/重新生成/查看全文/删除分区）；确认框先关面板。
4. **P0-2 + P2-1/2/3 身份与文案**（~2h）：行结构改“会话名 + 角色名·预览”、自动命名、时间统一、英文复数、去 Markdown。
5. **P1-4 滚动跟随**（~1h）：IME 感知 + 增量跟随。
6. **P1-5/P1-6 a11y 与列表化**（~1.5h）：箭头 40dp、拆合并语义、面板 `LazyColumn`、当前项报读。

## 需要真机复核的项（本轮无法确认）

- P1-4 的 IME 遮挡程度（推断依据是 LazyColumn 的偏移保持语义，建议用 `adb` 在长会话里点输入框实拍）；
- 对话框叠在 `ModalBottomSheet` 之上的返回键层数与触摸穿透（截图已证明视觉分层，交互层数需实测）；
- 1.3×/2× 字号下预览框四按钮行是否裁切、`heightIn(420.dp)` + 大字号是否顶出屏幕；
- `mergeDescendants` 下两个箭头的 TalkBack 实际可聚焦数量。

