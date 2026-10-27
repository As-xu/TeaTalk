# M2 审阅汇总（2026-10-27）

四份原始报告：`01-software-architect.md`、`02-ux-architect.md`、`03-ui-designer.md`、`04-reality-checker.md`。
审阅对象：commit `a65f2fe`（M2 步骤 1，多版本/重试/删除/状态，0.4.0）。

## 结论速览

| 审阅人 | 结论 |
|---|---|
| Software Architect | NEEDS WORK：功能骨架正确，但有 5 条需在步骤 2 前修的核心问题（重试无目标/无预检、版本追加猜测插入位置、非原子写、status 写在消息上但有 N 个版本、失败片段回流上下文） |
| UX Architect | 高级感来自“确定性与上下文”，M2 尚未达成：P0 四条（失败状态不可见、切 Provider 黑气泡、发送/滚动竞态、版本切换只有箭头无标签） |
| UI Designer | 视觉语言稳定可扩展；主要问题是 Light 模式对比、变体条视觉分量、`Online · Roleplay mode` 语义模糊、图标/空态细节 |
| Reality Checker | 系统完整，但文档与证据不一致：消息数、构建归属、测试数与“流式已验证”的范围需要按实修正 |

## 本轮已修（对应 chunk 提交）

| 编号 | 来源 | 问题 | 状态 |
|---|---|---|---|
| F1 | Arch #1 / UX P0 | `retry()` 无目标、无预检；只有开场白时会把 greeting 从历史剔除 | ✅ 已修：生成中→提示“正在生成”；先 `preflightError()`；无 assistant 消息→“还没有可重试的回复”；`retry` 不再空跑 |
| F2 | UX P0 / Arch #13 | 变体切换只有两个 26dp 小箭头，无标签、无无障碍描述 | ✅ 已修：32dp 图标按钮 + `Version 1 of 2` 文案 + `content-desc`（Previous/Next version）+ 禁用态 0.45 透明度 |
| F3 | UX P0 / Arch #12 | 删除多版本回复时未说明会删掉所有版本；确认按钮写“Close” | ✅ 已修：`This reply has N versions...` 文案；dismiss 改为 `Cancel` |
| F4 | Arch / UX | 滚动锚点 off-by-one（ChatList 第 0 项是日期分隔行） | ✅ 已修：`scrollToItem(count)` |
| F5 | Arch #1 | `lastAssistantMessageId` 缺失 | ✅ 新增 repository 方法 |

## 未修（已记录，排入 M2 步骤 2 / 后续）

| 编号 | 来源 | 问题 | 计划 |
|---|---|---|---|
| B1 | UX P0-1 / Arch #4 #6 | 版本级 `status` 缺失：一个气泡内有多个候选时无法表达“某候选失败/截断” | ✅ 已修（0.5.0，DB v5）：`variantStatusJson` + 变体条文字状态；状态点留待视觉打磨 |
| B2 | UX P0-2 | 切换 Provider 后旧消息不可用 → 黑气泡，无解释 | ✅ 已修（0.5.1，DB v6）：记录 providerId/name/model 并显示来源提示；可点击重试入口留待下一步 |
| B3 | UX P0-3 | 发送/滚动竞态：发送新消息与 `scrollToItem` 同时发生 | 发送时先取消 stick-to-bottom，完成后再吸附一次 |
| B4 | Arch #2 #3 #14 | `appendAssistantReply` 非原子 RMW；调用方忽略返回值 | 改为事务 + 返回值检查，失败时回滚并提示 |
| B5 | Arch #5 | stopped/failed 片段被当成历史送进下一次 prompt | 构造历史时过滤非 complete 候选 |
| B6 | Arch #7 | `MIGRATION_3_4` 无自动化覆盖 | 引入 Room `MigrationTestHelper` + 迁移测试 |
| B7 | Arch #8 #10 #15 | 别名风险、content 镜像仅约定、日志/KDoc 仍是 M1 契约 | 文档与注释同步；content 镜像加断言 |
| B8 | Arch #11 / UX | 预览/删除弹层持有过期快照 | 弹层数据改为按 id 实时读取 |
| B9 | UI | Light 模式对比、变体条视觉分量、`Online · Roleplay mode` 语义、空态/图标细节 | 下一轮 UI 打磨 |
| B10 | Reality Checker | 文档数字/证据口径与实际不符 | 在 `docs/09-M2-进度与验收.md` 按实修正 |
