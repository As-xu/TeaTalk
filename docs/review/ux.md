# TeaTalk M0 界面与交互审阅报告

- **审阅对象**：`com.teatalk.xs` 0.1.0 debug（`dist/TeaTalk-0.1.0-debug.apk`，未曾重装，使用设备上现有安装）
- **审阅设备**：`emulator-5554`（Android 14 / API 34，1080×2340，440dpi）——**全程只操作模拟器**，未触碰真机 `PQY0221205009539`
- **审阅方式**：逐项实操（adb `input` / `screencap` / `settings` / `svc`），配合源码核对（`app/src/main/java/com/teatalk/xs/ui/**`），未改动任何源码或 git 状态
- **日期**：2026-10-24
- **证据目录**：`TeaTalk/docs/review/ux-shots/`（41 张，命名 `NN-页面-状态.png`）
- **对照基准**：`docs/00-技术方案与演进路线.md` §3（UI/UX）、§4（信息架构）；目标审美＝现代手机 App（QQ / 微信）

---

## 1. 一句话总评与差距评级

**一句话总评**：M0 的「壳」已经站在正确的一边——统一暗色、克制的茶青点缀、大标题 + 3 列网格 + 单列气泡的骨架都像 2024 年的手机 App，但「骨」还停在原型阶段：启动落错 Tab 并先闪一帧空态、聊天顶栏在大字号下直接压到消息上、删除入口藏在长按里且不可撤销——这三件事会让用户在第一分钟就怀疑「它是不是把我的卡弄丢了」。

**与 QQ/微信式目标的差距评级**

| 维度 | 现状 | 评级 | 说明 |
|---|---|---|---|
| 视觉基调（配色/圆角/密度） | 暗色统一、primary 点缀克制、卡片 16dp / 气泡 18dp / 输入框 22dp 成体系 | **B** | 不土，但缺 QQ/微信那种「信息有层次」的细节（无分割线、无时间戳、无二级信息区） |
| 信息架构 | 3 Tab 命名清楚，但启动落点与方案 §3.1 相反；Tab 切换进了返回栈 | **C+** | 骨架对，决策错 |
| 交互完成度 | 主流程可走通（导入→开聊→发消息→返回），但删除无提示、列表项点击无反馈、无复制/重生成 | **C** | M0 可接受，M1 必须补 |
| 健壮性 | 大字号下聊天顶栏崩、横屏无宽度约束、冷启动闪空态 | **C-** | 这三处是「一放开适配就露馅」的典型 |
| **综合** | — | **B-（≈ QQ/微信的 70%）** | 视觉已接近，交互与 IA 差一个明显台阶 |

**做对了的地方（建议保留）**
- 启动无白闪：`windowSplashScreenBackground` = 主题背景 `#101413`，叶子图标居中（`01-splash.png`）。
- 详情页/聊天页全屏隐藏底栏，符合 QQ 习惯（`16-chat-open.png`）。
- `imePadding()` 生效：键盘弹出时输入框上移、列表不被压成 0 高（`17-chat-keyboard.png`）。
- 删除角色卡的级联正确：删卡后会话列表无残留（`38-delete-card-with-chat.png` → `39-chats-after-delete.png`）。
- 从会话列表进聊天、返回后列表数据刷新正确（`27-chat-from-list.png` → `28-chat-back-to-list.png`）。
- 双击「开始聊天」不会重复入栈（`29-doubletap-startchat.png` → `30-after-back-once-detail.png`，一次返回即回详情页）。
- 大字号（1.3×）下网格与详情页仍可读（`31-font13-detail.png`、`32-font13-grid.png`）。

---

## 2. Findings

> 严重度：**P0** 阻断体验（用不了）｜**P1** 明显问题（用户会抱怨/误解）｜**P2** 打磨。
> 本次未发现 P0 级崩溃或流程阻断；最接近 P0 的是 UX-03（误删不可撤销）与 UX-05（大字号顶栏崩坏），均按 P1 处理。

