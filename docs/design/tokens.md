# TeaTalk 视觉令牌 v1（A「奶油茶」定稿）

> 状态：v1.0 · 2026-10-25 · 依据 `docs/design/samples/01-3palettes-5screens.png` 中的 **A 奶油茶** 列，用户已拍板（m02249）。
> 落地位置：`app/src/main/java/com/teatalk/xs/ui/theme/Theme.kt`（`CreamColors` / `CreamDarkColors`）。
> 规则：**业务代码一律用 MaterialTheme 语义色，不写十六进制色**；新增颜色先进本表。

## 1. 色彩（浅色 / 暗色）

| 语义 | 浅色 cream | 暗色 cream-night | 用途 |
|---|---|---|---|
| primary | `#8C5A33` | `#E8C29A` | 主按钮、选中态、链接、头像 fallback 字 |
| onPrimary | `#FFFFFF` | `#3A2414` | 主按钮文字 |
| primaryContainer | `#F6E7D3` | `#4A321D` | 选中 chip、次级按钮底、图标底 |
| onPrimaryContainer | `#5C3A1C` | `#FFE2C2` | 选中 chip 文字 |
| background | `#FBF7F0` | `#17130F` | 页面底色 |
| onBackground | `#2B241D` | `#EDE2D6` | 主文字 |
| surface | `#FFFDF9` | `#1D1813` | 卡片/分组底色 |
| onSurface | `#2B241D` | `#EDE2D6` | 卡片主文字 |
| surfaceVariant | `#F3ECE1` | `#26201A` | 输入框底、搜索框底 |
| onSurfaceVariant | `#7B7167` | `#B8AA9B` | 次要文字、时间戳、副标题 |
| surfaceContainer(Highest) | `#F5EEE3` / `#F1E9DD` | `#241E18` / `#2A231C` | 气泡底（AI）、分组底、卡片按下态 |
| outline | `#E6DDD0` | `#3A2F26` | 1dp 分隔线、描边按钮边 |
| outlineVariant | `#F0E8DC` | `#2C241D` | 极淡分隔线 |
| secondaryContainer | `#3F5D58` | `#27403B` | 仅暗色用（气泡/标签底色） |
| error | `#B3261E` | `#F2B8B5` | 删除、失败 |

**语义色使用约定**
- 用户气泡底 = `primary`，字 = `onPrimary`（暗色下 `onPrimary` 是深色字，已核对对比度）。
- AI 气泡底 = `surfaceContainerHigh`，字 = `onSurface`。
- 在线点：`#4A7A5A`（暗色 `#7FB28F`）；中性点用 `outline`。
- 封面渐变遮罩：底 `#E6000000`→透明（名字压在白/彩图上才够对比）；详情页顶部另有 42% 黑到透明的状态栏保护层。

## 2. 字体

| 角色 | 字号/行高/字重 |
|---|---|
| 大标题（Tab 页顶部，如「角色」「会话」）| 22sp / 28 / W700 |
| 页面内标题（角色名、分组标题）| 17sp / 24 / W700 |
| 列表主文字（会话名、设置项）| 15.5sp / 22 / W600 |
| 正文/气泡 | 14.5sp / 22 / W400 |
| 次要文字（时间、副标题、作者、byline）| 12.5sp / 18 / W400，`onSurfaceVariant` |
| 标签/chip | 11.5sp / 14 / W600 |
| 数字（计数、时间）| `FontFeatureSetting("tnum")` 等宽 |

不内置字体（用户拍板），走系统默认 + 中西文混排；不做自定义字体加载。

## 3. 间距与圆角

| 项 | 值 |
|---|---|
| 屏幕左右边距 | 16dp |
| 卡片间距（网格/分组之间）| 12dp |
| 分组内行高 | ≥64dp，行内上下 12–14dp |
| 分组卡片内圆角 | 18dp；分组外底色 `surface` |
| 封面/头像圆角 | 网格卡 16dp，列表头像 12dp，详情封面 0（全宽） |
| 按钮圆角 | 主按钮 24dp（高 46dp），小图标按钮圆形 |
| 气泡圆角 | 18dp，同侧连续两条时同侧圆角 6dp |
| 分隔线 | 1dp `outlineVariant`，只在行内（不顶到边）|
| 底部导航栏 | 高 64dp + 系统手势区，选中指示器为 28dp 药丸 |

## 4. 动效

- 页面切换：默认 Compose 650ms fade/scale（保留系统默认，不自定义花哨转场）。
- 列表项按下：`surfaceContainerHigh` 叠 1 层，无缩放。
- 发射/落座：不做动画；等 M1 流式输出时给「打字中」三点动画（预留）。

## 5. 与样张的差异（已知、待下轮统一）

1. 网格卡占位（JSON 卡无封面）当前用渐变底 + 首字，标签「角色」底色偏淡、对比不足 → 待加深。
2. 会话页未做「置顶/免打扰/未读数」标记（数据层没有这些字段，M2 再加）。
3. 角色页筛选项「全部/收藏」目前只有样式、无筛选逻辑（M2 补）。
4. QQ 风格的分组标题（会话页「会话」小标题）已用，但未做「折叠」交互。

## 6. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-10-25 | v1.0 | 由 3 配色样张定为 A 奶油茶；app 五屏首次对齐 |
