# Software Architect — M2 审阅（对象 f30d9b6 / 0.7.0）

已读 `docs/09-M2-进度与验收.md`、`docs/reviews/m2/SUMMARY.md`、`data/`（TeaTalkDatabase/Entities/CharacterDao/ConversationDao/CharacterRepository/ChatEngine/ChatClient/MessageVariants/AppContainer）与 `ui/`（ChatScreen/ChatListScreen/HomeScreen/CharacterDetailScreen/Markdown）的关键路径，并用 `git show` 回溯了 v1→v7 各版本的 entity/schema 声明。**未修改任何代码。**

---

# M2 代码审阅结论（f30d9b6 / 0.7.0）

**总评：功能骨架与迁移链的“常态路径”是对的，但 M2 新增的“可变引用”（变体列表、会话、Provider）都缺少一个明确的权威标识，导致竞态与归属问题集中在写路径。** 计 P0×2、P1×8、P2×12。

三个贯穿性问题：
1. **回复落点靠“最后一条是不是 assistant”猜测**，而非由发送动作携带的消息 id 决定 → 一切并发/删除/停止场景都在这个假设上崩塌。
2. **组合写没有事务边界**（跨 DAO 的 create/delete 尤其），迁移链又没有任何自动化验证，一旦中断就留下迁移会二次惩罚的脏数据。
3. **取消/收尾没有串行化**：`job` 字段既是“单飞锁”又是“取消句柄”，两职合一导致 stop 与 send 互相穿透。

---

## P0

### P0-1 `stop()` 与 `Completed` 双写同一个变体槽位：完整回复被截断版覆盖（真丢数据）
`app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt:262-278`（stop）+ `:326-346`（collectStream）+ `app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt:314-334`

触发是一个完全自然的动作：正文刚渲染完，用户再点一次 ⏹。此时 `Completed` 分支的 `appendReply(reply, STATUS_COMPLETE)`（`ChatScreen.kt:334`）已进入 suspend 的 `dao.lastMessage` 却未落库；`stop()` 取 `partial = _streamText.value.trim()`（`:264`，此刻等于全文）后 `current.cancel()`（`:267`）——取消点在 `appendReply` 内部，**完整的 UPDATE/INSERT 被取消**；随后 stop 自己的 `viewModelScope.launch{ ... appendReply(partial, STATUS_STOPPED) }`（`:269-277`）读到同一个 `last`，走 `MessageVariants.append` 追加一版。结果两种都坏：

- 取消落在写之前 → 库里只有**被标记为 stopped 的全文副本**，状态谎报，`content` 镜像被改；
- 取消落在写之后 → **两条内容相同的变体**，用户看到 “Version 2 of 2 · Stopped”，且两条都进下一次 prompt。

根因是 `appendAssistantReply` 的读-改-写（`CharacterRepository.kt:314`→`:324`）不在事务里，且调用方谁也没“认领”这次写。
**修复建议**：①`appendAssistantReply` 改为 `@Transaction`（在 DAO 上做单方法 RMW：`INSERT ... ON CONFLICT` 不可用则用 `@Transaction` 包裹 lastMessage+updateVariants，并返回受影响行数）；②`collectStream` 在 Completed 分支先置 `job = null` / 打 `completed` 标记，`stop()` 入口检查该标记后直接 return（不要靠 `generation` 兜底，`generation` 只挡 UI 回调不挡写库）；③给 `stop()` 的收尾加“只允许一次”的守卫（`if (!committed.compareAndSet(false,true)) return`）。

### P0-2 `job = null` 早于 `join()`：单飞保护失效，陈旧的 stopped 回复会插到新 user 消息之后
`app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt:265-277`、`:188`