| 编号 | 严重度 | 页面 | 问题 | 证据 | 建议 |
|---|---|---|---|---|---|
| UX-01 | P1 | 启动/角色 | **冷启动先渲染「还没有角色卡」空态约 0.4s，再翻出真实卡片**（库里有 8 张卡）。用户会以为数据丢了 | `02-coldstart-empty-flash.png`（~1.75s）→ `03-coldstart-landing-characters.png`（~2.0s，8 张卡）；`am force-stop` + `am start` 复现 | `characters` 状态用 `null` 表示 loading，首帧渲染骨架/占位而非 `EmptyCharacters`；或把 `when(characters.isEmpty())` 改为 `when(characters == null) -> Loading` |
| UX-02 | P1 | 启动 | **冷启动永远落在「角色」Tab**，与方案 §3.1「有历史会话 → 直接进『聊天』」冲突；也不符合「打开看消息」的直觉 | `03-coldstart-landing-characters.png`（已有 1 会话仍落角色）；源码 `ui/HomeScreen.kt:78 startDestination = Routes.CHARACTERS` | 启动时读 `chats` 是否为空决定 `startDestination`：有会话 → `Routes.CHATS`；首次使用 → 方案里的 3 屏引导 |
| UX-03 | P1 | 角色列表 | **删除唯一入口是长按，无任何视觉提示**；确认弹窗「删除」与「取消」同为 primary 色，且**无撤销**，删卡会连带删光聊天记录 | `10-delete-confirm.png`；源码 `combinedClickable(onLongClick = { confirmDelete = true })` 是唯一入口 | 卡片右下角加「⋯」（详情/编辑/删除）；删除成功后 snackbar 给「撤销」5s；确认弹窗删除键改 `error` 色 |
| UX-04 | P1 | 我的 | 「模型与接口」**可点但完全无反应**，也没有「即将上线」标注（`onOpenModelSettings = {}` 空实现） | `25-profile-dead-tap.png`（点击后画面无任何变化） | 行尾加 `›` + 右侧灰字「M1 即将上线」，或 `enabled=false` + 40% 透明；点击给 toast「模型接入将在 M1 开放」 |
| UX-05 | P1 | 聊天 | **字体放大到 1.3× 时顶栏崩坏**：固定高度 `TopAppBar` 装不下「2 行角色名 + 离线示例模式」，副标题被绘制到顶栏之外，被第一条气泡压住/重叠 | `33-font13-chat-overflow.png`（「离线示例模式」与开场白气泡重叠） | 角色名 `maxLines = 1` + `TextOverflow.Ellipsis`（长名不换行）；「离线示例模式」移出标题列（改为 action 区 chip 或首条系统提示）；标题列用 `heightIn(min = 64.dp)` 自适应 |
| UX-06 | P1 | 角色详情 | **正文原样输出 SillyTavern 原始标记**：`[Seraphina's Personality= "caring"...]`、`<START>`、`{{user}}:`、`{{char}}:`、`*动作*`，像调试视图而非产品界面 | `11-detail-seraphina.png`（设定）、`14-detail-longtext-bottom.png`（示例对话里的 `<START>`） | 渲染前清洗：去掉 `<START>`；`{{user}}/{{char}}` 替换为「你/角色名」；`*…*` 用斜体 + 次色；或给「原文/美化」切换 |
| UX-07 | P2 | 角色列表 | **导入有双入口**（顶栏「+ 导入」与右下 FAB）地位对等，用户会犹豫点哪个 | `05-characters-grid9.png` | 只留 FAB（主操作），顶栏右侧改为搜索图标（M1 需求） |
| UX-08 | P2 | 角色详情 | byline 直接显示内部枚举：`作者 OtisAlejandro · v1.0.0 · PNG_V3` | `11-detail-seraphina.png`、`13-detail-longtext-top.png`（`JSON_V2`） | 映射成「PNG 卡 / JSON 卡 / CHARX」，或只保留「作者 · 版本」 |
| UX-09 | P2 | 角色详情 | **超长正文无折叠、无章节跳转**：2k 字「设定」占满 2 屏，开场白/示例对话被推到很远 | `13-detail-longtext-top.png` → `14-detail-longtext-bottom.png` | 每段默认 `maxLines = 6` + 「展开」；顶部加「设定 / 人设 / 场景 / 开场白」章节 chip，点击滚动定位 |
| UX-10 | P2 | 世界书弹窗 | **只显示 comment，关键词（keys）从不显示**，用户判断不了条目何时触发；正文 3 行**硬截断且无省略号**（如 `…as she offers you a`）；条目不可展开 | `12-detail-worldbook-nokeys.png`（Seraphina 4 条）、`15-detail-worldbook-comment-only.png`（爱丽丝卡里有 keys「书/雨/壁炉」但界面不显示） | 标题行右侧显示 keys 前 3 个 chip；正文加 `overflow = TextOverflow.Ellipsis`；条目可点展开 |
| UX-11 | P2 | 角色列表 | **emoji 首字占位被裁断**：`🧋 Tea 测试` 的头像渲染成「茶青菱形 + 问号豆腐块」 | `05-characters-grid9.png`（第 2 张卡）；源码 `CharacterAvatar` 用 `name.take(1)` 切断代理对 | 用 `name.offsetByCodePoints` / 正则取首个 grapheme cluster，或直接显示 `🧋` 整字 |
| UX-12 | P2 | 导入 | SAF 用 `*/*`，**任意文件都能选**（json、70MB 垃圾文件均可）；错误文案技术化：「PNG 里没有 chara / ccv3 元数据，不是角色卡」「角色卡 JSON 解析失败」，没有「怎么办」 | `06-import-picker.png`、`07-import-err-json.png`、`08-import-err-png.png`、`09-import-err-toobig.png` | 文案改「这看起来不是 SillyTavern 角色卡（缺少角色卡数据）。支持 PNG / JSON / CHARX」+ 「换一个文件」按钮；选择器可保留 `*/*`，选后按扩展名快速判型 |
| UX-13 | P2 | 全局反馈 | Snackbar 用 M3 默认 `inverseSurface`（**近白底 `#E2E8E5`**），在暗色 App 里非常刺眼；且排队串行（连导多个文件时后一条要等前一条 4s） | `07-import-err-json.png`、`08-import-err-png.png`、`09-import-err-toobig.png` | 自定义 `SnackbarHost`：`surfaceContainerHigh` 底 + primary 图标；错误用 `errorContainer`；成功提示不排队（替换而非叠加） |
| UX-14 | P2 | 导入 | 成功提示「已导入「卡六」（8 条兼容提示）」——小卡也报 8 条，像出错了 | `05-characters-grid9.png` | 成功只报「已导入『X』」；兼容提示收进详情页折叠区或日志 |
| UX-15 | P2 | 角色列表 | 矮屏/多卡时 **FAB 直接压住卡片头像**，网格底部没有避让 padding | `40-shortscreen-fab-overlap.png`（1080×1400 矮屏，FAB 盖住「阿」卡） | `LazyVerticalGrid` 的 `contentPadding.bottom ≥ 96.dp`，或 FAB 收进底栏中央 |
| UX-16 | P2 | 聊天 | 消息**无时间戳、无头像、长按无菜单**（不能复制/引用/删除）；也无「重新生成 / 停止」 | `19-chat-after-send.png`、`20-chat-longpress-none.png` | 时间跨度 >12h 插入居中时间分隔；长按弹底部菜单（复制/引用/删除）；M1 补重新生成与停止 |
| UX-17 | P2 | 聊天 | 离线占位回复把**用户全文回显**并带内部文案「（离线示例回复）…接上模型后…」，还会出现 `。」。` 双标点 | `19-chat-after-send.png`、`21-chat-back-to-detail.png` | 占位回复收短：「（示例）接入模型后，我会真正回应你」；M1 换真实生成 |
| UX-18 | P2 | 聊天 | 「离线示例模式」语义不明：用户不知道这是什么、怎么退出 | `16-chat-open.png` | 改成可点 chip「示例模式 · 去配置模型」→ 跳「我的 > 模型与接口」 |
| UX-19 | P2 | 会话列表 | **名字被两个 `weight(1f)` 对半挤**，长名截成「爱丽丝·冯·霍恩…」而右侧却留大片空白 | `22-chats-list-updated.png` | 名字 `weight(1f)`、时间固定宽度；即 `Text(name, Modifier.weight(1f))` + `Text(time)`，去掉中间的 `Spacer(weight(1f))` |
| UX-20 | P2 | 会话列表 | 预览露出内部文案「（离线示例回复）…」；**行间无分割线**；无未读/置顶 | `22-chats-list-updated.png` | 预览取正文并去掉前缀；行间加 1px 分割线或 8dp 间距；M1 补未读点 |
| UX-21 | P2 | 全局 | **底部 Tab 切换被压入返回栈**：在「我的」按返回会回到「角色」Tab，而不是退出（与 QQ/微信「返回＝退出到桌面」不一致） | `26-profile-back-to-characters.png` | Tab 切换不入栈（各 Tab 独立 back stack），返回键在根 Tab 直接 finish |
| UX-22 | P2 | 全局 | **横屏未锁定且无宽度约束**：`开始聊天` 按钮被 `weight(1f)` 拉成 ~1600px、气泡行宽 >100 字符、正文行宽过长 | `34-landscape-chat.png`、`35-landscape-detail-widebutton.png` | M0 直接 `android:screenOrientation="portrait"`；M1 做横屏时给内容 `widthIn(max = 600.dp)` 居中 |
| UX-23 | P2 | 全局 | 系统切浅色后 **App 仍全暗**，无「跟随系统 / 深 / 浅」设置（LightColors 已定义但没接线） | `36-system-light-app-still-dark.png`（与暗色截图同 sha） | 符合 M0「固定暗色」决策，但 M1 需补三态；`TeaTalkTheme(darkTheme = isSystemInDarkTheme())` 一行即可 |
| UX-24 | P2 | 我的 | 无开源许可 / 源码入口；「关于 TeaTalk」不可点 | `24-profile.png` | 「关于」→ 二级页：版本、AGPL-3.0 全文、GitHub、第三方许可 |
| UX-25 | P2 | 全局图标 | 图标族混用：底栏是 Material Symbols 风格（实心、几何），内容区 `ArrowBack / Send / Settings / Info / Add` 是 Material Icons（箭头头部角度、粗细不同） | `16-chat-open.png`（←）与 `24-profile.png`（⚙ / ⓘ）对比 | 统一 Material Symbols Rounded，24dp / 同一 stroke 权重 |
| UX-26 | P2 | 导航心智 | 从「角色详情」开聊后按返回**回到详情页**而不是会话列表；从会话列表进聊天返回才回列表（两条路径返回落点不同） | `21-chat-back-to-detail.png` vs `28-chat-back-to-list.png` | 明确一种心智：开聊＝进入会话，返回回来源；或在聊天顶栏加「会话列表」入口 |
| UX-27 | P2 | 导入 | 断网/无网络时全 App 无任何提示（M0 无网络功能，属预期） | `37-offline-chats.png` | M1 接模型后必须补：无网 banner、发送失败态 + 重试、超时文案 |

