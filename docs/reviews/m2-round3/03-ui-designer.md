# UI Designer — M2 审阅（对象 f30d9b6 / 0.7.0）

## M2 视觉审阅（commit `f30d9b6`，暗色「奶油茶夜」）

审阅方式：读代码 + 采样截图像素 + WCAG 2.x 相对亮度计算（仅读，未改任何代码）。截图里的实际颜色与 [Theme.kt](TeaTalk/app/src/main/java/com/teatalk/xs/ui/theme/Theme.kt#L50-L83) 完全一致（气泡 `#2C251E`、代码块 `#332B23`、用户气泡 `#E7BE96`、页面底色 `#171310`），所以下面的数值既是设计问题，也是实现问题。

Compose BOM 2024.06.00 对应 material3 1.2.1，相关默认容器色：`DropdownMenu`=`surfaceContainer`、`AlertDialog`=`surfaceContainerHigh`、`ModalBottomSheet`=`surfaceContainerLow`，均叠了 tonal elevation，所以实测比令牌值略亮。

---

## 0. 结论先说

**文字对比度不是问题，而且做得不错**：正文 12.24:1、引用/次要 7.28:1、用户气泡 7.49:1，全部远超 AA。

**真正的短板是「层与层之间」的分离度**，以及一个系统性行高 bug：

| 问题 | 严重度 | 一眼可否看出 |
|---|---|---|
| 代码块 / 行内代码 / 下拉菜单 / 重命名输入框 与其背景的层差 ≈ 1.0–1.09:1（等于看不见） | **P0** | 是，[01-blocks-dark.png](TeaTalk/docs/screenshots/m2-md/01-blocks-dark.png) 里代码块只剩“颜色的错觉” |
| 只传 `fontSize` 的 `Text` 继承了 `bodyLarge` 的 24sp 行高 + 0.5sp 字距 → 11sp 脚注行距比 14.5sp 正文还大 | **P0** | 是，脚注第二行像掉下去一行 |
| 变体切换箭头 32dp 触控区（<48dp），禁用态 2.77:1 | **P0** | 是，界面上唯一的翻页入口 |
| 字阶未落令牌：11 / 11.5 / 12 / 14 / 15 / 16 / 19.5sp 十几种，标题阶梯与 AppBar 倒挂 | **P1** | 是 |
| 引用条 4.57:1 比它要衬托的文字“更响”，装饰抢戏 | **P1** | 是 |
| 来源脚注比气泡宽（右边缘超出气泡约 40dp）、每条消息重复、出现 7 字孤儿行 | **P1** | 是，[01-provider-hint.png](TeaTalk/docs/screenshots/m2-b2/01-provider-hint.png) |
| 长按预览框：4 个同权重按钮一排、危险操作夹在中间、正文显示裸 `**` | **P1** | 是，[01-retry-dialog.png](TeaTalk/docs/screenshots/m2/01-retry-dialog.png) |

---

## 1. 暗色对比度：文字合格，分层不合格

### 1.1 文字对（全部合格，不需要动）

| 组合 | 实测 | 判定 |
|---|---|---|
| 正文 `onSurface` / AI 气泡 `surfaceContainerHigh` | **12.24:1** | AAA |
| 引用 + 次要文字 `onSurfaceVariant` / AI 气泡 | **7.28:1** | AAA |
| 用户气泡 `onPrimary` / `primary`（`#4A2A0F`/`#E7BE96`） | **7.49:1** | AAA |
| 版本/状态标签 `onSurfaceVariant` / 页底 | **8.90:1** | AAA |
| 代码文字 `onSurface` / 代码块 | **11.27:1** | AAA |
| 代码语言标签 `onSurfaceVariant` / 代码块 | **6.70:1** | AA+ |
| 来源脚注（`onSurfaceVariant`×0.85 合成 `#A69A8E`）/ 页底 | **6.71:1** | AA+ |
| 错误条 `onErrorContainer` / `errorContainer` | **7.24:1** | AAA |

### 1.2 层对（这才是暗色下的病灶）

本品自己已经把基准定下来了：**AI 气泡 vs 页底 = 1.22:1**，这是"一张卡片浮在底色上"的可读下限。低于它的层，暗色下就等于没有：

| 层对（截图实测） | 比值 | 判定 |
|---|---|---|
| 代码块 `#332B23` vs AI 气泡 `#2C251E` | **1.09:1** | 不可见，只是"隐约有块" |
| 行内代码底 `#332B23` vs AI 气泡 | **1.09:1** | 不可见（`kettle whistles — and then:` 那行只剩等宽字形在提示它） |
| 下拉菜单 `#2F2720` vs 它盖住的气泡 `#2C251E` | **1.03:1** | **层级倒挂**：菜单比被它遮挡的气泡还"平" |
| 底部面板 `#28211C` vs 页底 | 1.16:1 | 偏弱，靠 scrim 才读得出来 |
| 对话框 `#332A23` vs 底部面板 `#28211C` | 1.13:1 | 可接受 |
| 重命名输入框 `surfaceContainerHigh` vs 对话框（同 token，仅对话框多叠了 elevation） | **≈1.06:1**（按代码取值推算） | **输入框在对话框里是隐形的** |
| Hairline（`outlineVariant`×0.7，厚 0.6dp）vs 页底 | **1.48:1** | 偏弱，且 0.6dp 在 2.6x 屏上只有 1.56px，发虚 |

**注意这是明暗共通缺陷**：浅色下代码块 `#E6DBC9` vs 气泡 `#EDE4D6` 也是 **1.09:1**。所以别只修暗色，要修令牌。

**修复方向（令牌级，不改具体组件）**

| 令牌 | 暗色 | 浅色 | 与气泡实测 |
|---|---|---|---|
| `codeSurface`（代码块底） | `#120F0C`（=surfaceContainerLowest） | `#FFFFFF` | **1.26:1 / 1.26:1**（明暗对称） |
| 代码块描边 | 1dp `outlineVariant` | 1dp `outlineVariant` | 边 vs 代码块 1.89:1（暗） |
| `codeInlineSurface` | `#4A4038`（=outlineVariant） | `#D8CAB6` | **1.50:1 / 1.28:1** |
| 引用条 | `outline` @55% ≈ `#685D52` | 同比例 | **2.36:1**（弱于文字 7.28，弱于气泡边界 4.57） |
| 菜单容器 | `surfaceContainerHighest` + 1dp `outlineVariant` 描边 | 同 | 描边把菜单"抬"起来，别只靠 1.03:1 的色差 |

---

## 2. 字阶与行高（最系统性的问题）

### 2.1 根因：`Text(fontSize = …)` 会继承 24sp 行高

[ChatScreen.kt](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chats/ChatScreen.kt) 和 [Markdown.kt](TeaTalk/app/src/main/java/com/teatalk/xs/ui/components/Markdown.kt#L281-L396) 里大量写法是只给 `fontSize`，不给 `lineHeight`。Compose 会 merge 进 `LocalTextStyle`（material3 `bodyLarge` = **16sp / 24sp / letterSpacing 0.5sp**），于是：

- **11sp 的来源脚注跑在 24sp 行盒里**。截图实测：脚注两行行距 ≈50px，正文 14.5sp/22sp 行距 ≈46px，比值 1.087 ≈ 24/22 —— 也就是**小字比正文行距还松**，第二行看起来像单独一段。
- **代码块的 10sp 语言标签跑在 24sp 行盒里**，代码里只写了 `padding(bottom = 4.dp)`，实测标签到首行代码的空白 ≈17dp，多出的约 13dp 全是行盒盈余（[01-blocks-dark.png](TeaTalk/docs/screenshots/m2-md/01-blocks-dark.png) 代码块上半部的大片空洞就是这么来的）。
- **列表圆点 `Text("•", fontSize = 14.5.sp)` 也是 24sp 行盒**，比正文 22sp 高 2sp → 列表项步进从设计的 25sp 变成 ≈27sp（+8%），圆点与首行基线出现约 1sp 错位。
- 0.5sp 字距也被继承，小字号上显得字距过宽（脚注、`Stopped`、"更新于 16:09" 都能看出来）。

**建议**：(a) 在 `Theme.kt` 里挂一套 `TeaTalkTypography`，把 `bodySmall / labelSmall / labelMedium` 按令牌实测行高固定下来；(b) 加约定"禁止只写 `fontSize` 的 `Text`"，或在 `ui/theme/Type.kt` 提供 `Caption / Label / Body` 语义组件；(c) 代码块/标签/脚注这类必写 `lineHeight`。

### 2.2 令牌漂移（M2 新增 UI 实测值 vs [tokens.md](TeaTalk/docs/design/tokens.md#L50-L59)）

| 组件 | 现值 | 令牌值 | 差 |
|---|---|---|---|
| 面板标题「全部会话」 | 16sp / 24sp(继承) / W600 | 页面内标题 17 / 24 / W700 | 差 1sp + 字重 |
| 面板会话名 | 14.5sp / 24sp / **W400** | 列表主文字 15.5 / 22 / W600 | 小 1sp、轻两级 |
| 面板「更新于 16:09」 | 11.5sp / 24sp | 次要文字 12.5 / 18 | 小 1sp、行高大 6sp |
| 来源脚注 | 11sp / 24sp / α0.85 | 次要文字 12.5 / 18 | 小 1.5sp、行高大 6sp |
| 状态标签「已停止」 | 11.5sp / 24sp / W500 | 标签 11.5 / 14 / W600 | 行高大 10sp |
| 版本标签「版本 2/2」 | 11.5sp / 24sp / W500 | 标签 11.5 / 14 / W600 | 行高大 10sp |
| 聊天头副标题「在线 · 角色扮演模式」 | 11sp / 24sp | 次要文字 12.5 / 18 | 小 1.5sp、行高大 6sp |
| 消息列表「今天 16:09」 | 11sp / 24sp | 次要文字 12.5 / 18 | 同上 |
| 预览/删除对话框标题 | **16sp**（手写） | — | 下面 2.3 |
| 删除会话对话框标题 | **24sp**（AlertDialog 默认 headlineSmall） | — | 两条差 8sp |
| 对话框正文 | 14sp / 21sp | 正文 14.5 / 22 | 小 0.5 |
| 聊天列表行标题 | 15sp / 24sp / **W500** | 列表主文字 15.5 / 22 / W600 | 小 0.5sp、轻一级 |
| 下拉菜单项 | 16sp / 24sp / 0.5 字距（默认） | — | 比消息正文还大 |

一句话：**小字族集体"偷小一号 + 行高被撑大"**，大标题族"凭感觉放大"。这不是某一处，而是 M2 全部新组件的通病，建议一次性把上面 13 行落成令牌再逐一替换。

### 2.3 对话框标题两套体系

[ChatScreen.kt](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L606-L745) 里：预览框/删消息框手写 `fontSize = 16.sp`，重命名框/删会话框不写（= headlineSmall 24sp）。**同一个界面的两个对话框标题差 8sp**（对比 [04-delete-confirm.png](TeaTalk/docs/screenshots/m2-conversations/04-delete-confirm.png) 的「Delete chat」与 [01-retry-dialog.png](TeaTalk/docs/screenshots/m2/01-retry-dialog.png) 的「Message」）。24sp 也明显大于 AppBar 的 22sp 页标题，对话框标题成了全屏最大字。

**建议**：对话框标题统一 = 17sp / 24sp / W700（= 页面内标题角色），并写进令牌表新增一行「对话框标题」。

### 2.4 Markdown 标题阶梯与 AppBar 倒挂

[Markdown.kt](TeaTalk/app/src/main/java/com/teatalk/xs/ui/components/Markdown.kt#L294-L307)：H1=19.5sp、H2=17.5、H3=15.5、H4–H6=14.5（和正文同字号，只加粗），行高 `size+6`（1.31–1.39 比值，且不是令牌值）。

结果：[01-blocks-dark.png](TeaTalk/docs/screenshots/m2-md/01-blocks-dark.png) 里 `# Old Card's Sample`（19.5sp）**比聊天头角色名 15.5sp 大 26%**，成了整屏最大字。气泡最大宽 268dp，19.5sp 下每行只能放约 12 个汉字，角色扮演长回复里的 H1 会非常吵。

**建议阶梯（都取自现有令牌，不新增字号）**：

| 级别 | 建议 | 说明 |
|---|---|---|
| H1 | 17 / 22 / W700 | = 页面内标题，不再超过 AppBar |
| H2 | 15.5 / 22 / W700 | = 列表主文字字号 |
| H3–H6 | 14.5 / 22 / W600 + 前距 10dp | 靠字重 + 上方留白区隔，不再放大 |
| 标题上/下距 | 前 12dp / 后 4dp | 取代现在上下都 6dp 的"悬浮感" |

---

## 3. 间距节奏

1. **块间距扁平**：`MarkdownText` 用 `Arrangement.spacedBy(6.dp)`（Markdown.kt:291）对所有块一视同仁。标题、段落、列表、代码块之间全是 6dp → 标题既不"贴"下文，也不"离"上文（截图里 `Come in.` 上下空白几乎相等，看起来像浮在中间）。建议分层：段落↔段落 8、标题前 12/后 4、列表↔列表 6、代码块/引用前后 10。
2. **圆点撑高列表**（见 2.1）：列表项步进 ≈27sp vs 设计 25sp。
3. **水平内距没有统一梯子**：消息列表 14dp、错误条 14dp、输入条 10dp、面板 20dp、重命名框 12dp、令牌规定 16dp —— 同一屏出现 10/12/14/20 四种左边界。建议收敛为 **16（页面）/ 12（卡片内）/ 8（紧密控件）** 三档，聊天消息列表从 14 改 16 与令牌一致。
4. **版本切换条贴死输入条**：`padding(top = 1.dp)`（ChatScreen.kt:960），行高 32dp，下方紧接输入条 hairline → 版本条在视觉上被读成输入条的一部分（[04-dark-variants.png](TeaTalk/docs/screenshots/m2-fix/04-dark-variants.png) 底部）。建议：与气泡 4dp、与输入条 8dp，或干脆把切换器做成气泡底部的内嵌条（气泡内 6dp 顶分隔线 + 切换器）。
5. **气泡内边距 13/10**：非 4 栅格值，且与 16dp 圆角搭配显得贴边。建议 14/12（配 18dp 圆角，见 6.5）。
6. **面板标题到首行 ≈44dp**：`title padding(vertical=10)` + 行 padding(top=10) + 24sp 行盒盈余叠加，标题与列表之间像空了一格。建议标题 16dp 上 / 8dp 下，行 padding 12dp。

---

## 4. 按钮与图标一致性

| 问题 | 位置 | 具体 | 建议 |
|---|---|---|---|
| **触控目标 32dp** | 变体箭头 `Modifier.size(32.dp)`（ChatScreen.kt:968/988） | 小于 48dp 最小值，是全屏最易误触的控件 | 去掉 `.size(32.dp)`，`IconButton` 自带 40dp 视觉 / 48dp 触控；图标 18dp 保持 |
| 禁用态对比 2.77:1 | 同上，`onSurfaceVariant.copy(alpha=0.45f)` | 箭头几乎消失，用户读不出"到头了/还能翻" | 用 `onSurfaceVariant.copy(alpha = 0.38f)` 至少保持形状可辨，或加 `contentDescription` + 文字"已是最后一版" |
| **禁用发送像可用** | 输入条（ChatScreen.kt:583-599）：禁用=暗底 + 8.9:1 亮箭头 | 空输入时按钮仍很"实"，看着能点 | 禁用态图标降到 `onSurfaceVariant`×0.38，并去掉圆底填充 |
| **表情图标没有点击** | 输入框内 `Icons.Outlined.SentimentSatisfied`（ChatScreen.kt:556） | 摆在输入框里、与文本同层、有按钮观感，但无 `clickable` | 要么接上点击，要么移出输入框并降为装饰（去 tint 强度），别做"假按钮" |
| 圆尺寸混排 | 输入条一行内：`+` = IconButton 48dp、发送/停止 = 40dp 圆 | 两端视觉重量不对等 | 统一 40dp 视觉 + 48dp 触控（发送/停止也包一层 `minimumInteractiveComponentSize`） |
| **危险操作无差别** | 面板 edit / delete 同 `onSurfaceVariant`、同 18dp、相邻 48dp（ChatScreen.kt:856-871） | 删除与会话标题只隔一个编辑图标，无红色提示、无分隔 | delete 用 `cs.error`，并在 edit|delete 间加 8dp 或 1dp `outlineVariant` 竖分隔 |
| **选中态只靠颜色** | 面板当前会话只有 18dp `Check`（无 `contentDescription`、行无 selected 语义） | 违反"不要只靠颜色表达状态"，读屏也不知道哪条是当前 | 行底 `primaryContainer`（暗色已经很淡，可压到 40% 透明度）+ 行语义 `selected = true` + Check 给 `contentDescription` |
| 当前行标题被挤 | Check 插在 name 与 icon 之间，仅当前行占位 | 同一列表里当前行标题比其它行更早省略 | 给 Check 预留固定 18+8dp 槽位（非当前行留空）或把 Check 挪到行首 |
| 图标族混用 | `Filled.MoreVert/Add/Delete/Edit/Stop/Send` vs `Outlined.SentimentSatisfied`，尺寸 18/20/21/22/23dp | 暗色下 Outlined 的细笔画明显比 Filled 淡，混在一行里"重量"不齐 | 统一一套（建议 Filled）+ 尺寸令牌 18 / 22 / 24 |
| 下拉菜单太"平" | `DropdownMenu` 默认 `surfaceContainer`，实测 1.03:1 vs 气泡 | 菜单直接压在气泡上时读不出边界（[01-chat-menu.png](TeaTalk/docs/screenshots/m2-conversations/01-chat-menu.png)） | `containerColor = surfaceContainerHighest` + 1dp `outlineVariant` 描边或 elevation；两项加图标（`Add` / `ChatBubbleOutline`）区分「新建」与「全部」 |
| 菜单项字号 16sp | 同上 | 比消息正文 14.5 大，菜单看起来比内容"重" | 显式给 14.5sp / 20sp |

---

## 5. Markdown 代码块与引用的视觉层次

**代码块（[01-blocks-dark.png](TeaTalk/docs/screenshots/m2-md/01-blocks-dark.png)）**
1. 底与气泡 **1.09:1**，靠"亮度差"完全无法识别，只能用圆角轮廓猜 → 改 `codeSurface`（1.26:1）+ 1dp 描边（见 1.2 表）。
2. 代码文字用 `onSurface`（和正文同色）→ 代码块失去"另一类内容"的信号，只剩等宽字形。建议代码文字 `onSurface`、但容器/边距明确分开，或代码文字降到 `onSurface`×0.92。
3. 语言标签 10sp + `onSurfaceVariant` + 24sp 行盒 → 上半块大片空洞，标签读起来像正文残留的单词。建议 11sp / 16sp 行高 / W600 / `letterSpacing 0.06em` / 大写，或改为右上角小徽标。
4. **没有横向滚动**：`Text(block.code)` 直接软换行，长行会在令牌中间折断（`switch -> holds` 这类本来就短，但真实代码会）。建议 `horizontalScroll(rememberScrollState())` + 保留 `fillMaxWidth` 底色。
5. 代码块右/下内边距 10/8，与气泡 13/10 不同源 → 同一气泡内两种内距，建议 12/10。

**引用**
1. 竖条 3dp `outline`（4.57:1）**比它要衬托的文字还抢眼**：装饰的对比度是文字强调程度的 3.8 倍于层差（1.22:1）。建议 2dp、`outline`@55%（2.36:1）。
2. 引用文字 `onSurfaceVariant` 7.28:1 是对的，但**没有斜体/没有缩进层次**，与上下文的区别只靠灰度 → 与"次要文字"（时间戳、脚注）用了同一灰度，语义撞车。建议引用保持 `onSurfaceVariant` + 不斜体，但**与"说明性小字"区分 token**（例如小字用 `onSurfaceVariant`，引用用 `onSurfaceVariant` + 2dp 竖条，视觉上已经足够；不要在引用里再用 11sp）。
3. 引用块上下无 padding，竖条会与相邻块的文字几乎相接（截图里竖条顶端贴近上一列表项）→ 前后各 8dp。
4. 引用内 `codeBackground` 也传 `surfaceContainerHighest`，嵌套行内代码同样隐形。

**分隔线**：`padding(vertical = 2.dp)` 太紧（Markdown.kt:389），`outlineVariant` 在暗色只有 1.50:1 —— 建议 8dp 上下 + 用 `outlineVariant`（这是装饰线，1.5 可接受）。

---

## 6. 空态与提示文案排版

1. **来源脚注（[01-provider-hint.png](TeaTalk/docs/screenshots/m2-b2/01-provider-hint.png)）四个叠加问题**
   - 11sp + 继承 24sp 行高 + `alpha 0.85` → "Generated with DeepSeek · deepseek-flash (not the current provider)" 折成两行，第二行 `provider)` 是 7 字孤儿行，且行距比正文还大。
   - **宽度超出气泡**：脚注在气泡 `Column` 里没有 `widthIn(max = 268.dp)` 约束，实测右边缘比气泡宽约 40dp，破坏了"消息块"的视觉边界。
   - 每条来自其它 Provider 的消息都重复一次 → 长对话里变成噪音。
   - 与气泡仅 1dp → 读起来像下一条消息的开头。
   - 建议：12.5sp / 18sp / `onSurfaceVariant`（去掉 α）/ `maxLines = 1..2` + 省略号 / `widthIn(max = 268.dp)` / 与气泡 4dp；文案改为短句 `DeepSeek · deepseek-flash`，把「与当前接口不同」降级为长按预览里的说明；同一 Provider 连续出现只显示一次。
2. **状态标签「已停止 / 生成失败」**：纯 11.5sp 灰字，和正文同灰度、无图标、无底色，容易被读成回复的最后一句。建议做徽标：11.5sp/16sp/W600 + `surfaceContainerHigh` 底 + 6dp 圆角 + 8dp 横内距；失败用 `error`，停止用 `onSurfaceVariant`（不要只靠颜色，加 `Icons.Outlined.Info`/`ErrorOutline`）。
3. **长按预览框（[01-retry-dialog.png](TeaTalk/docs/screenshots/m2/01-retry-dialog.png)）**
   - 4 个 TextButton 排一行（重试 / 删除 / 关闭 / 复制），**全部同权重**，看不出哪个是主操作；**危险操作夹在第 2 位**，紧邻"重试"，是误触高危布局。
   - 正文是 `Text(message.content)` → 显示**裸 Markdown**（`**Smoke and Honey**`），而气泡里渲染成粗体，同一个内容两种样子。
   - 建议：正文用 `MarkdownText`（只读、同样式）；按钮收敛为「关闭（dismiss）+ 复制（confirm）」，重试/删除移成对话框内的两行列表项（图标 + 文字 + 删除用 `error`），或用 `DropdownMenu` 承载次要操作。
   - 另外：**复制/重试/删除只有长按一个入口**，无可见入口（违反「关键操作不能只有手势」）。建议在版本切换条右侧加一个 18dp `MoreHoriz`，与长按打开同一个对话框。
4. **会话列表预览显示裸 Markdown**（[06-chat-list.png](TeaTalk/docs/screenshots/m2-conversations/06-chat-list.png)）：`**bold** *italic*`、`## Arrival…` 直接原样呈现。建议在写入 `lastContent` 时或渲染时 strip 掉 `#`/`*`/`` ` ``/`>`，一行 1 次截断。
5. **时间格式跨页不一致**：会话列表同一天显示 `15:17`、日期显示 `01-01`；底部面板统一 `更新于 16:09`；聊天内是 `今天 16:09`。且第二条 `01-01` 明显是 `updatedAt=0`（epoch）被格式化 —— 建议未设置时间时显示空串或「未开始」，并把三种格式收敛成 `今天 HH:mm / 昨天 HH:mm / MM-dd` 三档。
6. **底部面板缺空态、缺滚动**：`conversations.forEach` 放在普通 `Column` 里，没有 `LazyColumn`/`heightIn(max)`，会话多于约 5 条时超出面板高度且**无法滚动**（内容被裁掉）。建议 `LazyColumn` + `rememberModalBottomSheetState(skipPartiallyExpanded = true)` + 空态一行「还没有其它会话，点右上「+」新建」。
7. **聊天列表空态**文案「去「角色」里挑一张卡，点「开始聊天」」仍然准确（M2 点击后会先建会话），保留；但 `chatlist_last_placeholder`（「还没有消息，点开聊一句吧」）在 M2 里已经不会出现（空会话被过滤），可以从文案表里删掉或复用为面板空态。

---

## 7. 逐组件修正清单（可直接排期）

| 优先级 | 组件 | 具体改动 |
|---|---|---|
| **P0** | 代码块 | 新增 `codeSurface`（暗 `#120F0C` / 浅 `#FFFFFF`，1.26:1）+ 1dp `outlineVariant` 描边 + `horizontalScroll` + 内距 12/10 |
| **P0** | 行内代码 | 新增 `codeInlineSurface`（暗 `#4A4038` 1.50:1 / 浅 `#D8CAB6` 1.28:1） |
| **P0** | 全局文本 | 引入 `TeaTalkTypography` / 语义 Text 组件，消灭"只传 fontSize"；优先修 11 / 11.5sp 的 5 处（脚注、状态、版本标签、今天、聊天头副标题） |
| **P0** | 变体切换条 | 去掉 32dp 固定尺寸（→48dp 触控）、与气泡 4dp、与输入条 8dp |
| **P1** | 下拉菜单 | `surfaceContainerHighest` + 1dp 描边 + 图标 + 14.5sp |
| **P1** | 重命名输入框 | 底改 `codeSurface`/`surfaceContainerLowest` 或加 1dp `outlineVariant`（当前 1.06:1 隐形）+ 聚焦时 `primary` 边框 |
| **P1** | 来源脚注 | 12.5/18、去 α、`widthIn(max=268dp)`、与气泡 4dp、同 Provider 去重、文案缩短 |
| **P1** | 长按预览框 | 正文改 `MarkdownText`；按钮收敛为「关闭+复制」；重试/删除改成列表项；补 `MoreHoriz` 可见入口 |
| **P1** | 底部面板 | `LazyColumn` + 最大高度 + 空态；标题 17/24/W700；行名 15.5/22/W600；副标题 12.5/18；删除 `error` 色 + 分隔；Check 预留槽位 + `selected` 语义 |
| **P1** | Markdown 标题 | H1 17/22/W700、H2 15.5/22/W700、H3–H6 14.5/22/W600；标题前 12dp 后 4dp |
| **P2** | 引用 | 竖条 2dp `outline`@55%（2.36:1）+ 前后 8dp |
| **P2** | 列表 | 圆点改 12dp 槽位 + 与首行基线对齐（给 `lineHeight` 或画 4dp 圆点）；项间 4dp |
| **P2** | Hairline | 0.6dp→1dp；`outlineVariant`×0.7 → 1.48:1 提到 ≥1.6:1 |
| **P2** | 输入条 | 禁用发送降到 38% 强调；表情图标要么可点要么去掉；两端圆 40/48 统一 |
| **P2** | 气泡 | 圆角 16 → 18dp（令牌值，`tokens.md` §3 已写 18）；内距 13/10 → 14/12 |
| **P2** | 对话框 | 标题统一 17/24/W700（消除 16 vs 24 双体系）；正文 14.5/22 |
| **P2** | 会话列表 | 预览 strip Markdown；`updatedAt=0` 不出 `01-01`；行标题 15.5/22/W600 |
| **P2** | 文档 | [tokens.md](TeaTalk/docs/design/tokens.md#L23-L43) 回填 Theme.kt 实际值，并补齐：Markdown H1–H6、对话框标题、脚注/徽标、`codeSurface`、`codeInlineSurface`、气泡 18dp |

---

## 8. 做得好的地方（别改）

- 文字对比度全线 AAA，用户气泡 7.49:1 尤其稳；`onPrimary` 在暗色下用深色字是对的。
- 变体标签 `semantics(mergeDescendants) { contentDescription = label }` 把「版本 2/2 · 已停止」合成一句读给读屏，这个处理很到位。
- 长按预览解决了气泡 268dp 截断问题，思路正确（只差可见入口和 Markdown 一致性）。
- 会话行显示「更新于 HH:mm」，让"哪条是活跃会话"有了除高亮以外的第二信号。

## 9. 本次未覆盖、建议补图

- 浅色模式下的 M2 新组件（仅有 [01-retry-dialog.png](TeaTalk/docs/screenshots/m2/01-retry-dialog.png)）：代码块 1.09:1、行内代码 1.03:1 在浅色下同样是缺陷，需要一张浅色 Markdown 图确认。
- 重命名对话框（`03-renamed.png` 是面板结果，不是对话框本身）——上面关于"输入框隐形"的结论来自代码取值推算（≈1.06:1），需要一张实图确认。
- 大字号（fontScale 1.3/2.0）与横屏：当前所有字号都是 sp，行高却被 24sp 钉死，放大字号后行距比例会进一步压缩，建议单独测一轮。