```kotlin
generation++            // :265
job = null              // :266  ← 在这里放开了 send() 的闸门
current.cancel()        // :267
viewModelScope.launch { runCatching { current.join() }; if (partial.isNotEmpty()) appendReply(partial, STATUS_STOPPED); ... }
```
`send()` 的闸门是 `if (trimmed.isEmpty() || job != null) return`（`:188`）。`job` 在 join 之前就被置空，于是 stop 之后立刻 send 是允许的：新的一轮先插入 user 消息，而旧协程的 `appendReply(partial, STOPPED)` 在 join 完成后才执行，此时 `lastMessage` 已经是新的 user 行，`role != assistant` → **insert 一条新的 assistant 行**。表现为：停止的半截回复出现在新问题下面（顺序倒错），并且此时 `pendingProvider`（`:172`，非代次作用域）已被新一轮覆盖，写成错误的 Provider 归属。
**修复建议**：`job = null` 只在 `finally`/`finishIfCurrent` 里做；stop 的收尾协程自己持有 `job` 的引用并把它作为“仍在飞行”的栅栏（`private val inFlight = AtomicBoolean`），或改造为单一 Actor：`Channel<Intent>` + 串行消费 `Send/Stop/Retry`，从结构上消除这类交错。同时把 `pendingProvider` 改为随代次传递的参数（`appendReply(text, status, provider)`），不要用跨轮共享字段。

---

## P1

### P1-1 回复与用户消息没有权威关联：`sendUserMessage` 丢弃 id，追加靠“最后一条是 assistant”
`app/src/main/java/com/teatalk/xs/data/ChatEngine.kt:32-40`（`characters.addMessage(...)` 的返回值被丢掉，只返回 `Boolean`）、`app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt:202`、`app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt:314-315`

`appendAssistantReply` 用 `dao.lastMessage(conversationId)` 决定“追加到上一版还是新起一行”。这是对整个 M2 语义（多版本=同一条 assistant 消息的多个候选）的隐式全局约定，任何插入时序变化都会让它指向错误的宿主。P0-1/P0-2 都是它的下游症状。
**修复建议**：`sendUserMessage` 返回 `Long`（user 消息 id），`streamReply(conversationId, replyToUserMessageId)` 传递，`appendAssistantReply(..., targetAssistantId)` 显式指定宿主（无则按 `replyToUserMessageId` 之后的 assistant 行新建）；`messages` 增加 `replyTo` 列（M3 迁移）能一劳永逸，但即便不加列，让目标 id 在调用链里显式传递就能消灭这一类 bug。

### P1-2 跨 DAO 组合写无事务：schema 层没有外键，中断即脏数据
`app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt:105-109`（`deleteMessages` + `deleteById`）、`:90-98`（`conversations.insert` + `insertGreetingIfEmpty`）、`:181-188`（三写 + 删头像文件）

`MessageEntity`（`app/src/main/java/com/teatalk/xs/data/db/Entities.kt:41`）对 `conversations` 只有逻辑关联、无 `@ForeignKey`、无级联，因此“删会话”的中断会留下**所有消息 characterId 有效但 conversationId 指向已删会话的孤儿行**。这些孤儿对 UI 不可见（列表以会话为单位），却会占空间并在下一次迁移里变成 P1-6 的崩溃源。
**修复建议**：把这类多表删除下沉到 DAO 的 `@Transaction` 方法（`@Query` 组合或抽象 DAO 上的默认方法），Repository 只调一个入口；`importCard` 的“写库+落盘头像”用“先写文件再写库、失败删文件、库写失败删文件”的补偿顺序（现有 `:144-150` 已是这个路子，但 delete 与 create 没有）。是否加 `@ForeignKey(onDelete = CASCADE)` 需要权衡：加外键需要 `MIGRATION_x_y` 重建 messages 表（12 步 TableInfo 重建），M2 不做也可以，但**必须**让 DAO 层事务兜住。

### P1-3 生成中切换/删除当前会话：在飞回复被静默丢弃，不落半截
`app/src/main/java/com/teatalk/xs/ui/HomeScreen.kt:226-231`（`popUpTo(Routes.CHAT){inclusive=true}` → 旧 BackStackEntry 出栈 → 旧 VM `onCleared` → `viewModelScope` 取消）、`app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt:308-314`（`deleteConversation` 不 cancel / 不 await `job`）

`ChatClient` 的取消是干净的（`app/src/main/java/com/teatalk/xs/data/net/ChatClient.kt:112` 的 `invokeOnCompletion { call.cancel() }` + `:222-224` 的 `finally`），所以不会泄漏连接；但**用户已看到的流式文本会随 VM 一起消失**，而按 ⏹ 却会保存半截 —— 同一份文本，两种手势两种结果，且切换会话比按停止更常见。
**修复建议**：`ChatViewModel.onCleared()` 里无法 suspend，正确做法是把“流式收尾”移出 VM：`ChatEngine` 暴露 `streamReply` 的“消费即落库”语义（或加一个 `runCatching { replyFlow.collect }` 的 `NonCancellable` 收尾函数），VM 只订阅展示。若不想动引擎，至少在 `HomeScreen` 切会话前调用一次 `viewModel.persistPartial()`（VM 暴露 suspend 方法，`viewModelScope` 之外用 `GlobalScope`? 不推荐）—— 结论：**这是“落库责任错放在 UI 层”的结构性问题**，与 P1-1 同一处方。

