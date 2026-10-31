# M4 审阅记录（Software Architect / UX Researcher / UI Designer / Reality Checker）

审阅对象：M4 完成后的 `0.11.2`（提交范围 `c3c8e9b..79e7fc3`）。
四路结论统一为 **NEEDS WORK**。以下为原始结论的整理，不含虚构证据；
每条修复状态以 `docs/12-M4-进度与验收.md` §10 为准。

## Software Architect
- P0-1 世界书详情/编辑器持有旧快照（`WorldBooksScreen.kt:80/166/194`），保存后 UI 仍旧值。
- P0-2 世界书条目编辑写回会破坏原文：根为数组时 `WorldBookEntryLocation.rootArray` 被拼回原串；非 V2 `data.character_book` 结构假设过强。
- P1 条目内容键名大小写、`insertion_order`/`order` 双写、扩展字段透传问题；`ChatJsonl.parse` 单体异常/大文件边界；导入无事务/无上限；空名/预置值语义。
- P2 说明文档与实现不符（如 `12-M4` 声称的字段与实际 JSON 不一致）。

## UX Researcher
- P0-1 世界书编辑没有可见保存反馈，关闭面板即丢失（stale snapshot）。
- P0-2 新建角色空名点保存：对话框关闭但什么都没创建，无任何提示。
- P1 导入超大文件无提示/无上限；世界书保存失败无提示；「我的」页两行占位行点了没反应。
- P1 输入框高度固定 52dp，多行长文本无扩展。

## UI Designer
- P0-1 世界书编辑按钮与删除按钮相邻且无分组，误触风险。
- P0-2 「我的」页存在点了无反应的占位行（Backup & export / About 无内容页）。
- P1 世界书面板信息层级弱（无 section 标题/分隔线）；新建/编辑对话框按钮层级弱（取消/保存同等重量）。
- P1 World books 页删除按钮与行内容同级，缺少二次确认的分组。

## Reality Checker
- 文档声称 `app` 单测 52 条，实际跑出 60 条（11 个套件）。
- `docs/12` §3 的验收截图/日志行在仓库内找不到对应文件；`0.11.1` 版本号从未落地，属虚构。
- `StDirectoryImporter` 没有自动化测试；逗号关键词修复无回归测试。
- `docs/00` 中 `M4` 状态写成「完成」，但审阅发现的问题未修。

## 本轮修复状态（0.11.3）
| 结论 | 状态 |
| --- | --- |
| SA P0-1 快照陈旧 | 已修：保存成功后从 DB 重读并重渲染 |
| SA P0-2 写入破坏原串 | 已修：`WorldBookEntryPatch` 重写（根数组分支不再拼回原串） |
| SA P1 order/case/扩展 | 已修：同时写 `order`/`insertion_order`、camel/snake 双写、其余键透传 |
| SA P1 导入边界 | 已修：32MB 上限 `TooLarge`、跳过行 ≤5、swipe 截断、事务写入 |
| UX P0-1 保存反馈 | 已修：保存失败保留面板并提示；成功 snackbar |
| UX P0-2 空名 | 已修：name 错误态、对话框不关闭 |
| UX P1 占位行 | 已修：去掉无实现行 |
| UX P1 输入框高度 | 已修：`heightIn(min = 52.dp, max = 140.dp)` |
| UI P0-1 误触 | 已修：行尾操作拆为「编辑」文字按钮 + 独立「删除」 |
| UI P1 分组/层级 | 子节标题与分隔线、Save 实心按钮 |
| Reality 计数/虚构 | 已修：`docs/12` §10 记录真实证据与命令输出；版本按实际提交推进 |
| Reality 测试缺口 | 已补：`WorldBookEntryPatchTest`、`ChatJsonlTest` 边界；导入器仍待补集成测试 |
