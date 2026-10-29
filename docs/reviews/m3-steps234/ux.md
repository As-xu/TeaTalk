# M3 步骤 2/3/4 交互逻辑审阅（UX Architect）

> 审阅对象：commit `436b771`（0.9.0）· 焦点：①Prompt 面板 ②卡高级编辑 ③ST 预设导入 ④预设与面板耦合
> 依据：`docs/10-M3-进度与验收.md` §10、`docs/design/tokens.md`、`docs/i18n.md` §2；截图取自 `docs/screenshots/m3-steps234/`
> 审阅方式：读代码（含 `git show` 实际改动）+ 逐张核对提交自带的 3 张截图 + 按 i18n/tokens 规则比对

## 结论

三处「主操作要么没落库、要么点不到、要么指错对象」的问题必须先修，否则本轮三个功能在真机上都不成立：

1. **卡高级字段是假保存**——`CardEdit` 新增的 4 个字段在 `updateCard` 里根本没有被写入 `cardJson`，保存后立刻丢失，用户却收到「已保存」。
2. **Prompt 面板的「复制」落在窗口外**——外层 `Column` 不受高度约束、内层固定 `360dp`，截图 01 底部即被窗口裁切，面板唯一产出（复制 JSON）不可达。
3. **快照是全局单例、不绑会话**——切到另一个会话后「查看本次请求」显示的是上一个会话的请求，空态文案还写「本次会话」。

P1 集中在：面板信息密度（6144 字符全量内联、消息只有长度没有内容）、编辑弹层的字段可达性与校验缺口、预设导入的「无预览 / 无确认 / 失败文案一刀切」，以及预设生效后**在 App 内无法验证**（面板不含采样参数，只能看 logcat）。

---

## P0（阻塞验收，本轮必修）

| # | 界面位置 | 用户影响 | 具体改法 |
| --- | --- | --- | --- |
| A1 | `CharacterRepository.kt:271-297`（`updateCard`）+ `EditCardDialog.kt:99-104` + `CharactersViewModel.kt:138-143` | 编辑弹层里填 System prompt / Post-history / Example dialogue / Alternate greetings，点 Save 后 snackbar 说「已保存」，但 `updated.put(...)` 只写了 name/description/personality/scenario/first_mes/creator_notes/tags（275-283 行），4 个新字段被丢弃。重开弹层值是空的、`ChatEngine.systemPrompt()` 永远读不到用户编辑（`ChatEngine.kt:277-291` 只读导入卡里原有的值）。**整个步骤 3 在真机上不成立，且是静默数据丢失** | 在 `275-283` 的 `apply {}` 里补：`put("system_prompt", JsonPrimitive(edit.systemPrompt))`、`put("post_history_instructions", ...)`、`put("mes_example", ...)`、`put("alternate_greetings", JsonArray(edit.alternateGreetings.map(::JsonPrimitive)))`（空值时写空串/空数组，不要删键，保证「清空即清空」）。补一条 round-trip 单测：`updateCard` 后重新 `CardViewParser.parse(cardJson)` 断言 4 字段相等（当前 `SamplingPresetTest` 之外无任何覆盖）。顺带核对 `docs/10 §10.2`「写回 data.*」的表述与实现一致后再提交 |
| A2 | `ChatScreen.kt:1309-1398`（`PromptSheet`） | 弹层内容 = 标题 + provider 行 + hint + `heightIn(max=360.dp).verticalScroll` 内层 + 底部按钮 Row；外层 `Column` 无高度约束，`ModalBottomSheet` 默认半展开。截图 `01-prompt-panel.png` 里正文一直延伸到屏幕底边被裁切，`Close/Copy` 行不在可视区。**面板唯一可交付物（复制 JSON）拿不到**，与 0.8.2 已修过的「横屏弹层按钮不可达」是同一个坑（`docs/10 §9.3 批次 C`） | 改成与世界书弹层一致的三段式：头部（标题/provider/hint）固定 → 中间 `Modifier.weight(1f, false).verticalScroll(...)`（去掉 `heightIn(max=360.dp)`）→ 底部 `Row` 固定；`ModalBottomSheet(state = rememberModalBottomSheetState(skipPartiallyExpanded = true))`。验收：2340×1080 横屏 + 1.3× 字体下 Close/Copy 同屏可见 |
| A3 | `PromptSnapshot.kt:49-71`（`PromptDebugStore`）+ `ChatScreen.kt:544-545` | `PromptDebugStore` 是进程级单例，`publish` 不带 `conversationId`，聊天页直接 `collectAsState()` 全量订阅。从会话 A 切到会话 B 后打开菜单，看到的仍是 A 的 system prompt / 消息 —— 用户会据此判断「B 的卡设定没生效 / 世界书没注入」而做出错误修改；空态文案 `prompt_debug_empty`「本次会话还没有发出过请求」在这个实现下是错误陈述 | 快照按会话存：`MutableStateFlow<Map<Long, PromptSnapshot>>` + `snapshotOf(conversationId)`，`publish(conversationId, ...)` 由 `ChatEngine` 传入；UI 侧 `val snapshot = PromptDebugStore.snapshotOf(conversationId).collectAsState()`，空态文案改「这个会话还没有发出过请求」。顺带把 `collectAsState` 从 `ChatScreen` 顶层移到 `PromptSheet` 内（现在每次发请求都会重组整个聊天页） |

