# M3 步骤 2/3/4 审阅（对象：0.9.0 / 436b771）

四份原文：`01-software-architect.md`、`02-ux-architect.md`（ux.md）、`03-ui-designer.md`（ui.md）、`04-reality-checker.md`。

## 已修（0.9.1）

| 编号 | 问题 | 处置 |
|---|---|---|
| P0-1 / A1 | `updateCard` 只写 7 个基础键，4 个高级字段静默丢弃且提示已保存 | `CardEditCodec.kt` 纯函数写回 4 键；`updateCard` 返回 Boolean；失败文案；`CardEditCodecTest` 4 条 |
| A2 | Prompt 面板中段无高度约束、Close/Copy 被挤出屏幕 | 三段式 + `weight(1f)` 中段滚动 + `skipPartiallyExpanded`；底部按钮固定 |
| A3 / P1-3 | 快照进程级且不带会话 id，切会话串台 | `PromptSnapshot.conversationId` + 面板按会话过滤 |
| P1-1 / B2 | 消息只有长度没有正文 | 消息与世界书条目均显示正文（限行省略） |
| P1-9 / 6 | 文档字段清单与测试数不实 | docs/10 §10 按实现重写；计数改 core 60 + app 49 = 109 |
| B11 | 面板不含采样参数 | 快照增 `sampling`，面板显示 `Sampling · …` |
| P1-5 / B12 | 预设越界值原样下发（-1 等） | 解析层夹取 + maxTokens>0 |
| P2-1 | 复制 JSON 手写转义可能非法 | 改用 kotlinx.serialization |
| B4 | 多行输入框截获拖动，高级字段不可达 | 编辑弹层改「字段行 + 单字段编辑器」 |

## 未修（遗留，见 docs/10 §10.5）

P1-2 预设全局作用域 / 无 provider 覆盖；P1-6 PHI 与 mes_example 的 ST 语义；P1-4 主线程读预设；
P1-7 system 分段静默截断；P2 若干（i18n 硬编码、saf 类型、导入预览/撤销、日志补采样参数）。