---

## 3. 与 QQ / 微信差距 Top 5

1. **「打开就到消息」的第一屏**。微信/QQ 冷启动直接落在会话列表且首帧就是内容；TeaTalk 落在「角色」Tab，还要先闪 0.4s「还没有角色卡」。（UX-01 / UX-02）
2. **列表项的信息节奏**。微信会话行是「头像 ▸ 名字（能占满）▸ 时间 ▸ 预览 + 分割线」，TeaTalk 名字被两个权重对半挤到 40% 宽、没有分割线、预览里混进内部占位文案。（UX-19 / UX-20）
3. **长按/更多操作的统一语言**。QQ/微信里「长按消息 → 复制/引用/删除」「条目右侧长按或左滑 → 删除」是肌肉记忆；TeaTalk 长按卡片是删除唯一入口且零提示，长按消息完全无反应。（UX-03 / UX-16）
4. **聊天阅读的「可读性装置」**。时间戳、头像、发送状态、重新生成、选中复制——这些不是装饰，是聊天页的骨架。TeaTalk 目前只有气泡本身。（UX-16 / UX-17）
5. **大字号 / 横屏 / 深色跟随系统这类「设备契约」**。头部三大厂 App 在 1.3× 字号下几乎不会崩，TeaTalk 的聊天顶栏直接与消息重叠；横屏按钮被拉成 1600px。（UX-05 / UX-22 / UX-23）