### P1-4 整张角色卡 JSON 在主线程解析（每次发送/重试）
`app/src/main/java/com/teatalk/xs/data/ChatEngine.kt:95`（`CardViewParser.parse(character.cardJson)` 位于 `flow {}` 体内，且 `streamReply` 没有 `flowOn`）

`flow {}` 在**收集方上下文**执行，即 `viewModelScope`（Main.immediate）。`characters.conversation()/recentMessages()` 内部有 `withContext(IO)`，但 `parse` 是纯 CPU 的同步调用，直接占主线程。同一解析在详情页已经显式加了 `flowOn(Dispatchers.Default)`（`app/src/main/java/com/teatalk/xs/ui/character/CharacterDetailScreen.kt:80-88`，注释写明“大卡 JSON 解析别占用主线程”）—— 说明团队已知代价，只是聊天路径漏了。
**修复建议**：`streamReply` 返回 `.flowOn(Dispatchers.Default)`，或把 `CardViewParser.parse` 包进 `withContext(Dispatchers.Default)`；更彻底的是在 `CharacterEntity` 写入时就缓存解析结果（或存 `worldBookJson` 展开后的 system prompt 片段），避免每次发送重复解析。

### P1-5 迁移链可回滚性与可验证性双缺：`exportSchema = false` 且无降级路径
`app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt:18`（`exportSchema = false`）、`app/src/main/java/com/teatalk/xs/data/AppContainer.kt:45-61`（`addMigrations(1→2 ... 6→7)`，无 `fallbackToDestructiveMigration*`）、`app/build.gradle.kts`（无 `androidTestImplementation("androidx.room:room-testing")`，无 `ksp { arg("room.schemaLocation", ...) }`）

两点：
- **回滚**：装回 0.6.x 的 APK 会命中 `SQLiteException: Can't downgrade database from version 7 to 6`，表现为启动即崩、清数据才能用（对灰度/QA 回滚是硬伤）。取舍明确：`fallbackToDestructiveMigrationOnDowngrade()` 换来可回滚、代价是降级丢历史。对 M2 阶段的取舍我倾向于**接受丢失**（降级本身是异常路径），但必须显式决策并写进 ADR，而不是靠“没人会降级”默认。
- **可验证**：`exportSchema = false` + 无 schema JSON ⇒ `MigrationTestHelper` 无法工作，所以 B6（无迁移测试）在当前配置下**不是“忘了写”，而是“写不了”**。这是本轮最值得投入的一项：打开 `exportSchema`，把 `app/schemas/7.json` 提交进库，补 `MigrationTestHelper` 覆盖 6→7 的“有/无消息/多角色/孤儿”四类输入。
**修复建议**：`exportSchema = true` + ksp 参数；`app/src/androidTest/.../MigrationTest.kt` 覆盖 `1→7` 全链（用 `runMigrationsAndValidate` 一次跑通，比单跳更省事）；`AppContainer` 加 `fallbackToDestructiveMigrationOnDowngrade()`。

### P1-6 `MIGRATION_6_7` 的回填子查询可能产生 NULL ⇒ NOT NULL 约束中止迁移，升级即崩且可复现
`app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt:156-164`

```sql
UPDATE messages SET conversationId = (
    SELECT cv.id FROM conversations cv
    WHERE cv.characterId = messages.characterId ORDER BY cv.id ASC LIMIT 1
) WHERE conversationId = 0
```
`WHERE conversationId = 0` 只是过滤范围，不保证子查询有结果。若存在 `characterId` 已无对应 `characters` 行的孤儿消息（P1-2 的中断、旧版本 bug、用户侧 SQL 工具），子查询返回 `NULL`，写入 `INTEGER NOT NULL` 列 → `NOT NULL constraint failed: messages.conversationId`，**整个迁移事务回滚，Room 抛异常，应用每次启动都崩**（Room 迁移失败不会自动跳过，且这里没有 destructive fallback）。概率低但后果不可恢复。
**修复建议**：`COALESCE((SELECT ...), 0)` 使其退化回不可见行 + 迁移末尾 `DELETE FROM messages WHERE conversationId = 0`（或先 `DELETE FROM messages WHERE characterId NOT IN (SELECT id FROM characters)`），并在同一个迁移里为每条语句间保持一致；迁移全程包在 `db.beginTransaction()/setTransactionSuccessful()` 里（Room 已在外层包事务，但显式清理更好读）。

