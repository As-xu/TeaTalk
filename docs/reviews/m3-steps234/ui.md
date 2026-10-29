# M3 步骤 2/3/4 UI 视觉审阅（UI Designer）

> 审阅对象：commit `436b771`（0.9.0）· 焦点：①Prompt 面板 ②卡高级编辑 ③Me 预设行
> 依据：`docs/design/tokens.md`（色板/字阶/间距）、`docs/screenshots/m3-steps234/` 3 张截图、`docs/10-M3-进度与验收.md` §10
> 方法：逐张像素取样 + 按 WCAG 2.1 计算对比度 + 对照 token 核对字号/间距/触达面积；交互与数据问题见同目录 `ux.md`（本文只做视觉层，交叉引用其编号）
> 证据限制：3 张截图**均为浅色**（无暗色），`02-preset-row.png` 的 About 行是 `v0.8.2`（本 commit 已是 0.9.0）→ 暗色结论按 token 推演，截图不能作为 0.9.0 的验收证据（见 P2-9）

## 结论

1. 编辑弹层的 11 个输入框**没有可辨识的边界**（实测 1.08:1，暗色 1.00:1），也没有聚焦态 —— 截图 03 里那段 "First message" 读起来像引用块而不是输入框，这是 WCAG 1.4.11 / 2.4.7 的硬失败，P0。
2. Prompt 面板首屏**没有任何可执行动作**：半展开 + 内层 360dp 滚动 + 底部按钮 = Copy JSON 落在窗口外（截图 01 直证），P0。
3. 三处共同病根是**字阶/间距没落到 token**：面板正文 12sp（token 14.5）、hint 11sp + 0.8 alpha（3.50:1）、段间距=行间距（12dp），层级只能靠字重硬撑；预设行摘要把 5 个参数塞进 11.5sp 单行，1.3× 字体下必然截成 `pres 0…`。

## P0（本轮必修：不满足 WCAG AA 或主操作不可达）

| # | 位置 | 视觉问题（量化） | 改法 |
| --- | --- | --- | --- |
| V1 | `EditCardDialog.kt:126-133`（`Box` 字段容器）+ `:134-142`（`BasicTextField`） | 字段底 `surfaceContainerHigh`，弹层容器几乎同色：截图像素实测 `#EDE4D6` vs `#F3ECE4` = **1.08:1**；按 token 算 `#EDE4D6` vs `surfaceContainer #F2EBDF` = **1.06:1**，暗色下字段与弹层同为 `surfaceContainerHigh #2C251E` = **1.00:1**。无描边、无聚焦描边（`BasicTextField` 没有 `interactionSource`/focus ring，只有光标变色）→ 违反 WCAG 1.4.11（UI 组件 3:1）与 2.4.7（焦点可见）。11 个字段全部受影响，截图 03 里"只读引用块"的错觉就是它 | ①字段加 `1.dp` 边框 `outline`（`#8A7A6A` on `#EDE4D6` = **3.29:1**，on 弹层底 **3.53:1**；暗色 `#9A8B7C` on `#2C251E` = **4.57:1**）——**不要用 `outlineVariant`**（1.18:1 / 1.50:1，等于没画）；②聚焦时 `2.dp` `primary` 描边 + 背景提到 `surfaceContainerHighest`；③圆角 10dp 改成 token 的 12dp |
| V2 | `ChatScreen.kt:1314`（`ModalBottomSheet` 无 `sheetState`）、`:1345`（`heightIn(max=360.dp)`）、`:1383-1394`（按钮 Row） | 默认半展开（截图 01 面板顶边在屏幕 47% 处），外层 `Column` 不受高度约束、内层固定 360dp，按钮被推到窗口外：`01-prompt-panel.png` 里从标题到屏幕底边全是正文，**Close / Copy 一个都看不见**；且内层滚动会先吃掉手势，用户得先滑完 6144 字符才轮到展开面板。面板唯一产出（复制 JSON）首屏不可达 | 三段式：头（标题/provider/hint，固定）→ 体 `Modifier.weight(1f, false).verticalScroll(...)`（删掉 `heightIn(max=360.dp)`）→ 底栏固定，`FilledTonalButton`（高度 40dp、左右各 16dp）承载 "复制 JSON"；`state = rememberModalBottomSheetState(skipPartiallyExpanded = true)`；验收：2340×1080 横屏、1.3× 字体下 Close/Copy 与内容同屏 |

## P1（本轮应修，或写进已知限制）

