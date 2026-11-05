# 0.24.0 代码检视记录（WebDAV 云备份）

- 检视对象：0.24.0 工作树（`versionName = 0.24.0`，`versionCode = 50`），相对 `a00ddfe`（0.23.0）。
- 检视方式：独立检视者（fresh context、只读、不改文件不构建）读 `git diff` + 6 个新增文件全文，交叉核对 SecretStore / AppPrefs / AppContainer / BackupRepository / HomeScreen / ProfileScreen / AndroidManifest / 两份 strings。
- 结论：**P0 无；P1 六条；P2 二十条。** 六条 P1 全部在本版修复并复验，P2 修复了影响正确性与体验的十条，其余归档。

## P0

无。未发现崩溃路径、未发现删除/覆盖用户既有数据的路径；核心链路（配置 → 上传 → 列表 → 恢复 → 跳过重复）在真机跑通。

## P1（本版已修）

| 编号 | 问题 | 影响 | 状态 |
| --- | --- | --- | --- |
| P1-1 | `href` 归一化混用 `encodedPath` 与 `URLDecoder.decode`；非 ASCII 基址（Nextcloud `/remote.php/dav/files/<中文用户名>/`）或文件名含 `+` 时相对路径多一截 | 列表条目不可用 → 恢复 404；DELETE 404 被当成功 → 保留策略静默失效 | 已修：统一「解码后的段」口径，`percentDecode` 只解 `%XX`，拼 URL 用 `addPathSegment`；补两条单测 |
| P1-2 | `busy` 未覆盖恢复：`askRestore/confirmRestore` 无忙判断、列表行可点 | 备份中可并发恢复，`busy` 状态机失效，可能双份会话 | 已修：入口 `if (busy) return`，行 `clickable(enabled = !busy)` |
| P1-3 | 恢复会话时「先 `insertExact` 后 `insertMessagesExact`」非事务 | 取消/异常后留下空会话，重试按标题判重 → 消息永久导不进来 | 已修：`ConversationDao.insertExactWithMessages`（`@Transaction`）；`CancellationException` 穿透 |
| P1-4 | `usesCleartextTraffic="true"` 全局生效且无提示 | 任意 `http://` provider 都会明文发 Key | 已修：地址为 `http://` 时配置区红字警告（明说只建议局域网/自建） |
| P1-5 | `get()` 无大小上限，`body.bytes()` 全量入内存 | 地址配错指到超大文件 → OOM（进程级崩溃） | 已修：`Content-Length` 预检 + 流式读，256 MB 上限中止 |
| P1-6 | `test()/backup()` 先 `save()` 再 `message = null`，把「地址为空」提示冲掉 | 空地址点测试看到英文内部错误，`webdav_need_config` 成为死资源 | 已修：`save(): Boolean`，失败直接返回 |

## P2（已修）

| 编号 | 问题 | 状态 |
| --- | --- | --- |
| 2 | `pruneRemote` 失败导致整次备份报错，诱导重复上传 | 已修：降级为警告，成功照常提示 |
| 3 | 未配置服务器时 `init` 仍 `refresh()`，一进页面弹「读取失败」 | 已修：`hasConfig()` 为真才刷新 |
| 4 | 目录不存在（404/409）被当失败 | 已修：视为空列表 |
| 7 | 快照只写不读，文案暗示可回滚 | 已修：弹层/文档改为「排障留底，App 内不提供回滚」 |
| 8 | 先写快照再下载：坏包白付一次全量导出 | 已修：下载 → `canRestore()` 校验 → 快照 → 导入 |
| 9 | `clearConfig` 不清远端目录，无二次确认 | 已修：目录一并清空（二次确认保留为后续体验项） |
| 10 | `password.trim()` 会改坏带空格的专用密码 | 已修：不 trim |
| 11 | KDoc 写「整包覆盖」与「补缺导入」矛盾 | 已修 |
| 18 | 保存后列表仍是旧服务器内容，点恢复可能取到别处同名包 | 已修：保存后清空列表并刷新 |
| 20 | 保留策略单测自证同义反复 | 已修：改硬编码期望集合 |

## P2（归档，未在本版处理）

1. 阻塞式 OkHttp 不在协程取消链路（退出页面后上传仍跑满超时）。
5–6. 内部英文错误串尚未全量本地化；`stamp()` 已带毫秒但仍存在设备时钟回拨边界。
13–17, 19. 触达高度 < 48dp（`OutlinedAction`）、无 `Role.Button`、恢复中无取消入口、en 密码提示句式。已修 `imePadding`、按钮换行、弹层参数化。

## 已核实没问题（摘录）

- URL 拼接（base 有/无尾斜杠、dir 有/无尾斜杠）与 `makeDirs` 逐级 MKCOL、405 容忍。
- PROPFIND Depth 0/1、命名空间解析与自身条目跳过；401/403 分类；DELETE 404 幂等。
- XXE 加固逐个 `runCatching`（Android 不支持时不失败）。
- 凭据不进日志：`webdav.put` 只记 path/bytes，`redactedBase()` 走 `Redact.url`。
- 恢复不删除/更新任何既有行；`restore()` 返回 null 时中止，已写入部分是纯增量。
- 快照写失败即中止，不会「没留底就动库」。
- 两份 strings 新增 key 一一对应、占位符个数一致。

## 复验

- `:app:assembleDebug :app:testDebugUnitTest` BUILD SUCCESSFUL（新增 `listHandlesNonAsciiBaseAndEncodedHref`、`listKeepsPlusInFileNames`，共 11 条 WebDAV 单测）。
- 真机（TAS-AN00 / Android 12）复验：`MyBackups` 目录上传、明文警告、三行恢复弹层、重复恢复 `skipped=8`，截图 `docs/screenshots/v024/08..10`。
