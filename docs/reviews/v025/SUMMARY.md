# 0.25.0 代码检视记录（朗读 / TTS）

- 检视对象：0.25.0 工作树（`versionName = 0.25.0`，`versionCode = 51`），相对 `a7e69ae`（0.24.0）。
- 检视方式：独立检视者（fresh context、只读、不改文件不构建）读 `git diff` + `data/tts/` 与 `ui/tts/` 全文，交叉核对 `ChatScreen` 接线、`AppPrefs`、`AppContainer`、`AndroidManifest`（`<queries>`）与两份 strings。
- 结论：**P0 一条（可稳定复现的崩溃）；P1 三条；P2 十五条。** P0 与 P1 全部在本版修复并复验；P2 修了影响正确性与体验的十一条，其余归档。

## P0（已修）

| 编号 | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| P0-1 | `ChatViewModel.init` 里就开始 `collect(tts.failed)`，而 `_notice` 声明在 40 行之后；`viewModelScope` 是 `Main.immediate`，首值同步 emit → 构造期 NPE | 试听中点「停止朗读」把 `_failed` 置 true 后，打开任意会话必崩 | 已修：把该 collect 移到 `_notice` 声明之后的独立 `init` 块，并在注释里写明原因 |

## P1（本版已修）

| 编号 | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| P1-1 | `preview()` 缺 `catch (CancellationException) { throw e }`，`stop()` 的取消被当成失败；开头也不清 `_failed` | 试听→停止后 `_failed` 残留，直接触发 P0，并留下 `tts.preview.fail` 误报 | 已修：补 rethrow，`preview` 开头清 flag |
| P1-2 | 设置页从不读 `controller.failed`，Scaffold 无 snackbar | 无引擎/无语音包时试听静默失败，用户以为没点到 | 已修：`failed` collect + SnackbarHost + `LaunchedEffect` 提示后 `clearFailure()` |
| P1-3 | 自动朗读无前台判断，文案却写「仅前台生效」 | 切后台/锁屏后突然外放 | 已修：新增 `AppForeground`（Application 注册 ActivityLifecycleCallbacks），`ttsAutoRead` 判定加 `AppForeground.isForeground` |

## P2（已修）

| 编号 | 问题 | 状态 |
| --- | --- | --- |
| 2 | 空文本（纯代码块/纯表情）也置失败，弹「引擎不可用」 | 已修：静默返回，日志降为 `tts.empty`（info） |
| 3 | `SystemTts` 引擎实例缓存后永不复位 | 已修：`speak` 返回非 SUCCESS 时 `resetEngine()`（shutdown + 置空），下次重建 |
| 4 | `deferred.await()` 无超时，厂商引擎不回调就永挂 | 已修：`withTimeoutOrNull(5s + 字数×80ms)`，超时停段继续 |
| 5 | `TtsText.emphasis` 全局删 `* _ ~ # > |`，`snake_case`/`C#`/`a*b*c` 被吃字 | 已修：成对强调替换 + 只删行首列表/引用符号 |
| 6 | 裸 URL 被逐字符念出 | 已修：`https?://\S+` → 空白 |
| 7 | `actionText` 定义未使用（死代码） | 已修：删除 |
| 8 | 退出会话页朗读继续，且「停止朗读」入口消失 | 已修：`ChatViewModel.onCleared()` → `tts.stop()` |
| 9 | 两个 Slider 无语义标签，TalkBack 只读「滑块 50%」 | 已修：滑杆 `semantics { contentDescription = 语速/音高 }` |
| 10 | en 文案「no Chinese voice installed」与多语种回复不符 | 已修：改为「no voice for this language installed」 |
| 11 | 无状态机测试（同 id 二次点击、切条、停止不误报、空文本） | 已修：新增 `TtsControllerTest` 6 例（假引擎 + `Dispatchers.setMain`） |

## P2（归档，未在本版处理）

- 群聊自动朗读只念第一条（有意为之，文档已写明；连读三条会打断用户）。
- `TtsController.release()` 仍无调用点：controller 是应用级单例、持有 `applicationContext`，进程结束随系统回收；真要在 `MainActivity.onDestroy(isFinishing)` 释放可后续补。
- 试听/朗读进度只在多段时显示；单段短文本不显示「第 1/1 段」。
- 气泡菜单无法在朗读时高亮当前气泡（只有菜单文案切换）。

## 已核实没问题（摘录）

- `TtsText` 的正则转义正确（`\\s`/`\\[`/`\\t` 单层转义）；`hardSplit` 已处理代理对，`"啊"+emoji×120` 不会被劈半且有单测。
- 每段一定 ≤ 200：合并分支有上限判断，超长走 `hardSplit`。
- `TextToSpeech` 的全部调用都在 `Dispatchers.Main.immediate`；`pending` 用 `ConcurrentHashMap`，`stop()` 给未完成 deferred 补 `TtsStopped` 后清空，不泄漏。
- `generation` 守卫有效：旧协程 `finally` 不会覆盖新朗读状态；`stop()` 只在 `speaking` 时复位。
- 停止不误报失败：catch 顺序为 `TtsStopped` → `CancellationException` rethrow → 通用 `Exception`；`wasStopped = stopped || stopping` 日志准确。
- 自动朗读在 `commitMutex` 之外、落库之后调用：不重入、不占锁、`appended` 为空自动跳过。
- `speakingMessageId` 用 `WhileSubscribed(5_000)`，无泄漏。
- `<queries>` 位置与写法正确（Android 11+ 必需）；zh/en 的 `tts_*` key 一一对应、占位符个数一致。

## 复验

- `:app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL（新增 `TtsControllerTest` 6 例、`TtsTextTest` 10 例）。
- 真机（TAS-AN00 / Android 12）：长按角色气泡「朗读 / 停止朗读」、自动朗读、试听、语速持久化均通过；**复验 P0 场景**：试听中停止 → 返回 → 打开会话，进程存活（`pidof` 有值）、无 `FATAL EXCEPTION`。
- 截图：`docs/screenshots/v025/01-bubble-menu.png`、`02-tts-settings.png`、`03-preview-speaking.png`、`04-auto-read-on.png`。
