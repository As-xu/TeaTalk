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

## 已知问题

- 上滑触发 `chat.page` 的日志证据尚未取到（`logcat -s TeaTalkPerf:V` 无输出）；但 `chat.open ms=419` 与尾部消息截图说明分页生效，待下次真机复盘（可能与滑动方向/首次可见索引未到阈值有关）。
- 0.18.0 的真机验收已在 0.18.1/0.18.2 补齐（迁移崩溃、ICU 正则、display 作用域三个问题修复后，正则页 CRUD、display 改气泡、prompt 面板、变量落库与跨重启保留均通过）。
