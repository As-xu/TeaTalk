# M3 世界书绑定 · 视觉复核（0.8.1 / commit `320521f`）

> 复核人：UI Designer · 复核方式：代码走查 + 对比度实算（WCAG 2.1 相对亮度）+ `docs/screenshots/m3-fix/` 截图逐像素测量（1080×2340 @2.75x，393×851dp）
> 复核范围：胶囊描边、绑定面板层级/行高/复选框、计数行与分区标题、按钮与保存反馈、长文本与 1.3× 字号。

## 一、结论

- **P0：0 项。** 上一轮的三条（描边对比度、图层/材质、密度与分段）中，描边已达标修复，另两条**部分残留**。
- **P1：6 项**（层级/对齐/大字号裁切/长名字吞计数/点按区/主次按钮）。
- **P2：9 项**（死参数、文案口径、截断方式、分隔线、暗色 snackbar 等）。

### 逐项验证

| # | 复核项 | 结论 |
| --- | --- | --- |
| 1 | 胶囊描边 ≥3:1（亮/暗） | **通过**。亮 3.88:1 / 暗 5.59:1（详见 §2） |
| 2 | 绑定面板层级、行高、复选框 | **不通过**：三套左轴（20 / 32.7 / 72.4dp）、两套行高（48.7 / 56dp）、复选框与 App 既有勾选语言不一致 |
| 3 | 计数行 vs 分区标题层级 | **不通过**：分区标题与条目副标题同色仅差 1sp；标题→内容间距 8.3dp vs 20.8dp |
| 4 | Cancel/Save、禁用态、snackbar | **部分通过**：语义正确（草稿态、dirty 才可 Save），但主次不分、亮色禁用态 2.24:1、二次保存无反馈 |
| 5 | 700+ 字符 / 1.3× 小屏 | **不通过**：胶囊与主 CTA 在 EN + 1.3× 下裁切；长书名吞掉右侧计数 |

## 二、第 1 项：对比度实算（验证 A7 确实修好）

| 元素 | 亮色（奶油茶） | 暗色（奶油茶夜） | 门槛 |
| --- | --- | --- | --- |
| 胶囊描边 `outline` | `#8A7A6A` on `#FBF7F0` = **3.88:1** | `#9A8B7C` on `#171310` = **5.59:1** | ≥3:1 ✅ |
| 同上，落在面板底 `surfaceContainerLow` | 3.65:1 | 5.10:1 | ≥3:1 ✅ |
| 同上，若落到 `surfaceContainerHigh` | **3.29:1** | — | 余量最薄 |
| 旧值 `outlineVariant`（复核基准） | 1.39:1 | 1.83:1 | ❌（已替换） |
| 胶囊文字 `onSurfaceVariant` | 5.55:1 | 8.90:1 | ≥4.5:1 ✅ |

结论：`CharacterDetailScreen.kt:503` 的 `outline` 选择正确，暗色余量充足，**亮色余量仅 0.29**（3.29:1 上限场景），后续不要再把胶囊挪到 `surfaceContainerHigh` 之上。

## 三、P1（6 项）