### P1-7 首屏路由判定用 `countAll()>0`，而 v7 迁移给**每个角色**预建空会话 ⇒ 升级后落在空的「聊天」页
`app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt:67`（`hasAnyChat() = conversations.countAll() > 0`）、`app/src/main/java/com/teatalk/xs/ui/HomeScreen.kt:77-79`、`app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt:149-155`（每角色一条会话）、`app/src/main/java/com/teatalk/xs/ui/chats/ChatListScreen.kt:79`（`filter { it.lastContent != null }`）

三处对“有没有聊天”的定义不一致：迁移保证“有角色 ⇒ 有会话”，首屏判定“有会话 ⇒ 进聊天页”，列表却“有消息才算聊天”。因此**导入过卡但还没开始聊的用户**（M1 典型状态）升级后会看到一个空白的聊天列表，且这是首屏 —— 需要手动切到「角色」Tab 才能继续。这不是迁移 bug，是迁移语义泄漏到了产品判定。
**修复建议**：把判定下沉给 SQL：`SELECT EXISTS(SELECT 1 FROM conversations c WHERE EXISTS(SELECT 1 FROM messages m WHERE m.conversationId=c.id))`，一次查询既解决首屏也解决列表过滤（顺带干掉 P2-4 的 UI 层过滤）。

### P1-8 Provider 归属依赖两次独立 `resolve()`，可能把 A 的回复记成 B
`app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt:200`（`pendingProvider = engine.activeProvider()` 供落库用）+ `app/src/main/java/com/teatalk/xs/data/ChatEngine.kt:79`（`stream()` 内部再次 `providers.resolve()` 供实际调用）

两处读的是同一份 `prefs.activeProviderId`，但中间隔了若干 suspend 点（`sendUserMessage` 两次写库）；用户在设置页切 Provider、或另一路径触发 `resolve()` 变更时，**落库的 providerId/name/model 与实际发出请求的 Provider 可以不一致**，而这条记录一旦写入就是不可纠正的“历史事实”（UI 上还会拿它和当前 Provider 比对显示 “Generated with X (not the current provider)”，等于把错误放大给用户）。
**修复建议**：由 `ChatEngine` 自己回报实际使用的 Provider —— 在 `ChatEvent` 增加 `Started(provider: ActiveProvider)`（或 `streamReply` 返回 `Flow<ChatEvent>` + 一个 `providerUsed` 的 `StateFlow`），VM 只用它落库，删掉 `activeProvider()` 这个可被误用的旁路读取；`preflightError()` 同理应并入 `streamReply` 的首个事件（现在的 `preflightError()` + `resolve()` 是典型的 check-then-act 跨异步边界）。

---

## P2

**写路径/数据一致性**
- **P2-1 变体内容与变体状态是两个并行数组，靠位置对齐，且 `parse` 会 `filter { it.isNotEmpty() }` 丢空串** —— `app/src/main/java/com/teatalk/xs/data/MessageVariants.kt:23-28`、`:47-57`。任何一次写入空文本（`appendAssistantReply` 对空文本返回 -1 会挡住，但 `parse` 的过滤对历史数据生效）都会让 `variantStatusJson` 与 `variantsJson` 永久错位，之后 `parseStatuses` 用 `getOrNull(i) ?: complete` 静默掩盖。建议：改为单列 JSON 数组 `[{text,status,provider}]`（一条迁移），或至少在 `append` 中断言两数组等长并在不等长时整体重建 statuses（而非按位置补）。
- **P2-2 `deleteMessage` 不 touch 会话、不处理版本锚** —— `app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt:377-380`。删掉会话里最后一条消息后，会话从列表消失（`ChatListScreen.kt:79` 的过滤）但聊天页仍停留在其中；`_restoreText`/`lastAssistantId` 也可能指向已删 id。
- **P2-3 `ensureConversation` 无幂等保证** —— `app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt:74-80` + `app/src/main/java/com/teatalk/xs/ui/HomeScreen.kt:207-212`（`scope.launch` 无重入保护）。详情页连点「开始聊天」→ 两个协程都看到 `latest()==null` → 建两条会话，其中一条永远无消息、永远不可见（但会被 P1-7 的 `countAll` 计入）。修法：`conversations` 加唯一约束不行（允许同角色多会话是 M2 需求），改用 `Mutex` 或在 `createConversationInternal` 内二次校验 `latest()`，并对“空会话”做复用而非新建。
- **P2-4 `observeChats` 无 WHERE、同一相关子查询写了两遍** —— `app/src/main/java/com/teatalk/xs/data/db/ConversationDao.kt:48-58`。`lastContent`/`lastAt` 各扫一次 messages（走 `index_messages_conversationId` 尚可，但每条会话两次），且空会话由 UI 过滤（`ChatListScreen.kt:79`）。建议：`WHERE EXISTS(...)` + 用 `LEFT JOIN messages` 一次算出两列，语义与性能一起收进 SQL。

