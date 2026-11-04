# 14 · M5 打磨与 Beta（进行中）

目标（路线图 M5「打磨与 Beta」）：

1. 冷启动 < 2s（中端机）；
2. 1 万条消息的会话打开与滚动不卡；
3. 发布 GitHub Release APK（用户已明确：A 包发布链暂缓，先做 B/C/D 与 V1 Backlog）。

执行顺序：B 性能 → C 稳定可观测 → D 体验打磨 → V1 Backlog 首批。

## B 性能

### B1 冷启动基线（改造前）

- 设备：HUAWEI TAS-AN00（Android 12 / API 31 / 1080x2340），真机串号见 `docs/env.md`。
- 该 ROM 上 `am start -W` 的 `WaitTime` 恒为 3038–3062ms、`LaunchState: UNKNOWN`、无 `TotalTime`，**不可作为冷启动指标**。
- 替代口径：`logcat -b events` 的 `wm_create_activity → wm_on_create_called` ≈ 1.22–1.25s；最终以自埋点 `app.home_ready` 为准。
- 截图轮询测得：进程启动到会话列表 ≈ 3.87s（粒度粗，仅作数量级参考）。

### B2 大数据量基线（改造前）

- 造数：把会话 1 直插 10000 条消息（DB 拷贝方式，见 `docs/env.md`），会话总计 10009 条。
- 打开会话到 `#9999` ≈ 3.32s；滚动本身已流畅（`dumpsys gfxinfo` 1058 帧 / Janky 10 = 0.95% / 50th 5ms / 90th 15ms）。
- 结论：瓶颈在「打开时全量加载」，不在滚动。

### B3 分页加载（已落地）

- `CharacterDao.observeRecentMessages(conversationId, limit)`：内层 `ORDER BY createdAt DESC, id DESC LIMIT :limit`、外层 ASC，只观察尾部一页。
- `CharacterDao.messagesBefore(conversationId, createdAt, id, limit)`：向上翻页（`(createdAt < :createdAt OR (createdAt = :createdAt AND id < :id))`）。
- UI：`PAGE_SIZE = 120`；`mergePaged(older, tail)` 按 id 去重后按 `(createdAt, id)` 升序；`snapshotFlow { firstVisibleItemIndex } <= 2` 时 `loadOlder()`；顶部显示 `chat_loading_older`。
- **效果：冷启动 815ms、打开 10000 条消息会话 419ms**（改造前 3.87s / 3.32s）。

### PerfLog（性能埋点口径）

- 关键决策：性能埋点不能依赖用户「调试日志」开关（`TeaTalkLog` 关闭时 `minLevel = W` 会静音 I/D）。
- `log/PerfLog.kt`：常开 `Log.i("TeaTalkPerf", "事件名 k=v …")`，**只记耗时/条数，不含消息内容**。
- 当前埋点：`app.home_ready`（`SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()`）、`chat.open`、`chat.page`。
- 观测：`adb logcat -s TeaTalkPerf:V`。

## C 稳定与可观测

### C1 崩溃收集（已落地）

- `log/CrashLogStore.kt`：
  - `install(context)` 在 `TeaTalkApp.onCreate` 里包装默认 `UncaughtExceptionHandler`，**先写盘再交回系统默认行为**，不吞崩溃；
  - 落地 `filesDir/crashes/crash-yyyyMMdd-HHmmss.txt`，内容含时间、版本、设备、线程、完整堆栈、最近 200 条内存日志（不含聊天正文）；
  - 保留最近 10 个文件；`clear()` 清空；写盘后记 `crash.saved`（PerfLog）。
- About 页新增「崩溃记录（N）」入口：弹窗列出文件名，支持「分享最近一次」「清除全部」。截图：`docs/screenshots/beta-1/01-about-crash-entry.png`、`02-crash-dialog.png`。
- 自测入口（仅 DEBUG）：`am start -n com.teatalk.xs/.MainActivity --ez teatalk.crash true`，真机验证生成 `crash-20261103-004611.txt`（1764 B，含 `version=0.12.3(33)` 与完整堆栈）。

## 版本

- 0.13.0：B3 分页 + PerfLog + C1 崩溃收集。
- 0.14.0：V1 首批「聊天搜索」（会话 + 消息分区，点击消息行直达并定位到该条，详见 `docs/14-V1-聊天搜索.md`）。