### P1-1 分区标题与条目副标题只差 1sp，亮色几乎同色
- 位置：`app/src/main/java/com/teatalk/xs/ui/character/CharacterDetailScreen.kt:375-380`（`Built into card`）、`:400-410`（`Library`）vs 条目副标题 `:389-395`。
- 现状：标题 12sp/Medium/`onSurfaceVariant`；副标题 11sp/Regular/**同一个** `onSurfaceVariant`。亮色下两者对比度都是 5.23:1（面板底 `#F6F0E6`），只靠 1sp（+9%）和 Medium 区分。
- 期望：标题 12sp / `FontWeight.Medium` / `letterSpacing = 0.4.sp` / 色 `onSurface.copy(alpha = 0.78f)`（亮色 ≈7.1:1，暗色 ≈8.8:1，与副标题 5.23:1 拉开）；或直接 13sp SemiBold。副标题保持 11sp `onSurfaceVariant`。**亮暗差异**：暗色标题 8.13:1 已经够强，问题主要出在亮色（5.23:1）。

### P1-2 两条分区标题→内容间距不一致（实测 8.3dp vs 20.8dp）
- 位置：`:374-381` + `:382-397`（内嵌段）vs `:400-411` + `:427-451`（书库段）。
- 实测（截图 02，2.75x）：`Built into card` 底 463.3dp → 首个条目名顶 471.6dp = **8.3dp**；`Library` 底 690.1dp → 复选框视觉顶 710.9dp = **20.8dp**。
- 成因：书库行的高度由 `Checkbox` 的 48dp 触摸区撑起（视觉盒子只有 20dp，上下各 14dp 空白），而内嵌行没有。
- 期望：两段"标题底→首行内容顶"统一到 **8–12dp**；做法是书库行改 `Modifier.toggleable` 撑 48dp、`Checkbox(modifier = Modifier.size(20.dp), onCheckedChange = null)`，把触摸区交给整行。

### P1-3 三个左对齐轴（实测 20 / 32.7 / 72.4dp）
- 位置：`:357`（面板 20dp 内边距）、`:383`（内嵌条目 `padding(start = 12.dp)`）、`:433-442`（复选框 48dp + `start = 4.dp`）。
- 实测：标题 20.4dp / 内嵌条目 32.7dp / 书库书名 72.4dp（屏幕 393dp 宽）。
- 期望：只留两条轴——标题与内嵌条目同为 **20dp**（去掉内嵌段的 12dp 缩进，靠"无控件"本身表达从属）；书库书名与内嵌条目**同轴**，即复选框改为行尾（见 P1-4），书名回到 20dp。当前 32.7/72.4 的双缩进是"分段不齐"的主要视觉来源。

### P1-4 复选框与 App 既有勾选语言不一致；且 56dp 行里只有 48dp 可点
- 位置：`:427-451`（`Checkbox` 在行首、只有它可点，`:428` 注释自陈是"避免 TalkBack 双节点"）。
- 对照：App 唯一的既有"选中"语言是 `app/src/main/java/com/teatalk/xs/ui/providers/ProvidersScreen.kt:380-390`——**行尾** 16dp 的 `CheckCircle / Outlined.Circle`（`primary` 着色）+ **整行可点**。本面板引入的是行首 Material Checkbox（20dp 视觉 / 48dp 触摸），等于同时新增了第三套语言。
- 后果：行高 56dp 但只有左侧 48dp 有响应；点书名无反应；与其它列表"整行可点"的肌肉记忆冲突。
- 期望（二选一，推荐 A）：
  - A：`Row(Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = ...))` + `Checkbox(onCheckedChange = null)` → 单语义节点 + 整行 56dp 目标 + 视觉不变。
  - B：改成行尾 `CheckCircle/Circle` 18dp + 整行可点，与 Providers 完全一致。

### P1-5 EN + 1.3× 下主 CTA 行被裁切
- 位置：`:292-307`（主按钮 `height(46.dp)` + `clip`，`Text` 无 `maxLines`）、`:497-514`（胶囊 `height(46.dp)` + `clip(RoundedCornerShape(23.dp))`，`:508-512` 的 `Text` 无 `maxLines/overflow`）。
- 实测（04 截图，392.7dp 屏 @1.0×）：主按钮 20.0→193.1（**173.5dp**）、间隙 10.5dp、胶囊 203.6→372.4（**169.1dp**，内含 18dp×2 内边距 + "World book · 4 entries" 文字 133dp），三者相加 353.1dp = 行宽，即**该行已被占满**。`Row` 先量非加权子项 → 胶囊拿走余量，`weight(1f)` 的主按钮只剩残值。
  - 392.7dp 屏 @1.3×：胶囊 ≈211dp → 主按钮 132dp ≥ "Start chat" 需求 ≈96dp ✅ 不裁。
  - 360dp 屏 @1.3×：可用 310dp → 主按钮 89dp < 96dp → 换行 2 行 ≈47dp > 46dp → **被 `clip` 裁掉**；若计数两位数（"12 entries"，胶囊 +10%）→ 剩 75dp，必裁。
  - 320dp 屏 @1.0×：可用 270dp → 主按钮 91dp ≥ 74dp ✅ 勉强；@1.3× 剩 21dp → 必裁。
  - 结论：**360dp 及更窄 + EN + 1.3×** 是当前的实际故障区间（360dp 是 Android 主力宽度）。
- 期望：两侧都 `Modifier.weight(1f)`（各半宽）+ `maxLines = 1, overflow = TextOverflow.Ellipsis` + `heightIn(min = 46.dp)` 取代 `height(46.dp)`；或胶囊在窄屏降级为「世界书 · 7」。

### P1-6 长书名吞掉右侧计数
- 位置：`:429-450`。行内是 `Checkbox`（非加权）→ `Text(name)`（非加权、无 `maxLines`）→ `Spacer(weight(1f))` → `Text(count)`（非加权）。
- 机制：非加权子项按顺序量，书名先拿到"整行剩余宽度"并换行，`Spacer` 权重收缩到 0，计数只拿到 0 宽 → **"3 entries" 直接不可见**，同时行高从 56dp 涨到 90dp+。
- 触发：`393 - 40 - 48 - 60 ≈ 245dp`；@1.3× EN 约 27 字符即触发（ST 里 `World book of the Northern Wastes vol 2` 远超）。
- 期望：书名 `Modifier.weight(1f)` + `maxLines = 1` + `Ellipsis`，计数固定 `Modifier.padding(start = 12.dp)`。

## 四、P2（9 项）

| # | 位置 | 问题 | 期望 |
| --- | --- | --- | --- |
| P2-1 | `CharacterDetailScreen.kt:310`、`:511` | `enabled = true` 硬编码 → 禁用分支（文字 `copy(alpha=0.45f)` = **1.92:1**）是死代码；即便走到，描边也不降透明度（描边满强度 + 文字 45%），禁用感不自洽 | 删掉参数，或 `enabled=false` 时描边用 `onSurface.copy(alpha = 0.12f)`、文字 `onSurface.copy(alpha = 0.38f)` |
| P2-2 | `:454-462` | Save 与 Cancel 都是 `TextButton` → enabled 时同为 `primary`（亮 5.11:1 / 暗 9.79:1），**两个按钮同色无主次**；disabled 时 Save 掉到 **2.24:1**（亮）/ **3.11:1**（暗），面板打开瞬间唯一有颜色的是"取消" | Save 用 `Button`（filled，`primary` 底）或 `FilledTonalButton`；Cancel 改 `onSurfaceVariant`。禁用态建议 `onSurface.copy(alpha = 0.5f)`（亮 ≈3.1:1）而不是 0.38 |
| P2-3 | `:198-203` | `_worldBookMessage` 恒为 `"saved"` → `StateFlow` 去重，**4s 内第二次保存不会弹 snackbar**（静默保存）；且 `consume` 在 `showSnackbar` 返回后才执行，配置变更会重弹 | 消息改递增 nonce（`Long` 计数器）或直接 `snackbarHostState.showSnackbar` 走队列 |
| P2-4 | `:341-344` | snackbar `padding(bottom = 16.dp)`，与 `ChatScreen.kt:936-939` 的 88dp+`imePadding()` 不一套；本页无 IME，实测未压到手势条（有 Scaffold 内边距兜底），但纯靠上下文偶然成立 | 统一成 `Modifier.align(BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)` |
| P2-5 | `:391-394` | `entry.content.take(80)` 是**按字符截断且无省略号**，截图可见 `her gown shimmering in` / `brow furro` / `eyes spar` / `turning ba` 半词截断；且 80 字符在中文 ≈3 行、英文 ≈1 行，中英密度不一致 | `maxLines = 2, overflow = Ellipsis`，删掉 `take(80)` |
| P2-6 | `:384-388` | 内嵌条目名直接渲染 ST 宏原文 `{{user}}`/`{{char}}`（截图可见），管理面板里出现开发者语法 | 预览先剥离 `{{...}}` 或取首句 |
| P2-7 | `:399` | 两段之间只有 8dp `Spacer`（实测 22.5dp 空白），无分隔线；App 列表语言是 0.6dp `Hairline`（`components/Ui.kt:137`） | `Library` 标题上方加 `Hairline()`，或把内嵌段放进 `surfaceContainerHighest` 底（亮暗均 ≈1.21:1，够分层不抢眼） |
| P2-8 | `:369-372` | `heightIn(max = 420.dp)` 不随 fontScale 缩放；1.3× + 短屏（≤640dp 高）时"手柄+抬头+420+按钮+24"已贴近面板上限，抬头再折一行就把动作行挤出可视区 | 外层 `Column` 加 `heightIn(max = …)` 且列表用 `weight(1f, fill = false)`，或 `fillMaxHeight(0.6f)` |
| P2-9 | `:517-535` vs `:375-380` | 同一份 App 里有第三套分区标题：`SectionCard` 是「3dp `primary` 竖条 + 13.5sp SemiBold + `onBackground`」，面板里是「12sp Medium + `onSurfaceVariant`」无竖条 | 抽 `SectionLabel` 组件，面板内用「无竖条」变体，字重/字距对齐 |

附加（复核中发现，非本轮改动引入）：
- `values-en/strings.xml:86-87` 用 Title Case（`Built into card` / `Library`），App 其余 EN 是 sentence case（`Import world books` / `World book binding saved`）→ 统一 sentence case。
- `values/strings.xml:89-90` 中文分隔符混用：「角色卡内嵌（7 条**，**随卡生效）」用「，」，「世界书库（3 本 **·** 已勾选 1）」用「·」→ 同一层级统一用「·」。
- 抬头文案「生效 %1$d 条」在 `0 books bound` 时读作「4 entries active · 0 books bound」（截图 03），语义上 0 不该占位；且「生效」承诺了实际不会注入（预算/位置过滤后）→ 建议 `可注入` 或 0 时省略该段。
- `:190`/`:309` 的生效条数是"卡内嵌 + 已绑定书条目"直接相加，与注入侧按 `content` 跨源去重（`d89507f` 的 A2）不同口径，绑定重复内容时会显示偏大。
- Hero 名称 `:252-257`（26sp Bold，无 `maxLines`）+ 380dp 定高 Box：700+ 字符卡名会画出 Hero 区域压到 CTA 行上（Box 不裁剪）。属详情页范围，建议 `maxLines = 2 + Ellipsis`。

## 五、亮色 / 暗色差异（按对比度排序）

1. **层级强度**：分区标题亮 5.23:1 / 暗 8.13:1 → 亮色下标题与副标题（同一 token）几乎不分层，暗色不会。P1-1 主要是亮色问题。
2. **禁用态可见度**：禁用 Save 亮 **2.24:1**（近隐形）/ 暗 **3.11:1**（可读）。同一个 `alpha = 0.38f` 在奶油底上是"消失"，在夜底上是"变灰"。若只修一处，优先亮色。
3. **选中态权重**：复选框填色 亮 `primary #8C5A33` 5.11:1 / 暗 `#E7BE96` **9.79:1**；勾选符号 5.79:1 / 7.49:1。暗色下勾选框成为整行最亮的点（奶油色圆角块），亮色下反而是沉稳的深棕 → 同一控件两种情绪，建议暗色 `primary` 只用于勾，或缩小到 18dp。
4. **snackbar 反色面**：亮色 `inverseSurface #2F2924`（深）在奶油底上不刺眼；暗色 `inverseSurface #EFE6DC` 几乎是纯奶油白，是暗色界面里**亮度最高**的一块（`onBackground #171310` L=0.0069 → `#EFE6DC` L=0.801），读起来像"警报"而非"已保存"。若要保持奶油茶夜调性，可把暗色 `inverseSurface` 调成 `#3A322A` + `inverseOnSurface #F6EFE6`。
5. **描边余量**：亮 3.88:1 / 暗 5.59:1，两者都过，但亮色余量小（见 §2），暗色可以放心。

## 六、证据与口径修正

- 截图目录 `docs/screenshots/m3-fix/` 四张：`01-pill-7-entries.png`、`02-sheet-bound.png`、`03-sheet-dirty.png`、`04-saved-snackbar.png`（22:29–22:32）。
- **`02-sheet-bound.png` 不能作为复数证据**：画面里是 `1 books bound` / `1 books`，而同一轮的 `03-sheet-dirty.png`（22:32）已是 `1 book` / `0 books`；`values-en` 的 `<item quantity="one">` 是在 `d89507f`（22:35）才落库的 → 02 是旧构建。当前 HEAD 三个 plurals（`detail_books_bound` / `_total` / `_selected`）均已有 one/other，正文已核对。
- 本轮无暗色截图，§5 的暗色数值是按 `ui/theme/Theme.kt:56-89` 的 token 实算，未做设备取色；建议补一张 `TeaNight` 截图存档。
- 1.3× 相关结论为"代码约束 + 像素测量 + 宽度推算"，未在 1.3× 设备上实拍。
