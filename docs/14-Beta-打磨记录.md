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
## 已知问题

- 上滑触发 `chat.page` 的日志证据尚未取到（`logcat -s TeaTalkPerf:V` 无输出）；但 `chat.open ms=419` 与尾部消息截图说明分页生效，待下次真机复盘（可能与滑动方向/首次可见索引未到阈值有关）。