| # | 位置 | 视觉问题（量化） | 改法 |
| --- | --- | --- | --- |
| V3 | `ChatScreen.kt:1337-1340`（hint 行） | `s11` + `onSurfaceVariant.copy(alpha = 0.8f)` → 8% 白底混合后 `#897E72` on `#F6F0E6` = **3.50:1**（在 `#FFFDF9` 上是 3.76:1），**浅色下不达 AA 4.5:1**；暗色 5.68:1 反而达标 → 主题依赖的无障碍失败。且 11sp 低于 token 最小 11.5sp | 去掉 alpha，直接 `onSurfaceVariant`（浅色 5.23:1 / 暗色 8.12:1）+ `s12_5`/行高 18。规则写进 tokens.md：**层级不得用 text alpha 表达** |
| V4 | `ChatScreen.kt:1342-1382`（面板主体） | ①6144 字符 system prompt 全量内联、无卡片、无折叠（`docs/10 §10.1` 承诺"卡片可展开"，未实现）；②正文 `s12`/行高 18（token 正文 14.5/22）；③分区标题 `s13 SemiBold` 与正文 12sp 只差 1sp+字重；④内层 `spacedBy(12.dp)` 让"标题→正文"和"段→段"同为 12dp，分组关系被抹平 → 6k 字正文读起来是一整块 | ①"系统提示"做成卡片：`surfaceContainerLow` 底 + 12dp 内边距 + 默认 3 行折叠 + "展开"（展开后 `heightIn(max=240.dp)` 内滚）+ 右上角 `复制`；②正文 14.5/22；③分区标题 15.5/22 W600，`labelGap = 4dp`、`sectionGap = 20dp`（拉开分组）；④顶部加分段 chips「系统 / 世界书 / 消息」做锚点跳转 |
| V5 | `ChatScreen.kt:1363-1369`（世界书行）、`:1375-1381`（消息行）、`PromptSnapshot.kt:24` | 消息只有 `1. user · 123`：**没有 position 标签**（`PromptMessageSnapshot` 根本没这个字段，`docs/10 §10.1` 说有），`123` 无单位；世界书行把 `comment · position · chars` 三件不同性质的东西塞进同一 12sp 灰字，position 还是裸英文 `before/antop/atdepth`。无 `maxLines`，长 comment 会折成多行冲散列表节奏 | 做成三列结构：左 `序号`（11.5sp tabular，`onSurfaceVariant`）· 中 `role/position` 胶囊（11.5sp W600，`secondaryContainer` 底）· 右 `chars`（11.5sp，右对齐）；comment `maxLines=1 + Ellipsis`；position/role 走 `prompt_position_*` 资源（同时解掉 ux C1） |
| V6 | `EditCardDialog.kt:66-82`（460dp 固定 + 11 字段无分组 + 无滚动条） | 截图 03 是"走不到"的直接证据：滚动区在 "System prompt" 标签处截断，标签下方没有输入框，4 个新增字段（`:79-82`）一个都没出现；顶部还留着一个被裁掉标签的半个字段，没有渐变/滚动条提示"上面还有内容"。`heightIn(max=460.dp)` 是死值，横屏（2340×1080）与 1.3× 字体下可见字段更少；多行 `BasicTextField` 吞竖向拖动（`docs/10 §10.4 ①` 自认），能起拖的只剩字段间 10dp 缝隙 | ①体改 `LazyColumn`（字段=item，滚动与输入不再抢手势）+ 右侧 `VerticalScrollbar`/顶部渐隐；②高度改 `heightIn(max = screenHeight * 0.6f)`；③"基础信息 / 高级设定（4 项）"分段，高级段默认收起（标题行 "高级设定 · 4 项"）+ 一句"追加到系统提示词末尾"；④最低成本兜底：高级段折叠后弹层只剩 7 个字段，不滑也能保存 |
| V7 | `EditCardDialog.kt:134-142` | `BasicTextField` 只有 `minLines`、**没有 `maxLines`/`heightIn`**：`snapshot.system` 那种 6144 字符内容填进 System prompt 后字段会长到几千 dp，弹层里"一个字段 > 一屏"，后面字段彻底不可达（截图 03 的 First message 已经长到 255dp 占掉半屏） | 多行字段 `maxLines = 8` + `heightIn(max = 200.dp)` + 字段内滚动；`singleLine` 字段补 `imeAction = Next` |
| V8 | `ProfileScreen.kt:213-227`（清除预设） | 弹层底部一行挤 3 个动作（`清除预设`(error) / `关闭` / `导入 ST 预设…`），销毁性动作与 `关闭` **紧邻**且同为 TextButton；点一下立即 `SamplingPresetStore.clear` + 关弹层，无二次确认、无撤销。触达面积本身合规（≥48dp），风险来自相邻与不可逆；入口只在弹层里，行上看不到 | 清除移出按钮行，做弹层正文里的独立"危险区"行（`outline` 1dp + `error` 文字，高 48dp，`Error / 清除预设` 左图标），点击弹二次确认「清除后需重新导入」；主按钮只留 `导入 ST 预设…`，`关闭` 单独一行或右上 X |
| V9 | `ProfileScreen.kt:156-162` + `:314-321` + `SamplingPreset.kt:96-105` | 5 个参数（`temp 0.65 · top_p 0.9 · max 512 · freq 0.2 · pres 0.1`）塞进 11.5sp 副标题槽、`maxLines=1 Ellipsis`。实测：串宽 ≈254dp，可用 ≈281dp，只剩 ~27dp（约 4 字符）余量；1.3× 字体需 ≈330dp → 截成 `temp 0.65 · top_p 0.9 · max 512 · freq 0…`，用户看不出 pres 有没有导入（且 `max 512` 无单位、五个键名未本地化） | 摘要模板化 + 分级：默认只显示"温度 0.65 · top_p 0.9 · 最大 512"+`+2`，`trailing` 槽右对齐（`SettingRow` 需支持真正的 trailing，而不是复用副标题）；长摘要进弹层看全量；顺手删掉从未被使用的 `profile_preset_sub`（ux C2） |