## P1（本轮应修，或明确写进「已知限制」并给替代手段）

| # | 界面位置 | 用户影响 | 具体改法 |
| --- | --- | --- | --- |
| B1 | `ChatScreen.kt:1349-1355`（system prompt 段） | `snapshot.system`（实测 6144 字符）整段内联，用户要滑几十屏才能看到下面的「世界书 / 消息」段，而这两段才是判断注入是否生效的关键。`docs/10 §10.1` 承诺的「System prompt N chars 卡片可展开」并未实现 | 默认折叠：`SectionTitle` 行尾放展开图标，展开后限 `heightIn(max = 240.dp)` 独立滚动；或顶部加分段 chips「系统 / 世界书 / 消息」点击 `animateScrollTo` 定位（同一套锚点也能解决编辑弹层的可达性问题） |
| B2 | `ChatScreen.kt:1371-1381`（消息段） | 每条消息只渲染 `n. role · 字符数`，**内容完全看不到**，`PromptMessageSnapshot` 也没有 position 字段。用户在调试「at-depth 插入位置对不对 / 历史里有没有重复问候」时无任何依据；`docs/10 §10.1` 写的「逐条消息折叠行」「position 为 rolePrompt|charBefore|…」都不存在 | 每条消息做成可展开行（默认折叠，点击展开正文，长文本限高滚动）；`PromptMessageSnapshot` 增 `position`（`rolePrompt/charBefore/charAfter/atDepth/exampleAbove/exampleBelow`），由 `PromptDebugStore.publish` 的调用点传入 —— 现在 `ChatEngine.kt:185-192` 只传 `messages.drop(1)`，丢掉了注入来源信息 |
| B3 | `ChatScreen.kt:1325-1330`（空态分支） | 空态只渲染标题 + 一句话，`Close/Copy` 的 `Row` 在 `else` 分支里（1383-1394），**空态没有关闭按钮**；也没有「去发一条消息」的引导。手势导航用户只能靠下拉/返回键退出 | 把底部按钮行提到 `if/else` 之外（空态只保留 Close，Copy 置灰），或空态加一个「回到底部输入」的 CTA |
| B4 | `EditCardDialog.kt:62-83` | 11 个字段塞进同一个 `heightIn(max=460.dp).verticalScroll`，无分组、无「高级」标记、无滚动指示；多行 `BasicTextField` 会吞掉竖向拖动（`docs/10 §10.4 ①` 已自认），必须从 Name/Tags 这种单行字段起拖才能滚到新增的 4 个字段。截图 `03-edit-advanced.png` 恰好停在「System prompt」标签处、4 个新输入框一个都没拍到 —— 这本身就是「走不到」的证据 | ①弹层体改 `LazyColumn`（字段成为独立 item，滚动与输入不再抢手势）；②顶部加分段 chips「基础信息 / 高级（system prompt 等）」，点 chip `LazyListState.animateScrollToItem` 直达；③「高级」段加一句话说明「这些字段会追加到系统提示词」。若时间不够，最低成本方案：把 4 个高级字段做成折叠分组（默认收起，标题行「高级设定 · 4 项」），收起时弹层只剩 7 个字段，不需要滑动即可保存 |
| B5 | `EditCardDialog.kt:85-111`（Save/Cancel） | Save 常开、不判脏：什么都没改也弹「已保存」；Name 清空后 `name.trim().ifBlank { character.name }`（89 行）静默回填旧名，用户以为改名成功；点外部/返回键直接丢弃 11 个字段的草稿且无任何确认（`docs/10` 未列为限制）。与世界书弹层 0.8.1 定下的「Save 仅 dirty 可用 + 显式 Cancel」不一致 | ①`dirty` 用 `edit == 原值` 比较，未变则 Save 置灰；②Name 为空时 Save 置灰 + 字段下 `supportingText` 报「名称不能为空」（去掉静默回填）；③有改动时 `onDismissRequest` 走二次确认「放弃修改？」（或改用不可点外部关闭的 `ModalBottomSheet`） |
| B6 | `EditCardDialog.kt:99-104`（alternate greetings 往返） + `CharacterRepository.kt:320-330`（`ensureGreeting`） | `card.alternateGreetings.joinToString("\n")` → 存回时 `split('\n')`，**任何含换行的开场白都会被拆成多条**（ST 卡里多行开场白很常见）→ 静默改数据；而且该字段目前无任何消费方（`ensureGreeting` 只用 `firstMes`），用户改完看不到任何效果 | 改为「一行一条、每条是多行文本框」的重复行编辑器（列表 + 删除/新增），序列化仍写 `alternate_greetings` 数组；同时接上消费路径（新建会话时若有多条开场白则让用户选），或暂时隐藏该字段并在 `docs/10` 记为「数据层已通、UI 入口待做」——不要留一个「可编辑但不生效」的输入框 |
| B7 | `ChatEngine.kt:277-291`（`systemPrompt` 追加段） | `post_history_instructions` 被塞进 system prompt 末尾（注释里自己也写了「位置语义最接近」），但界面标签叫「历史后指令（post-history instructions）」，承诺的是**历史之后、回复之前**；`mes_example` 更是把示例对话整块塞进 system prompt，而 ST 是把它当历史里的示例轮次。结果：用户按标签调字段，实际位置相差一整段历史；Prompt 面板会把它们显示在 system 里，用户看到后无法判断是对是错 | 二选一，别只留半截：①实现真位置——`mes_example` 按 `{{user}}/{{char}}` 切分成 history 消息（position `exampleAbove`），`post_history_instructions` 作为最后一条 `system` 插在历史之后（`WorldInfoInjector.injectHistory` 已有 at-depth 能力，可复用 `depth=0`）；②若这轮不做，把界面文案改成「补充指示（追加到系统提示词末尾）」「示例对话（追加到系统提示词）」，并在 `docs/10 §10.4` 明确「不是 ST 的历史位置语义」 |
| B8 | `ProfileScreen.kt:84-98`（SAF 导入） | `importPreset.launch(arrayOf("application/json", "text/*"))`：不少文件管理器 / 云盘把 `.json` 报成 `application/octet-stream`，文件在选单里**直接置灰选不到**，用户只会得到「无法识别该预设文件」这种牛头不对马嘴的反馈 | mime 改 `arrayOf("application/json", "text/plain", "application/octet-stream", "text/*")`，或直接 `arrayOf("*/*")` + 导入前判扩展名/内容首字符 |
| B9 | `ProfileScreen.kt:84-98`、`186-231`（覆盖 / 清除 / 反馈） | 已有预设时再导入 = 静默覆盖，Toast 只说「已导入预设」，没有新旧对比、没有确认、没有撤销；「清除预设」放在 `dismissButton` 里、点一下立即删除（`SamplingPresetStore.clear`），同样只有 Toast —— 用户丢掉的是从外部文件导进来、本机再无副本的配置 | ①导入成功后不直接 Toast，而是弹一次确认面板：「将覆盖当前预设 / 新：temp 0.65 · top_p 0.9 · … → 旧：temp 1.0 · …」+「未识别的键 3 个」，确认才 `write`；②清除改二次确认，并提供 5 秒撤销 snackbar（保留旧 `SamplingPreset` 在内存，撤销时写回）；③把 `preset_imported/cleared` 的 Toast 换成 Me 页 snackbar（全 App 其余反馈都走 snackbar，见 `ChatScreen.kt:948`） |
| B10 | `SamplingPreset.kt:80-94`（`parseStPreset` 返回 null） | 三种完全不同的情况共用一句「无法识别该预设文件」：读不到文件（IO/uri 失效）、JSON 坏、**是合法 ST 预设但只有 `top_k`/`repetition_penalty` 等未映射字段**（`isEmpty` → `takeUnless` → null）。第三种用户最困惑：文件明明是对的，App 说认不出 | `parseStPreset` 改为返回 `sealed interface PresetParseResult { Ok(preset, ignoredKeys), NoSamplingFields(foundKeys), InvalidJson, Unreadable }`；文案分别给「没有可用的采样字段（发现 top_k、repetition_penalty，暂不支持）」「预设文件不是合法 JSON」「无法读取该文件」。同时删掉 `82-84` 行未被使用的局部 `fun str(...)` |
| B11 | `PromptSnapshot.kt:14-22` + `ChatScreen.kt:1332-1341`（面板与预设的耦合） | 面板标题是「本次请求」，但**不含任何采样参数**（temperature/top_p/max_tokens/freq/pres）、也不显示 `takenAt`。所以：导入预设后，用户打开面板看到的内容和导入前一模一样 → 会误判「预设没生效」；反过来，面板里显示的 6144 字符 system prompt 也可能是预设导入之前那次请求的（无时间戳，无法区分）。当前唯一验证手段是 `adb logcat` 里的 `Prompt sampling.preset`（`docs/10 §10.4` 就是这么验的） | `PromptSnapshot` 增 `sampling: SamplingSnapshot(temperature, topP, maxTokens, freqPen, presPen, source: PRESET|PROVIDER)`，`ChatEngine.kt:185-192` 把 `preset ?: provider.temperature` 的**最终生效值**一起 publish；面板在 provider 行下加一行「采样 · temp 0.65 · top_p 0.9 · max 512 · 预设」；再补「HH:mm:ss 快照」时间戳与「该会话暂无新请求」提示。这样 ③④ 的问题一次解决，且不必让用户碰 logcat |
| B12 | `SamplingPreset.kt:86-92`（无范围校验） | 预设里的 `temp=3`、`top_p=-1`、`max_tokens=0`（ST 预设里手改过／别的模型专用）会被原样写进请求体，直到发消息时才由服务端返回 400（表现为「聊天突然不能用了」），用户无法关联到预设 | 导入时校验并 clamp 到安全区间（temp/top_p 0~2、penalties -2~2、maxTokens 1~模型上限或 ≥16），超出时在确认面板标黄「已修正：temp 3 → 2」；解析失败/缺失保持 null 不发送 |

