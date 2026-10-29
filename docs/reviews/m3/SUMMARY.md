# M3 步骤 1（世界书绑定与注入）审阅汇总

> 审阅对象：commit `cef478c`（0.8.0）· 四个专家（Software Architect / UX Architect / UI Designer / Reality Checker）
> 审阅方式：每路独立读代码 + 读文档 + （能连设备时）跑 AVD 截图；本文件由 Lead 复核去重后整理。

## 结论

**注入链路是通的（7 条 → 4 条命中 → 注入 3234 字符），但注入语义与 ST 有实质偏差，UI 有一处「假保存」级缺陷。**

## P0（已修）

| # | 问题 | 位置 | 处置 |
| --- | --- | --- | --- |
| A1 | at-depth 语义反了：`depth=0` 落在最后一条**之前**；`depth ≥ size` 被塞到历史最前；ANTop 用 `Int.MAX_VALUE/2` 哨兵 | `WorldInfoInjector.injectHistory` | 改为 ST 语义：插入点 = `size - depth`，夹取 `0..size`；depth=0 即最后一条之后；AN 改用 `AN_DEPTH=4` + `slot` 排序 |
| A2 | 跨书去重键 `(uid, comment)` 不成立（uid 是书内命名空间），卡内嵌 + 库书重复注入 | `ChatEngine` | 源内 `(uid, comment)`、跨源按 `content` 去重 |
| A3 | order 渲染方向反了（ST 是 999→1 循环 + unshift = 升序），且测试锁死了错误行为 | `WorldInfoInjector.block` / 测试 | `block()`/`beforeCharacter`/`afterCharacter` 升序；测试断言改为升序 |
| A4 | position 5/6/7 被激活、吃预算、又被所有路径丢弃；未知 position 字符串回落 0 → 混进 system prompt 最前 | `WorldInfoInjector.select` / `WorldInfoParser.parsePosition` | `select` 只保留 `position in 0..4` 并统计 `unsupported`；未知字符串解析为 `UNSUPPORTED_POSITION=-1` 后丢弃；字段缺失仍默认 0 |
| A5 | 绑定对话框勾选直接改 ViewModel，`Cancel` 不回滚、胶囊数字立即变、DB 不变（假保存） | `CharacterDetailScreen` | 新增草稿态 `draftWorldBookIds` + `openWorldBooks/cancelWorldBooks`；Save 才写库并弹「世界书绑定已保存」；Save 仅在 dirty 时可用 |
| A6 | 同一「世界书 N」两套口径（胶囊=条目数、对话框标题=条目数+本数） | `CharacterDetailScreen` | 胶囊 `世界书 · N 条`；对话框标题 `生效 N 条 · 已绑定 M 本`；库标题 `X 本 · 已勾选 Y`；全部走 plurals |
| A7 | 药丸描边用 `outlineVariant`（奶油 1.39:1 / 深色 1.83:1），低于 3:1 | `CharacterDetailScreen.OutlinedPill` | 改用 `outline`（3.88:1 / 5.59:1） |

## P1（本轮部分完成）

- ✅ `fromCard`/`fromStandalone` 统一为 `fromAny`（支持根数组），杜绝「显示 N 条、注入 0 条」。
- ✅ 预算单点：`select(..., MAX_WORLD_BOOK_CHARS=8000)`；system prompt 里不再二次 `take()`。
- ✅ `linkedWorldBookIdsJson` 四态解析（空白/空数组/合法/畸形），抽成 `WorldBookIds` + 5 条单测。
- ✅ 绑定不再写 `characters.updatedAt`（不会改变「最近」排序）。
- ✅ 删除死代码 `conversationWorldBookOverride`；会话覆盖 API 保留在数据层并注明「UI 待做」。
- ✅ 迁移测试改名 `migrate_1_to_current_*`，补「无孤儿消息」「按角色归属」断言。
- ✅ 世界书库空态提供「去导入世界书」入口；行不再整行可点，仅 Checkbox 交互（TalkBack 单节点）。
- ⏳ 未做：schema 根目录 `schemas/` 的 stale 清理（与 `app/schemas` 去重，下轮带过）、Prompt 调试面板。

## 证据口径修正

- 旧文档里的「顶部显示 `World book 7`」截图拍摄于计数口径修改前，**不能作为 7 条的证据**；本轮重拍为 `World book · 7 entries` + 对话框 `7 entries active · 1 book bound`。
- 旧文档「sysChars ≈1200（无世界书）」是按 4 条卡内嵌估算的值，按 7 条实测为 6174 字符，已在 `docs/10` 更正。