## P2（打磨 / 一致性 / 令牌补齐）

| # | 位置 | 问题 | 改法 |
| --- | --- | --- | --- |
| V10 | `ChatScreen.kt:1319-1324, 1332-1341, 1401-1408`；`EditCardDialog.kt:125` | 字阶不落 token：sheet 标题 17sp **W600**（token 页面内标题 17/24 **W700**）；provider 12sp（次要 12.5）；`chars` 计数 11sp（标签 11.5）；分区标题 13sp（不在字阶表里）；字段标签 11.5sp **W400**（token 标签 11.5/14 **W600**）→ 11 个字段的标签与正文只靠颜色区分 | 全部换成 token 字号/字重；`TypeScale` 增 `weight`/`lineHeight` 维度，避免到处手写 `fontWeight` |
| V11 | `ChatScreen.kt:1316, 1317, 1347`；`EditCardDialog.kt:70, 129, 132` | 间距不落 token：sheet 左右 20dp（token 屏幕边距 16dp，与 Me 页 16dp 不对齐，截图 01/02 可对比）、`spacedBy(10.dp)`、字段间距 10dp（token 12）、标签上边距 3dp（4dp 栅格）、内边距 10/8 | 统一 16/12/8/4；把 `space.screenPad / cardGap / labelGap` 写进 tokens.md |
| V12 | `ProfileScreen.kt:323-328` | chevron 18dp + `onSurfaceVariant.copy(alpha = 0.7f)` → 浅色 **3.08:1**（勉强过 3:1），是整行唯一的方向提示，弱 | 去 alpha（5.84:1）或改 `outline` |
| V13 | `ChatScreen.kt:1383-1394` | 复制按钮用最低强调 `TextButton` 且文案 `common_copy` 只有「复制/复制」，不说明复制的是 JSON；反馈用 `Toast`（与全 App snackbar 不一致，且 Android 13 另有系统剪贴板提示，双层） | `FilledTonalButton`「复制 JSON」+ snackbar「已复制为 JSON」（ux C3） |
| V14 | `ChatScreen.kt:1325-1330` | 空态只有一个句子，关不掉（按钮在 `else` 分支里） | 底栏提到 if/else 外，空态保留 Close（ux B3） |
| V15 | `ProfileScreen.kt:145` | 身份卡右上 "Edit" 是 `primary` 12.5sp 的链接样式，但**没有 `clickable`**（假链接），点不动；身份卡本身也无编辑入口 | 要么接上编辑，要么删掉这行文字（预置缺陷，非本轮引入，但同屏可见） |
| V16 | `ProfileScreen.kt:277-287` + tokens.md | 分组卡片 `surface` 与页面 `background` 仅 **1.05:1**（暗色 1.06:1）、固定 1dp `outlineVariant` 分隔线 **1.39:1**（暗色 1.83:1）→ 分组边界基本靠留白，弱光/低端屏会糊；本轮新增行落在这种分组里，扫读更吃力 | 分组加 `1.dp outlineVariant` 描边或把底改成 `surfaceContainerLow`（与 bg 拉开到 ≥1.2 靠描边补）；tokens.md 补"分组边界"规则（既有问题，非本轮引入） |
| V17 | `EditCardDialog.kt:62-64` vs `ChatScreen.kt:1319` | 同一 App 两种"标题"：AlertDialog 默认 `headlineSmall` 24sp（截图 03 的 "Edit card"），sheet 标题 17sp；tokens.md 里没有 dialog 标题规范 | tokens.md 增 `dialogTitle`（建议 22/28 W700，与本 App 大标题一致）+ `sheetTitle`（17/24 W700） |
| V18 | `ProfileScreen.kt:186-203` | 预设弹层正文不可滚（`Column` 无 scroll），hint 12sp + 摘要 13sp；横屏 + 1.3× 时 3 个按钮行会折成两行，正文可能被顶掉 | 正文 `verticalScroll`；动作收敛到 2 个（V8） |
| V19 | `docs/screenshots/m3-steps234/` | 证据口径：①无暗色截图，暗色无法验收；②`02` 显示 `v0.8.2`；③`01` 拍不到 Copy/Close（正好是 V2 的病灶）；④`03` 只拍到 "System prompt" 标签，4 个新字段全不可见，**不能作为步骤 3 的验收证据**（ux C7） | 修完 V1/V2/V6 后重拍 6 张：浅/暗 × ①面板（含 Copy JSON 与采样行）②编辑弹层高级段（划到 Alternate greetings）③Me 预设行（1.0× 与 1.3×），并确认 About 行版本号 |