- 0.15.0：C2 错误兜底复查 —— 流中途错误帧改为携带服务端原文（不再显示「HTTP 200」）、provider 测试/拉取失败按状态码与异常类型本地化（401/403/404/429/5xx、超时、断网、地址缺 scheme），并补 11 条映射单测。
- 0.16.0：D1 动效与空态 —— 路由切换改为 180ms 淡入/120ms 淡出；聊天页新增「回到最新」悬浮按钮（离开底部时淡入 + 上滑，点击 `animateScrollToItem` 回尾部）；provider 空态字号与其余空态统一（s17/Medium/onBackground）。

- 0.17.0：V1 摘要记忆 —— 历史窗口（最近 20 条）之外再取 200 条，由 `MemoryDigest` 做确定性抽样（最多 10 行、单行 140 字、总计 1200 字）生成 `[Earlier conversation]` 摘要，注入到「世界书之后、角色定义之前」；`memory = null` 时输出与改造前逐条一致，既有 ST golden 对拍不受影响。详见 `docs/15-V1-摘要记忆.md`。

- 0.18.0：V1 正则/变量 —— 新增 DB v11 `regex_rules` / `chat_variables` 两表；`RegexEngine`（flags i/m/s/g、编译缓存、坏正则跳过）与 `MacroEngine`（getvar/setvar/addvar、user/char、time/date/datetime/weekday、random/pick，最多 5 轮展开）均可在 prompt 出站时链式生效并写回变量；display 规则只改气泡显示、不改库内正文；设置页新增「正则脚本」管理（CRUD + 实时预览）。详见 `docs/16-正则与变量.md`。

- 0.18.1：修 0.18.0 的真机启动崩溃 —— `MIGRATION_10_11` 建表 SQL 带了 `DEFAULT` 且多建了
  `conversationId` 索引，与实体 `ChatVariableEntity` 不一致，Room 迁移校验失败
  （`Migration didn't properly handle: chat_variables`）。改为「迁移 SQL 无默认值 + 实体声明
  `indices = [Index("conversationId")]`」，让 schema 与实体严格对齐，identityHash 同时更新
  （v11 刚发布、所有人都会在迁移处崩溃回滚到 v10，因此改 identityHash 安全）。

- 0.18.2：真机验收又抓出两个只在设备上暴露的问题 —— ① `MacroEngine` 的 `}}` 正则在 Android ICU 下
  非法，一进聊天就 `ExceptionInInitializerError`（JVM 单测全绿），已转义为 `\}\}`；
  ② `RegexEngine.apply(promptScope = true)` 默认值让三处 display 调用被误当 prompt 过滤，
  display 规则静默失效，已在三个调用点显式传 `promptScope = false`。
  真机四项验完：display 改气泡不改库、prompt 在「查看本次请求」可见、`{{setvar}}` 落库、
  重启后 `{{getvar}}` 仍读得到。详见 `docs/16-正则与变量.md` 的「验证」段。

- 0.19.0：V1 快速回复 —— DB v12 新增 `quick_replies` 表（`QuickReplyEntity` + `QuickReplyDao`，
  建表 SQL 无 `DEFAULT`、列全 `NOT NULL`，与实体严格对齐）；`我的 → 快速回复` 可增删改、
  可停用、可排序，聊天输入条新增 `/` 按钮弹出已启用片段，点选即追加进输入框（只插入不发送）；
  删除走二次确认。真机验证：新建/列表/`/` 插入/删除四个路径全过。详见 `docs/17-快速回复.md`。

- 0.20.0：V1 分支树 —— `conversations` 增加 `parentConversationId/forkMessageId/forkLabel`（均可空，
  避开 `ALTER TABLE … NOT NULL` 需要 `DEFAULT` 的坑）；assistant 气泡长按菜单新增「从此分支」，
  可命名，创建后自动进入新会话且历史正好截到该条；原会话不受影响，群聊分支会复制成员；
  会话列表对分支显示「分支」标签。逻辑抽成纯函数 `BranchFork.plan` 并有 7 例单测。
  详见 `docs/18-分支树.md`。