---

## 4. M1 前必修 3 件事 / M1 可后置

### M1 前必修（3 件）

1. **启动体验归位**：按「有无历史会话」决定落点 + 干掉空态闪烁（UX-01、UX-02）。
   *理由*：这是用户对「界面不美 / 数据是不是丢了」的第一判断点，改动小（一个 `startDestination` + 一个 `null` loading 态），收益最大。
2. **聊天页顶栏自适应 + 消息操作骨架**（UX-05、UX-16）。
   *理由*：聊天是唯一的核心场景；顶栏在大字号/长角色名下崩坏属于可及性阻断，而长按复制/时间戳是「像不像微信」的最低门槛。
3. **删除的可发现性 +「模型与接口」死链**（UX-03、UX-04）。
   *理由*：一个会造成不可逆数据丢失（且无撤销），一个会让用户反复点并得出「这 App 没做完」的结论。两处都只需几十行改动。

### M1 可后置（按性价比排序）

- UX-06 正文清洗/排版（ST 标记）——观感提升大，但需要解析层改造，放 M1 中段。
- UX-09 长文折叠 + 章节锚点；UX-10 世界书显示 keys / 可展开。
- UX-12 / UX-13 / UX-14 导入文案与 Snackbar 主题（一次性样式统一）。
- UX-19 / UX-20 会话列表细节（权重、分割线、预览去前缀）。
- UX-21 Tab 返回栈；UX-22 横屏锁定；UX-23 跟随系统；UX-24 许可页；UX-25 图标族统一。
- UX-07 双入口合并、UX-15 FAB 避让、UX-11 emoji 占位、UX-26 返回心智——都可在 M1 打磨期批量处理。

