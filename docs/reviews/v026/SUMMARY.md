# 0.26.0 代码检视记录（发布准备）

- 检视对象：0.26.0 工作树（`versionName = 0.26.0`，`versionCode = 52`），相对 `dea06da`（0.25.0）。
- 检视方式：独立检视者（fresh context、只读、不改文件不构建）读 `git diff dea06da` 与 `data/update/`、`service/`、`ui/consent/`、`MainActivity`、`ConversationDao` 全文，并对真机日志与 `test-results` 交叉核对。
- 结论：**无 P0；P1 六条；P2 十五条。** P1 全部在本版修复并复验；P2 修了影响承诺与安全的七条，其余归档。

## P1（本版已修）

| 编号 | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| P1-1 | `release.sh` 只打 debug 包；`teatalk.update_feed` 只在 `BuildConfig.DEBUG` 下关闭，等于发布件接受任意同机 App 改写更新源 | 更新渠道可被劫持，配合文件名校验缺失可越界写文件 | 已修：新增 `BuildConfig.FEED_OVERRIDE`（默认 false，仅 `-Pteatalk.feedOverride=true` 为 true）；`MainActivity` 与 `UpdateManager` 都改判该字段，普通 debug/release 包一律忽略 intent 与残留 prefs。发布签名仍待补（见已知限制） |
| P1-2 | 关掉成人内容后，消息搜索仍返回并打开 NSFW 会话 | 「关闭后隐藏」被最常用的搜索入口绕过 | 已修：`ConversationDao.searchMessages` 增加 `hasNsfw` 列与 `AND (:allowNsfw OR NOT (...))` 条件，`MessageSearchHit` 带字段，`ChatListViewModel` 传 `allowNsfw()` |
| P1-3 | 前台服务停止走 `startForegroundService(ACTION_STOP)`；Android 12+ 后台启动受限，异常被 `runCatching` 吞掉 | 后台结束的生成会留下无法划掉的常驻通知 | 已修：`GenerationService.stop()` 改用 `context.stopService(...)`；`ACTION_STOP` 分支保留作兜底 |
| P1-4 | `check()` 跑在页面 `rememberCoroutineScope`，页面销毁即取消，`Checking` 状态无 `finally` 复位 | 返回上一页再进来永久显示「正在检查更新」，只能杀进程 | 已修：改为 `UpdateManager.scope.launch` + `try/finally`（取消时复位 `Idle`），调用点不再包 `launch` |
| P1-5 | 通知权限在同意页之上、每次 `onCreate` 都请求 | 与「首启只有同意页」不符；被拒后反复打扰 | 已修：移到 `onAccept` 之后，`AppPrefs.notificationAsked` 保证只问一次 |
| P1-6 | Gradle CLI 的 `-Dteatalk.cards.dir` 不会转发给单测 worker，真实卡回归永远 SKIP | 「20 张真实卡」发布门槛从未真正执行 | 已修：`app/build.gradle.kts` 用 `tasks.withType<Test>` 透传 `-D` 与 `TEATALK_CARDS_DIR` 环境变量 |

## P2

| 编号 | 问题 | 状态 |
| --- | --- | --- |
| P2-1 | 远端 `apkName` 直接拼路径，可 `../` 越界写 | 已修：取 basename + `canonicalPath` 前缀断言 |
| P2-2 | 非法 feedUrl 在 `try` 外抛异常，崩主线程 | 已修：`toHttpUrlOrNull()` 失败返回 `Failed` |
| P2-3 | 取消下载与进度回调竞态，可能写回 `Downloading` | 已修：回调再判 `_state.value is Downloading` |
| P2-4 | sha256 循环不可取消，取消后可能仍改名成功 | 已修：哈希循环内每块检查 `isActive`（保住完整包不如保住语义） |
| P2-5 | 无 digest 时静默安装截断包 | 已修：无 digest 时比对下载长度，不等即失败 |
| P2-6 | 未检查 `canRequestPackageInstalls()`，文案恒显示 | 归档：系统安装器会兜底提示，下一版做「去设置授权」跳转 |
| P2-7 | 标签 chip 与群成员选择来自未过滤角色 | 已修：`tags` 走同一 `allowNsfw` 过滤；成员选择器归档 |
| P2-8 | `allowNsfw` 是普通 lambda，非响应式 | 归档：当前 `WhileSubscribed(5s)` 下常规路径正确 |
| P2-9 | `CardCorpusTest` 中文用例可空集通过 | 已修：过滤后 `assumeTrue(files.isNotEmpty())` |
| P2-10 | `release.sh` 不校验 versionCode/工作树 | 已修：打印 `versionCode`，脏树给警告 |
| P2-11 | 同意页开关用 `remember`，旋转重置 | 已修：`rememberSaveable` |
| P2-12 | 失败文案直接显示英文异常 | 归档：下一版改 `FailureKind` + 资源文案 |
| P2-13 | 文档与实现不符（24h 复用、边下校验、未授权提示） | 已修：文档按实现改写 |
| P2-14 | 外部卡片导入绕过同意闸门 | 已修：同意前暂存 `pendingCard`，`onAccept` 后再导入 |
| P2-15 | 无启动时静默检查、离开页面看不到进度 | 归档：V1.1 再做 |

## 已核实没问题（摘录）

- 通知文案不含角色名/聊天内容；FileProvider 只暴露 `cache/{logs,export,updates}`；`begin/end` 三条路径都成对；`START_NOT_STICKY` 不会复活。
- sha256 覆盖全量下载且在 `renameTo` 之前；`.part` 在失败/取消路径删除（真机 `run-as ls cache/updates` 为空）。
- `LIKE '%"nsfw"%'` 带闭合引号，不误伤 `nsfw-art`；NULL `tagsJson` 读成 false 不崩。
- `isNewer` 覆盖 `v` 前缀、段数不等、非数字段；404/空 body/坏 JSON 都返回 `None`。
- 同意闸门位置正确：同意前不初始化会话数据、不发网络请求。
- 新增 zh/en 文案键名一一对应，占位符数量与调用处一致。

## 复验记录

- `:app:assembleDebug :app:testDebugUnitTest` 全绿（含 `UpdateClientTest`、`CharacterNsfwFilterTest`、`TtsControllerTest`）。
- 真机：同意页无权限弹窗 → 点「同意并继续」后进入角色页；下载中点取消回到「发现新版本」，`run-as ls cache/updates` 为空，日志 `update.download_cancelled`；普通构建忽略 `teatalk.update_feed`，走真实 GitHub（404 → 已是最新）。