**UI / 渲染**
- **P2-5 `MarkdownText` 每次重组全量重解析** —— `app/src/main/java/com/teatalk/xs/ui/components/Markdown.kt:290`（`val blocks = Markdown.parse(text)` 无 `remember(text)`），配 `ChatScreen.kt:328` 的 `_streamText.value += event.text`（O(n²) 拼接），流式期间每 60ms 把整段文本重新 parse + 重跑 `annotatedInline` 并重建全部 `AnnotatedString`。5KB 内没掉帧（文档口径）但长回复会。建议：`remember(text) { Markdown.parse(text) }`，流式期用 `StringBuilder`，并考虑把 blocks 拆成 `LazyColumn` 项以获得局部失效。
- **P2-6 Completed 已落库但 `_streaming` 仍为 true → 双气泡闪一帧** —— `ChatScreen.kt:334` 的 `appendReply` 之后 `:336` 才可能 notice，清标志在 `finally`（`:206`→`:351-356`）。`messages` 已含新行 + 流式占位 item（`:496-500`）同时存在。建议：Completed 分支 `appendReply` 成功后立即 `_streaming.value = false; _streamText.value = ""`。
- **P2-7 composable 内直读 `viewModel.conversation.value`** —— `ChatScreen.kt:671`（`currentId`）、`:722`（`isCurrent`）。绕过 `collectAsState` 的重组订阅，切换/删除瞬间可能拿到上一帧的值（把“删除当前会话”判成非当前 → 删完停在空页面）。建议改用已 collect 的 `conversation?.id`。
- **P2-8 `ConversationSheet` 用 `conversations.forEach` 而非 `LazyColumn`** —— `ChatScreen.kt:806-877`。会话数量无上限（每角色可建多条），一次全量组合。建议换 `LazyColumn` + `key`。

**分层 / 可维护性**
- **P2-9 迁移声明与注释错位** —— `app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt:118-122`（v5→v6 的 KDoc）后面紧跟 `:123-130`（v6→v7 KDoc）与 `:131` 的 `MIGRATION_6_7`，而 `val MIGRATION_5_6` 实际在 `:169-177` 且裸奔无注释。阅读者会以为 v5→v6 的说明属于 6→7。这是下一次迁移最容易踩的坑。建议把 `MIGRATION_5_6` 上移并恢复其 KDoc。
- **P2-10 列默认值声明风格不一致** —— `app/src/main/java/com/teatalk/xs/data/db/Entities.kt:58` 只有 `variantStatusJson` 带 `@ColumnInfo(defaultValue = "[]")`，而 `variantsJson`/`status`/`conversationId`（`:45`/`:66`/`:49` 附近）在迁移里都有 `DEFAULT` 却无注解。Room 2.2+ 只在 entity 侧声明了 default 时才比对，所以当前**不会**因 `Migration didn't properly handle` 崩溃（设备日志也已证实 3→4/4→5/5→6/6→7 均通过校验），但这是“靠 Room 的宽松规则侥幸通过”。建议统一全部标注（打开 `exportSchema` 后由 `MigrationTestHelper` 兜住）。
- **P2-11 `CharacterRepository` 424 行的职责过载** —— 会话生命周期、消息写、卡片导入、头像落盘、卡库编辑混在一个类（`CharacterRepository.kt:41-45` 构造即拿两个 DAO）。没有单元测试的现状（`app/src/test/.../data/` 只有 `MessageVariantsTest`）正是因为无可测边界。建议拆 `ConversationStore`（会话+消息，带事务）与 `CharacterCardStore`（导入/落盘/编辑），Repository 只做组合。
- **P2-12 `ChatEngine` 在 data 层拼 prompt，无法单测** —— `app/src/main/java/com/teatalk/xs/data/ChatEngine.kt:99-105`、`systemPrompt:160-178`。prompt 组装是纯函数（历史裁剪 + 系统提示 + 6000 字符截断），应是 `:core` 里可测的 `PromptBuilder`；现在它和 DB/网络耦在 flow 体内。同时 `recentMessages(limit=20)`（`:96`）只按条数不按 token 预算，“20 条长消息”可以轻易超出上下文的另一半。建议抽 `PromptBuilder`（纯 Kotlin，进 `:core`，补测试）+ 引入字符/token 预算而非条数。
- **P2-13 日志 key 恒为 `send.retry`** —— `app/src/main/java/com/teatalk/xs/data/ChatEngine.kt:66-69`，正常发送也记成 retry，排查时误导（B7 同族）。