## 暗色专项（无截图，按 token 推演）

| 元素 | 浅色 | 暗色 | 判定 |
| --- | --- | --- | --- |
| 字段底 vs 弹层底 | 1.06–1.08:1 | **1.00:1**（同为 `surfaceContainerHigh`） | 两套主题都失败，暗色更彻底 → V1 必须加描边，不能靠底色差 |
| hint（0.8 alpha） | **3.50:1** ✗ | 5.68:1 ✓ | 唯一"只在浅色失败"的项 → 证明 alpha 调文字不可移植 |
| 次要文字 `onSurfaceVariant` | 5.23–5.84:1 ✓ | 8.12–8.40:1 ✓ | 合规 |
| 字段/正文标签 | 4.71:1 ✓ | 7.28:1 ✓ | 合规（但仍需 W600 补层级） |
| chevron（0.7 alpha） | 3.08:1（临界） | 4.77:1 ✓ | 浅色偏弱 → V12 |
| `error`（清除预设） | 6.36:1 ✓ | 10.26:1 ✓ | 合规 |
| 分组卡 vs 页底 | 1.05:1 | 1.06:1 | 都弱 → V16 |

暗色下另需确认（截图缺失，需补）：输入框聚焦描边在 `#2C251E` 上 `primary #E7BE96` = **8.78:1** 观感会"放电"，建议聚焦描边用 `primary` + 1dp 内描边、不要整框填充；sheet 容器 `#221C17` 上放 `surfaceContainerLow` 卡片会看不见，卡片改用 `#2C251E`（`surfaceContainerHigh`）。

## 令牌缺口（建议本轮补进 `docs/design/tokens.md`）

```text
type.dialogTitle   22 / 28  W700      （补 V17）
type.sheetTitle    17 / 24  W700      （补 V10）
type.sectionTitle  15.5 / 22 W600     （补 V4）
type.caption       12.5 / 18 W400  onSurfaceVariant，禁止 alpha（补 V3）
color.fieldSurface / color.fieldOutline = outline（1dp，3:1+）（补 V1）
space.sheetPad 16 / space.sectionGap 20 / space.labelGap 4 / space.fieldGap 12（补 V11）
规则：文字层级只用语义色，不用 alpha；输入类组件的边界不得低于 3:1（WCAG 1.4.11）
```

## 验收（视觉层）

1. **对比度**：V1 字段描边 ≥3:1、V3 hint ≥4.5:1（浅/暗各测一次，可直接复用本次脚本的取值方式：取像素或 token 计算）。
2. **1.3× 字体 + 2340×1080 横屏**：面板 Close/Copy 同屏；编辑弹层能到 Alternate greetings 且 Save 可见；Me 预设行截断处不出现半个参数。
3. **暗色**：字段/卡片/分隔线在 `#171310` 底上仍可辨；无"纯色块"式输入框。
4. **证据**：6 张截图（V19），且 About 行版本号 = 构建版本。