- 0.21.0：V1 翻译 —— DB v14 新增 `translations` 表（缓存键 = 目标语言 + 原文，换模型不失效）；
  「我的 → 翻译」可设目标语言（简中/繁中/英）、自动翻译开关、独立翻译接口（可跟随聊天接口）、
  缓存条数与清空；长按 AI 气泡新增「翻译 / 重新翻译 / 显示原文」，译文以次级卡片挂在原文下方；
  输入条 `/` 与 `+` 之间新增「译」，先出可编辑译文再直接发送。只翻给人看的正文，不动 system
  prompt/世界书注入/正则变量；原文永不改写。详见 `docs/19-翻译.md`。

- 0.21.1：卡片字段与世界书条目翻译 —— 复用 0.21.0 的 `TranslationRepository` 与 `translations` 缓存
  （键 = 目标语言 + 原文，换模型不失效）；卡片详情四段（设定/开场白/人设与场景/创作者注释）与世界书
  条目正文各加「翻译 / 显示原文」，译文以次级卡片追加、原文保留；失败统一映射到既有 `translate_*` 文案。
  译文只存内存，不写回卡片 JSON 与世界书，避免污染导出。详见 `docs/20-卡片与世界书翻译.md`。

- 0.21.2：翻译 P1 修复 —— 长文本分块（`TranslationChunker`，默认 1500 字/块）+ `max_tokens` 8192 + 章节卡内红字失败提示 + `重新翻译` 透传 `force`；复测 2168 字开场白分 2 块成功。详见 docs/21-翻译真机验收.md。
- 0.21.3：世界书条目翻译失败内联提示 —— 世界书详情是底部弹层，原先失败只走 snackbar（被弹层遮住），现在条目正文下方按条目内联显示失败原因；同时把 `IOException`（离线/域名解析失败）从 `UNKNOWN` 改为 `NETWORK`，提示「翻译接口请求失败」而不是笼统「翻译失败」。真机自造 `wb_teahouse` 世界书补验在线翻译与断网失败两条，均通过。详见 docs/21-翻译真机验收.md。

- 0.22.0：角色卡批量管理 —— 角色页 ⋮ →「批量管理」进入选择模式（标题「已选 N」/ 全选 / 取消），卡片左上角勾选圈，底部「收藏 / 取消收藏 / 删除」批量操作，删除带二次确认（连带聊天记录，不可撤销）；**不新增分组表**，分组语义继续由既有标签筛选承担（避免 DB 迁移与新页面成本）。真机五张卡验证：选择 / 收藏 / 取消收藏 / 删除确认 / 全选 全部通过，截图 `docs/screenshots/v022/`。详见 `docs/22-批量管理.md`。

## 已知问题

- 0.21.x 真机验收（2026-11-04/05，TAS-AN00）：0.21.0 十条通过（设置页四区 / 切语言持久化 / 接口选择 / 气泡翻译 zh 与 en（`trans.ok lang=zh-Hans|en`）/ 显示原文与重新翻译 / 缓存计数 0→1→2 且按语言隔离 / 自动翻译开关 / 出站「译」→ 可编辑译文 → 用译文发送）。0.21.1 章节「翻译」动作与「翻译中」通过；长文 P1 已于 0.21.2 修复并复测通过（2168 字开场白分 2 块，`trans.ok chunks=2`，英文译文卡正常）。0.21.3 补齐最后两项：自造 ST 世界书 `wb_teahouse` 导入后条目「翻译」出英文译文（`15-worldbook-entry-translated.png`），断网点「翻译」在条目下方显示红字「翻译接口请求失败」（`16-worldbook-translate-error.png`，此前 `SnackbarHost` 被 `ModalBottomSheet` 遮挡、且 `IOException` 被误归 `UNKNOWN`）。详见 docs/21-翻译真机验收.md。
- 上滑触发 `chat.page` 的日志证据尚未取到（`logcat -s TeaTalkPerf:V` 无输出）；但 `chat.open ms=419` 与尾部消息截图说明分页生效，待下次真机复盘（可能与滑动方向/首次可见索引未到阈值有关）。
- 0.18.0 的真机验收已在 0.18.1/0.18.2 补齐（迁移崩溃、ICU 正则、display 作用域三个问题修复后，正则页 CRUD、display 改气泡、prompt 面板、变量落库与跨重启保留均通过）。