---

## 架构建议（建议落成 3 条 ADR）

1. **ADR：写路径的“权威标识”原则。** 任何异步结果落库必须携带它服务的宿主 id（`userMessageId`/`assistantMessageId`/`providerSnapshot`），禁止在写点用“最后一条记录”推断归属。这条同时消灭 P0-1、P1-1、P1-8。
2. **ADR：事务边界归属。** 规则：跨表写必须在单个 DAO `@Transaction` 方法内完成，Repository 不得串联两个 DAO 写；文件系统副作用与 DB 写之间使用补偿（先文件后 DB，DB 失败删文件）。附一条“schema 无外键 ⇒ 一致性由 DAO 事务承担”的显式说明（P1-2）。
3. **ADR：迁移契约。** `exportSchema = true` + schema 入库 + `MigrationTestHelper` 覆盖 1→7 全链；降级策略显式二选一（destructive-on-downgrade vs 崩溃）。这是 M2 唯一一处“当前配置下无法补救”的缺口（P1-5）。

---

## 已核查通过、不作为问题上报的点（避免误报）

- **迁移链完整且顺序正确**：`MIGRATION_1_2/2_3/3_4/4_5/5_6/6_7` 覆盖 1→7 无断点，`AppContainer.kt:49-56` 全部注册。
- **`index_messages_characterId` 不会缺失**：`git show 4b81d27:.../Entities.kt` 显示 v3 起 messages 就带 `indices=[Index("characterId")]`，表由 Room 建时即带索引；`MIGRATION_6_7` 只新增 `index_messages_conversationId`，不存在 “Migration didn't properly handle” 崩溃。
- **`conversations` 建表与 entity 一致**：`title TEXT NOT NULL`（无 DEFAULT）配 `title: String = ""`（无 `@ColumnInfo`），`INSERT` 显式给 `''`，Room 校验可过。
- **流式取消是干净的**：`ChatClient.kt:112` 的 `invokeOnCompletion { call.cancel() }`、`:202-204` 取消先记日志再 rethrow、`:205-221` 先 `ensureActive()` 再判错、`:222-224` `finally` 取消 call —— OkHttp 不会泄漏。
- **`generation` 守卫有效**（阻挡陈旧 UI 回调），只是它挡不住写库（见 P0-1）。
- **`insertGreetingIfEmpty` 用了 `@Transaction`**（`CharacterDao.kt:79-97`），是仓里唯一正确的事务示范。

**关于未修清单的对照**：`docs/reviews/m2/SUMMARY.md` 里 B3（发送/滚动竞态）、B4（非原子 RMW）、B5（stopped/failed 片段仍进 prompt）、B6（迁移测试）、B7、B8 至今未修 —— 本报告的 P0-1/P0-2/P2-1/P1-5 就是它们的现状，说明“记录在案”并未阻止它们随 M2 扩张而放大。B5 尤其值得在 M3 前处理：`ChatEngine.kt:96-105` 构造历史时不过滤 `status != complete` 的变体，被停止的半截回复会以“完整 assistant 轮次”进入下一次 prompt，直接损害回复质量且极难归因。