---

## 5. 附录：设备操作与还原情况

**只使用 `emulator-5554`；未对真机 `PQY0221205009539` 执行任何命令。**

| 操作 | 命令（`adb -s emulator-5554 shell ...`） | 已还原 | 还原后验证 |
|---|---|---|---|
| 字体放大 1.3× | `settings put system font_scale 1.3` | ✅ | `font_scale = 1.0` |
| 强制横屏 | `settings put system accelerometer_rotation 0` + `user_rotation 1` | ✅ | `user_rotation = 0`，`accelerometer_rotation = 1` |
| 系统浅色 | `cmd uimode night no` | ✅ | `cmd uimode night` → `Night mode: yes` |
| 断网 | `svc wifi disable; svc data disable` | ✅ | `wifi_on = 1`，`mobile_data = 1` |
| 矮屏模拟（测网格/FAB 遮挡） | `wm size 1080x1400` | ✅ | `wm size` → `Physical size: 1080x2340`，`density 440` |
| 冷启动复现 | `am force-stop com.teatalk.xs` + `am start -n com.teatalk.xs/.MainActivity` | 无需还原 | App 已回到主界面（`41-final-main.png`） |
| 测试文件注入 | `adb push` 到 `/sdcard/Download/`：`bad_200b.png`、`bad.json`、`huge_70mb.png`(73,400,320B)、`long_cn_name.json`、`nova.json`、`c1..c6.json` | 保留在模拟器 Download（如需清理可删） | 未影响 App 数据以外的系统状态 |
| App 数据操作 | 通过 UI 导入 8 张卡；删除测试卡「卡六」与「爱丽丝」各一次（均为自造测试卡，非用户数据） | — | 当前 8 张卡、1 个会话（Seraphina，3 条消息） |

**未执行/未能验证项（诚实记录）**

- **导入中断（SAF 结果丢失）**：本地文件导入 70MB 也在 ~1s 内结束（`MAX_CARD_BYTES` 64MB 提前中止），没有可稳定打断的窗口，**未能复现**。风险提示：导入跑在 `characters` 的 `viewModelScope` 上，若用户在导入中退出页面/退出 App，协程会被取消且静默丢弃——M1 建议改成可恢复的后台任务 + 结果通知。
- **网格滚动性能与滚动边界**：现有 8 张卡在一屏内不产生滚动，只能通过 `wm size 1080x1400` 矮屏间接观察（发现 FAB 遮挡，见 UX-15），未做长列表 FPS 采样。
- **未读/置顶/搜索/收藏**：属于 M1 功能，M0 不存在，无法验证。
- **uiautomator dump** 在会话中途开始持续返回 0 字节（重启 uiautomator 无效），后续坐标全部按截图 1080/872 比例换算，已核对点击均命中预期控件。

**截图索引（`docs/review/ux-shots/`）**

`01-splash` 闪屏 · `02-coldstart-empty-flash` 冷启动闪空态 · `03-coldstart-landing-characters` 落点 · `04-characters-empty` 空态 · `05-characters-grid9` 9 卡网格 · `06-import-picker` SAF · `07/08/09-import-err-*` 坏 JSON/坏 PNG/超大文件 · `10-delete-confirm` 长按删除 · `11-detail-seraphina` 详情 · `12-detail-worldbook-nokeys` · `13/14-detail-longtext-*` 长文上下屏 · `15-detail-worldbook-comment-only` · `16-chat-open` · `17-chat-keyboard` · `18-chat-typing-multiline` · `19-chat-after-send` · `20-chat-longpress-none` · `21-chat-back-to-detail` · `22-chats-list-updated` · `23-chats-empty` · `24-profile` · `25-profile-dead-tap` · `26-profile-back-to-characters` · `27-chat-from-list` · `28-chat-back-to-list` · `29-doubletap-startchat` · `30-after-back-once-detail` · `31/32-font13-*` 大字号 · `33-font13-chat-overflow` 顶栏崩坏 · `34/35-landscape-*` 横屏 · `36-system-light-app-still-dark` · `37-offline-chats` · `38-delete-card-with-chat` · `39-chats-after-delete` · `40-shortscreen-fab-overlap` · `41-final-main` 还原后主界面