## P2（打磨 / 一致性 / 证据口径）

| # | 界面位置 | 用户影响 | 具体改法 |
| --- | --- | --- | --- |
| C1 | `SamplingPreset.kt:96-105`（`summary`）、`ChatScreen.kt:1407`、`1365` | zh 界面里出现硬编码英文：「temp 0.65 · top_p 0.9 · max 512 · freq 0.2 · pres 0.1」直接当 Me 行副标题、「`$chars chars`」、「`${comment} · ${position} · ${chars}`」（position 还是 `before/after/antop/atdepth` 原始枚举）。违反 `docs/i18n.md §2`「所有 UI 文案必须走资源，Kotlin 里不再写中文」（英文同理） | 采样摘要走 `values`/`values-en` 的 `%1$s` 模板或资源数组；`chars` 用 `prompt_debug_chars` 词条；position 映射到 `prompt_position_before/after/antop/...` 词条（`WorldEntrySnapshot.of` 已经做了映射，只是映射结果是英文裸串，把它换成枚举再在 UI 层 `stringResource`） |
| C2 | `ProfileScreen.kt:156-162` + `strings.xml` `profile_preset_sub` | `SettingRow` 第三个参数传 `null`，导致新增的 `profile_preset_sub`「抽样参数：temperature / top_p 等」**从未被使用**；副标题槽位 `maxLines=1 / Ellipsis`（`290-321`），5 个参数在 1.3× 字体下会被截成「temp 0.65 · top_p 0.9 · max 5…」，用户看不出 freq/pres 是否导入 | 要么用它当固定说明文案、摘要挪到 `trailing`（需要 `SettingRow` 支持独立 trailing 槽），要么摘要只显示前 2 项 + 「+3」。顺手删掉未使用词条 |
| C3 | `ChatScreen.kt:1386-1393`（复制反馈） | 用 `Toast` 而不是页面已有的 `SnackbarHostState`（`ChatScreen.kt:536/948`）；Android 13+ 系统本身还会弹一次剪贴板提示，两层反馈；按钮文案只有「复制」，没说复制的是 JSON；`clipboard.setText` 无失败分支（大快照/剪贴板占用时静默失败仍提示「已复制」） | 统一走 snackbar（「已复制为 JSON · 可粘贴到任意文本编辑器」）；按钮改「复制 JSON」；`setText` 后用 try/catch 兜底提示失败 |
| C4 | `ChatScreen.kt:1413-1445`（`buildPromptJson`） | 手写字符串拼 JSON：只转义 `\ " \n \r \t`，**其余控制字符（`\u0000`–`\u001F`）不转义 → 输出可能是非法 JSON**，用户粘到分析工具里解析失败；且缺 `takenAt`、消息无序号/位置、世界书条目无 `content`（只有 comment/position/chars，调试注入效果时最关键的正文本体没有） | 改 `kotlinx.serialization`：`buildJsonObject { put("takenAt", snapshot.takenAt); ... }` 直接 `toString()`（转义交给库）；补字段并加一条快照 → JSON → `Json.parseToJsonElement` 的往返单测（当前 0 测试） |
| C5 | `PromptSnapshot.kt:21`（`takenAt`） | 采集了但 UI 从不展示（见 B11 的耦合问题）：面板无法回答「这个快照是几点的 / 是不是我要看的那次」 | 与 B11 一起做：标题行右侧显示 `HH:mm:ss`，或「3 分钟前」 |
| C6 | `ChatScreen.kt:544-545` | `collectAsState()` 放在 `ChatScreen` 顶层，每发一次消息（含流式期间的重发）都会重组整个聊天页与消息列表，而面板 99% 时间是关闭的 | 按 A3 移入 `PromptSheet`，或用 `derivedStateOf` 在 sheet 可见时才读取 |
| C7 | `docs/10 §10.1 / §10.2` + `docs/screenshots/m3-steps234/` | 文档与实现不符，后续按文档验收会踩空：①`PromptSnapshot(providerName, model, systemPrompt, messages, maxTokens, trigger, worldBookEntries)` / `PromptMessageSnapshot(role, content, position)` 与实际（`system`、无 `maxTokens`/`trigger`/`position`）不同；②「System prompt N chars 卡片可展开」「逐条消息折叠行」未实现；③§10.2「写回 data.system_prompt…」在 A1 修好前不成立；④截图 `02-preset-row.png` 的 About 行显示 `v0.8.2`，与本 commit 的 0.9.0 不符；`03-edit-advanced.png` 只拍到「System prompt」标签、4 个新字段输入框均不可见，**不能作为步骤 3 的证据** | A1–A3 修完后重拍 3 张（面板含采样段与时间戳、编辑弹层「高级」段可见可编辑并回读成功、Me 行 + 确认面板），文档按实现回填（`§10.1` 字段清单、`§10.4` 已知限制补「多行字段抢手势」「预设无撤销」「消息正文默认折叠」） |
| C8 | `ChatEngine.kt:194-203`（`sampling.preset` 日志） | `"topP" to (preset?.topP ?: -1f)`、`"maxTokens" to (preset?.maxTokens ?: -1)` 用哨兵值，与「temperature 记最终生效值」的口径不一致，日志看起来像真的发了 maxTokens=-1 | 缺值不写该键（或记字符串 `"none"`），与 `worldbook.inject` 五项口径一致；顺手把 `302-305` 行 `MAX_SYSTEM_CHARS/MAX_WORLD_BOOK_CHARS` 的缩进修正（现在顶格在 `companion object` 里） |
| C9 | `ChatScreen.kt:1309`（Panel 只读性） | 「只读」做到了（无输入入口），但正文用普通 `Text`：用户不能选中片段复制某一小段 system prompt，只能整包 JSON。手机端把 10KB JSON 粘到聊天框里再找某段很痛苦 | `SelectionContainer { ... }` 包住正文（仍无编辑能力），或给「系统提示 / 世界书 / 消息」各加一个段级复制 |

---

## 复现与验收清单（建议按此顺序）

1. **A1**：改卡 → 填 System prompt `TEST-PHI` → Save → 重开编辑弹层看是否还是 `TEST-PHI`；再发消息读 `adb shell dumpsys` 或面板里的 system prompt 是否含 `TEST-PHI`。（当前必然失败）
2. **A2**：打开 `⋯ → 查看本次请求`，**不滚动**找 Close/Copy；再横屏重试一次。
3. **A3**：会话 A 发一条 → 切到会话 B（B 无任何消息）→ 打开面板，应显示空态而不是 A 的 system prompt。
4. **B4**：在编辑弹层里从 Description 中间开始向上拖 —— 观察是否滚到 Name（预期滚不动），再从 Name 起拖 —— 观察能否到达 Alternate greetings。
5. **B9/B11**：先导入 `temp 0.65` 预设 → 发消息 → 打开面板：当前看不到任何采样参数；再导入一个 `temp 1.2` → 旧面板内容不变（无时间戳、无提示），这正是「改预设后旧快照误导」的具体表现。
