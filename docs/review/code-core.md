# TeaTalk `:core` 模块代码审阅（M0 收口）

- 审阅对象：`D:\Project\TeaTalkApp\TeaTalk\core\src\main\kotlin\com\teatalk\core\`（`card/`、`worldinfo/`）与 `core/src/test/kotlin/`，App 仓库 HEAD `8f6bdd4`。
- 对照实现：`D:\Project\TeaTalkApp\SillyTavern`（HEAD `06bde939f`）的 `src/character-card-parser.js`、`src/validator/TavernCardValidator.js`、`public/scripts/world-info.js`、`src/endpoints/characters.js`、`src/charx.js`。
- 方法：逐行阅读上述源码 + 21 个单测；用独立 Java 探针直接调用 `:core` 的已编译类验证边界行为（探针在 `D:\Project\TeaTalkApp\.tmp\`，未入库、未改动仓库）；强制重跑单测。
- 结论只针对 `:core`；UI/App 侧解析（如 `CardViewParser.kt`）不在本次范围。

## 1 结论

- 主干路径（PNG tEXt 读取、`ccv3` 优先、V1/V2/V3 识别、CHARX 取 `card.json`、未知字段进 `raw`）方向正确，代码短、无共享可变状态，线程安全性上没有发现问题。
- 但**世界书与 ST 实际导出格式不一致**：ST 把 `position/depth/role/probability/group/case_sensitive/...` 写在 `character_book.entries[].extensions` 里，`:core` 只读顶层同名字段，导致真实 ST 卡的这些语义全部丢失或误读（P1-02）；同时把 `use_regex` 当真（ST 在 `character_book` 上忽略它，P1-03）。
- 健壮性有两个可复现的硬问题：PNG chunk 长度整数溢出直接抛 `ArrayIndexOutOfBoundsException`（P1-04），ZIP 条目 `readBytes()` 无大小上限、可被压缩炸弹打爆内存（P1-01）。
- 现有单测 21 个全绿，但真实卡 `default_Seraphina.png` 的 `extensions.*` 恰好等于默认值，**掩盖了 P1-02**；`use_regex`、数字类型、uid、告警语义等均无用例。
- 总体评级：**需先修**。修掉 4 个 P1（世界书 `extensions` 映射、`use_regex`、PNG 溢出、ZIP 上限）并补第 3 节前 4 组测试后，可进 M1；P2 可排入 M1 迭代。P0：无。

## 2 Findings

### P1（应修，M1 前）

| 编号 | 严重度 | 位置 file:line | 问题 | 证据/复现 | 修复建议 |
|---|---|---|---|---|---|
| C-01 | P1 | `core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:57`（另 `PngTextReader.kt:76`、`CardParser.kt:75`） | CHARX 解压单条目用 `zip.readBytes()`，无解压大小上限、无总大小上限；`ZipEntry.size` 也不检查。恶意/损坏 CHARX 可声明超大解压尺寸，触发 OOM（`OutOfMemoryError`，连 `catch (e: Exception)` 都接不住）。PNG `tEXt` 与整段 JSON 同样只受文件本身大小约束，没有显式上限与早退。 | 代码路径 `parseCharx()` 第 52–68 行无 `try/catch`、无 `ZipEntry` 尺寸判断；`readBytes()` 一次性分配。构造 50MB→5GB 的 deflate 条目即可复现（未实际打爆本机，按 JDK `ZipInputStream` 语义必现）。 | 加 `MAX_ENTRY_BYTES`（建议 8–16 MiB）：先 `zipEntry.size` 预检（>0 且超限即拒），再循环 `read(buf)` 累计计数，超限抛 `CardImportException`；同时限制 ZIP 条目数与 `card.json` 解码后字符数（如 16 MiB）。整个 `ZipInputStream` 用 `use {}` 并 `catch (e: IOException) { throw CardImportException("CHARX 压缩包损坏", e) }`。 |
| C-02 | P1 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:69-92`（`normalize()` 第 81–90 行） | V2 `character_book.entries[]` 归一化只读**顶层** `depth/role/probability/use_probability/case_sensitive/match_whole_words/use_regex/group`，完全不读 `entry.extensions` 内同名字段。ST 的 `convertCharacterBook()`（`world-info.js:5617-5674`）是 `extensions` 优先、顶层兜底；`convertWorldInfoToCharacterBook()`（`characters.js:663-740`）正是把真实值写进 `extensions`，顶层 `position` 只写 `before_char/after_char`。结果：所有 ST 导出的卡在世界书行为上被静默改写。 | 构造 ST 导出形态（`extensions` 有值、顶层为 `after_char`）：`position=1`（应 4）、`depth=4`（应 2）、`role=0`（应 1）、`probability=100`（应 50）、`caseSensitive=false`（应 true）、`group=''`（应 `g`），`sticky/cooldown` 完全丢失。真实卡 `default_Seraphina.png` 的 4 条 entries 同样带 `extensions{depth:4,role:0,probability:100,...}`，只是值恰等于默认值，所以现有测试看不出。 | 按 ST 顺序实现：`position = extensions.position ?? (position=="before_char" ? 0 : 1)`；`depth/role/probability/useProbability/group/caseSensitive/matchWholeWords/sticky/cooldown/delay` 一律 `extensions` 优先、顶层兜底；`useProbability` 缺省 true、`probability` 缺省 100。建议把 `parseCharacterBook()`（V2 形态，extensions 优先）与 `parseWorldInfoFile()`（ST world-info 文件，顶层字段 + 对象 key 为 uid）分成两套读取逻辑，避免两套语义互相污染。 |
| C-03 | P1 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:85`（读取）、`:182`（编译） | 对 `character_book` 条目套用 `useRegex`：`entry["use_regex"]` 为 true 时用 `Regex(key)` 匹配。但 ST 的 `convertCharacterBook()` 根本不映射 `use_regex`（已核对 `world-info.js:5617-5674` 无该字段），而 `convertWorldInfoToCharacterBook()` 又**无条件写 `use_regex: true`**，所以 ST 导出的卡（含自带的 `default_Seraphina.png`）都会中招：键按正则解释，含 `(`、`[`、`.`、`+`、反斜杠的键会误匹配或 `runCatching` 后恒不命中；中文键在正则下语义也会变。 | 探针：`use_regex=true`、key=`Alice (maid)` → `matchLiteral("Alice (maid)")=false`，`matchRegex("Alice maid")=true`；真实卡第一条 entries 顶层即 `use_regex:true`、keys `["eldoria","wood",...]`（此卡键恰好正则安全，才没暴露）。 | `parseCharacterBook()` 强制按字面匹配（`Regex.escape` 或直接 `contains`），只有 `parseWorldInfoFile()`（ST world-info 文件）才认 `use_regex`；同时给 `WorldInfoEntry` 增加 `sourceForm` 或把 `useRegex` 默认 false，避免调用方误用。若要保留宽容性，至少对编译失败的正则回退到字面匹配而不是 `runCatching{false}`。 |
| C-04 | P1 | `core/src/main/kotlin/com/teatalk/core/card/PngTextReader.kt:38-41` | chunk 长度越界检查 `dataEnd + 4 > bytes.size` 用 `Int` 相加，`dataEnd = pos + 8 + length`（第 37 行）在 `length` 接近 `Int.MAX_VALUE` 时溢出为负数，随即 `pos = dataEnd + 4` 变负，`readInt`/`copyOfRange` 抛 `ArrayIndexOutOfBoundsException`；该异常不是 `CardImportException`，会直接穿透 `parse()` 的契约。 | 探针：构造 PNG（IHDR + 长度 `0x7FFFFFF8` 的 `tEXt`）→ `java.lang.ArrayIndexOutOfBoundsException: Index -2147483636 out of bounds for length 24`。 | 全程用 `Long`：`val dataStart = pos + 8L; val dataEnd = dataStart + length`；`if (length < 0 || dataEnd > bytes.size - 4) throw CardImportException("PNG chunk 越界")`；`pos = dataEnd + 4L` 再 `toInt()`（前面已保证 ≤ size）。`readTexts` 的 while 循环同时加“最多 N 个 chunk/文件末尾 IEND”保护。 |
| C-05 | P1 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:155` | 激活顺序用 `sortedWith(compareBy(order, uid))` **升序**；ST 是 `sortFn = (a,b) => b.order - a.order` **降序**（`world-info.js:88`，prompt 组装第 5203 行同样用 `sortFn`）。世界书 `order` 的语义在 ST 是“越大越先插入”，TeaTalk 反了；而且 `WorldInfoTest.kt:162` 把升序 `[2, 3]` 固化成断言，等于把错误行为锁死。 | `WorldInfoTest.kt:154-166`：`charBudget=34` 断言 `[2,3]`、预算 1000 断言 `[2,3,1]`；ST 对应应为 `[3,2]` / `[3,2,1]`。 | 改为 `compareByDescending<WorldInfoEntry> { it.order }.thenBy { it.uid }`，同步修正 `WorldInfoTest.kt:162/165`，并在 `activated()` KDoc 写明“返回顺序即 ST 注入顺序（order 降序）”。 |
| C-06 | P1 | `core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:146-156` | V1→V2 升级映射不完整：未映射 `creatorcomment ?? creator_notes`、`creator`、`talkativeness`（ST 把它放进 `data.extensions.talkativeness`）。ST 的 V1 导入分支（`characters.js:905-945`）与 `charaFormatData()`（`characters.js:564-660`）明确读取这三个字段。 | 探针：`{"name":"老卡","creatorcomment":"作者备注","creator":"me","talkativeness":0.8,...}` → `creatorNotes=''`、`creator=''`，`creatorcomment` 只在 `raw` 里。注意：ST 的 V1 导入同样**不会**保留 `character_book`（已核对 `characters.js:905-945`），所以不要把 `character_book` 当差异。 | `legacyFlatToData()` 补：`creatorNotes = str("creator_notes") ?: str("creatorcomment")`；`creator = str("creator")`；`talkativeness` 写入 `extensions`（若 `extensions` 缺失则新建，保留原对象内容）；`character_version = str("character_version")`（ST V1 不映射，可留空但建议补）。不要为了“保留”而把未知根字段塞进 `data`。 |

### P2（建议，M1 迭代）

| 编号 | 严重度 | 位置 file:line | 问题 | 证据/复现 | 修复建议 |
|---|---|---|---|---|---|
| C-07 | P2 | `core/src/main/kotlin/com/teatalk/core/card/PngTextReader.kt:59` | base64 用严格 `Base64.getDecoder()`，只 `trim()`，不处理内嵌换行/空白；ST 用 `Buffer.from(text,'base64').toString('utf8')` 是宽松解码。合法 ST 卡里被折行的 base64 会被 TeaTalk 拒掉（“不是合法 base64”）。另外 `ccv3` 存在但解码/JSON 失败时直接抛错，不会回退到 `chara`。 | 探针：`chara = "QUJD\nREVG"` → `CardImportException: PNG 的 chara 元数据不是合法 base64`；`Buffer.from("QUJD\nREVG","base64").toString()` 正常得 `ABCDEF`。ST 代码 `character-card-parser.js` 的 `read()` 只做 `Buffer.from(text,'base64')`。 | 解码前 `replace(Regex("\\s"), "")`；失败时依次尝试 `getMimeDecoder()`/URL-safe decoder；`cardJson()` 对 `ccv3` 解析失败时记录 warning 并回退 `chara`（ST 是直接抛，但宽容导入对手机端更合适，且可作为 warnings 用例）。 |
| C-08 | P2 | `core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:103-107` | `spec` 存在但未知（如 `chara_card_v4`、拼写错误）时，走“有 `data` 对象”分支，发出「缺少 spec 字段，按 V2 解析」——事实相反（spec 存在）；随后第 137–142 行按 `isV3` 把 `spec` 重写成 `chara_card_v2`，若 App 回写会静默降级。 | 探针：`spec="chara_card_v4"` + 完整 `data` → `source=JSON_V2`、`spec=chara_card_v2`、warning 文案为「缺少 spec 字段，按 V2 解析」。 | 先判断 `spec` 是否存在：存在但非 v2/v3 → warning「不支持的 spec: X，按 V2 结构尽力解析」且**不要改写**原 `spec`（或写回原值）；只有确实无 `spec` 且无 `data` 时才走 V1 分支/提示。 |
| C-09 | P2 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:125-136`（另 `CardParser.kt:163`） | `intOrNull()` 用 `toIntOrNull()`，拒绝 `1.0`、`2.0` 这类 JSON 浮点数字；`boolOrNull()` 只认字符串 `"true"`/`true`，数字 `0/1` 一律走默认。结果是“看似正常”的条目被静默改成默认值。`CardParser.mapData` 对数字 primitive 不告警（第 163 行只对非 primitive 告警）。 | 探针：`uid:1.0, order:2.0, depth:3.0` → `uid=0, order=100, depth=4`，无 warning。 | `content.toIntOrNull() ?: content.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()`；布尔接受 `0/1`、`"0"/"1"`；对“类型不符已回退默认”增加稳定 warning（带字段名与原值类型）。 |
| C-10 | P2 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:63-67` | `entriesOf()` 对对象形态只取 `values`，丢弃对象 key。ST 的 world-info 文件以对象 key 为 uid（`convertCharacterBook` 里 `uid = entry.id`，world-info 文件条目本身以 key 为 uid），丢失 key 后无 `uid` 字段的条目全部塌成 `uid=0`，排序/去重/引用都会串。 | 探针：`{"entries":{"42":{...},"43":{...}}}`（无 `uid`）→ 两条 `uid` 均为 `0`。 | 对象形态：`entry.uid` 缺省时用 `key.toIntOrNull()` 作为 uid；数组形态保持 `id`/`uid` 回退到 index。补测试断言 uid 唯一。 |
| C-11 | P2 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:49-53` | `fromCard()` 用 `runCatching{}.getOrDefault(emptyList())` 吞掉所有异常，`character_book` 结构错误/序列化失败会静默变成“没有世界书”，调用方无法区分“卡没书”与“书坏了”。 | 静态：`fromCard` 无日志、无 warnings 出口，返回类型只有 `List`。 | 改成 `Result<List<WorldInfoEntry>>`，或抛 `WorldInfoParseException` 并在 `ImportedCard.warnings` 里加一条；至少 `runCatching` 里记录原因。 |
| C-12 | P2 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:158-169` | `activated()` 的预算语义与 ST 不同：ST 按 **token**（上下文百分比）累计，TeaTalk 按 UTF-16 `content.length + 2` 字符数；ST 在预算耗尽时 `break`（`ignoreBudget` 除外），TeaTalk 用 `continue`（当前实现因 `used` 单调不减，效果接近，但语义脆弱）；`probability/use_probability/sticky/cooldown/delay` 解析了却完全不参与。KDoc 已注明概率等是 M0 后续，可以接受，但 `charBudget` 名字容易让调用方误当 token。 | 探针：1000 字条目 + 小条目、`charBudget=10` → 只返回首条 1000 字（首条超预算必须放入，这点与 ST 一致）；CJK 按 1 字符计，而中文 1 字≈1–2 token，实际上下文会超。 | 把参数改名为 `charBudget` 已在做——建议在 KDoc 明确“非 token，M1 换 tokenizer 估算”；`continue` 改 `break` 并注释；`probability` 在 `activated()` 里按 `useProbability` 做确定性/可注入随机（便于测试），`sticky/cooldown` 放到会话状态层。 |
| C-13 | P2 | `core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:188` | `matchWholeWords` 用 `(?<![\p{L}\p{N}])key(?![\p{L}\p{N}])`（Unicode 词字符）；ST 是 `(?:^|\W)key(?:$|\W)`，JS 的 `\W` 只按 ASCII 判定。中文/日文键在中文句子里：ST 会命中，TeaTalk 不命中。 | 已实测：key=`码头`、text=`去码头` → Java lookaround `false`，等价 ST 的 JS 正则 `true`（`node` + `java` 探针，见附录）。 | 直接复刻 ST：`(?:^|\\W)` + `Regex.escape(key)` + `(?:$|\\W)`（注意 Kotlin 字符串转义），不区分大小写时加 `IGNORE_CASE`；或显式决定“TeaTalk 更严格”并写进文档与测试，不要无声明地偏航。 |
| C-14 | P2 | `core/src/main/kotlin/com/teatalk/core/card/StCharacterCard.kt:19` | `@Transient val raw` 与注释“未知字段保留策略：序列化时通过 raw 输出”矛盾：`@Transient` 只对 kotlinx.serialization 生效（该注解来自 `kotlinx.serialization.Transient`），任何 `Json.encodeToString(card)` 都不会输出 `raw`，未知字段全丢。当前 App 用 `raw.toString()` 存盘（已确认 `CharactersRepository` 路径），所以暂无实害，但库的公开承诺是错的。 | 静态：第 19 行 `@Transient`；第 17–18 行注释明确说通过 raw 输出。 | 去掉 `@Transient`（并给 `raw` 默认 `JsonObject(emptyMap())`），或改注释为“raw 仅内存保留，导出请用 `raw.toString()`”，并提供 `toJson()`/`toJsonString()` 显式 API。补一个 `Json.encodeToString(imported)` 的测试锁住行为。 |
| C-15 | P2 | `core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:24-28`、`:132-136` | V3 卡仍用 V2 的 14 个必填字段表告警，不校验 `spec_version` 数值范围（ST validator 对 V3 要求 `Number(spec_version)∈[3,4)`，对 V2 要求严格 `"2.0"`），也没处理 V3 的 `group_only_greetings/assets`。另外 `isV3 = source==CHARX/PNG/JSON_V3`，若 `ccv3` chunk 里实际是 V2 卡，`spec` 会被无条件改写成 `chara_card_v3`。 | 真实卡 warnings 只有“V3 卡：多出的字段…”（提示性文案混进 warnings）；`PngTextReader.cardJson` 只按关键字选 chunk，不校验内容。 | 拆出 `V3_REQUIRED`（至少 `group_only_greetings`）与 `isVersionSupported()`；`isV3` 以 `root["spec"]=="chara_card_v3"` 为准，`source` 只表示容器来源；把“V3 新字段”提示降级为 info 或去掉。 |
| C-16 | P2 | `core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:89-115`、`159-167`；`core/src/main/kotlin/com/teatalk/core/worldinfo/WorldInfo.kt:49-53` | warnings 是自由中文文本，没有稳定的 code/字段名结构：调用方无法区分“缺字段（已填默认）”“类型错误（已丢弃）”“降级解析”“提示”。当前测试也依赖中文子串（`CardParserTest.kt:122-123/132/151`），文案一改测试就红。真实卡把“V3 多出字段，已保留在原始 JSON 中”这种提示塞进 warnings，会让 UI 误报。 | `default_Seraphina` warnings = `["V3 卡：多出的字段（group_only_greetings / assets 等）已保留在原始 JSON 中"]`；`P5` 数字类型被丢却无对应 warning。 | 定义 `data class CardWarning(val code: WarningCode, val field: String?, val message: String)`（`WarningCode` 枚举），`warnings` 改 `List<CardWarning>`；测试断言 `code` 与 `field`，不再断言中文。 |
| C-17 | P2 | `core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:203-204` | `isZip()` 只判 `PK` 两字节：任何以 `PK` 开头的 JSON/文本会被当 CHARX，报“压缩包里没有 card.json”，把“不是卡/坏文件”误导成“缺文件”。 | 静态：`bytes[0]=='P' && bytes[1]=='K'`；构造 `PK...` 文本即触发。 | 判 4 字节 `50 4B 03 04`（或空 ZIP 的 `50 4B 05 06`、分卷 `50 4B 07 08`）；失败时保留原文案，便于用户定位。 |
| C-18 | P2 | `core/src/main/kotlin/com/teatalk/core/card/CardParser.kt:52-68` | `ZipInputStream` 的 `IOException/ZipException` 未包装成 `CardImportException`；`substringAfterLast('/')` 取 basename 且 `equals("card.json", ignoreCase=true)`，会接受 `assets/card.json`、`CARD.JSON` 这类 ST 不认的条目（ST 的 `charx.js:68` 用根级精确名 `extractFileFromZipBuffer(data,'card.json')`）。宽松本身可接受，但应在文档/测试里明确，且不能把“坏 zip”暴露成非受控异常。 | 静态：第 52–68 行无 `try/catch`；ST `src/charx.js` 第 68 行。 | `try { ... } catch (e: IOException) { throw CardImportException("CHARX 压缩包损坏：${e.message}", e) }`；把“接受嵌套/大小写”写成 KDoc + 用例；若坚持精确匹配，用 `entry.name == "card.json"`。 |
| C-19 | P2 | `core/src/main/kotlin/com/teatalk/core/card/PngTextReader.kt:53-63`、`CardParser.kt:43-46` | 解析一个 PNG 会完整扫两遍（`cardJson` 扫一次找 `ccv3/chara`，`hasKeyword` 再扫一次），且 `cardJson` 对每个 tEXt 都做 `String` 解码（含大段非角色卡文本时才停）。单卡几十 MB 时无谓 CPU/分配。 | 静态：`parsePng` 先 `cardJson` 后 `hasKeyword`；`readTexts` 每次新建 `String`。 | `PngTextReader.readMeta()` 一次遍历返回 `Map<String,String>`（keyword→text）与 flags，`cardJson`/`hasKeyword` 复用；对 `iTXt/zTXt` 明确忽略（与 ST 只认 `tEXt` 一致）。 |
| C-20 | P2 | `core/src/main/kotlin/com/teatalk/core/card/StCharacterCard.kt:74`；`CardParser.kt:35-39`；`WorldInfo.kt:49-53` | API 层面异常不分层：`CardImportException` 是 checked、单一类型、无 code；调用方无法用类型区分「不是角色卡」「文件损坏/越界」「不支持的版本」「CHARX 缺 card.json」，只能匹配中文 message。`parse()` 对“空字节/非 PNG 非 ZIP 非 JSON”走 JSON 分支报“JSON 解析失败”，语义偏。 | 静态：`CardImportException` 只有一个 message/cause；`parseJsonText` 顶层不是对象时报“角色卡 JSON 顶层不是对象”。 | 拆 `sealed class CardImportException(message, cause): NotACardException / CorruptCardException / UnsupportedVersionException / MissingAssetException`（Kotlin 侧 `sealed` + `Exception` 子类），message 只给 UI 展示；`parse()` 先按魔数分流，空文件单独报。线程安全：所有解析器无可变字段、`Json` 只读，`raw` 由调用方持有，未发现共享状态问题（可在 KDoc 声明“实例无状态、可并发调用”）。 |

## 3 单测缺口清单（按优先级）

1. **世界书 `extensions.*` 映射（最高）**：用 ST 导出形态构造 `extensions{position:4,depth:2,role:1,probability:50,useProbability:false,group:"g",case_sensitive:true,match_whole_words:true,sticky:3,cooldown:10}`、顶层 `position:"after_char"`，逐字段断言；真实卡 `default_Seraphina.png` 的 extensions 值恰好是默认值，当前用例无法发现 C-02。再把真实卡 extensions 临时改为非默认值（或另存 fixture）做快照。
2. **恶意/畸形 PNG**：chunk 长度=0x7FFFFFF8（C-04 复现）、chunk 截断、缺 IHDR、缺 IEND、重复 `chara`/`ccv3`、`ccv3` 坏 base64 回退 `chara`、CRC 错误、0 长度 chunk、`tEXt` 无 NUL。
3. **ZIP/CHARX 安全**：单条目声明超大尺寸（mock `ZipEntry` 或构造低压缩比样本，断言拒绝/上限）、`PK` 开头的非 ZIP、只有目录条目、`assets/card.json` 与 `CARD.JSON`、非 UTF-8 名、坏 deflate 数据（断言 `CardImportException` 而不是 `ZipException`）。
4. **世界书语义**：`use_regex`（字面键含 `(`/`[`）、`matchWholeWords` 中文（`码头`/`去码头`）、`selective` 缺省 false、`order` 降序、对象 entries 的 key=uid、重复 uid。
5. **数字/类型/未知字段**：`uid/order/depth=1.0`、`probability:"50"`、`use_regex:1`、`null` 字段、`tags` 为字符串/单值、BOM、GBK/非法 UTF-8（当前静默 `�`，应加 warning 或明确文档）、`spec` 未知/v4、V3 `group_only_greetings` 缺失。
6. **V1 升级全字段**：`creatorcomment`/`creator_notes`/`creator`/`talkativeness`（C-06），并与 ST `characters.js:905-945` 对齐断言。
7. **warnings 语义**：断言 `WarningCode`/`field`，不要断言中文；覆盖“V3 提示不应进 warnings”“类型错误有警告”。
8. **预算边界**：首条超预算仍放入（与 ST 一致，锁定行为）、后续条目全部跳过、`content` 含 emoji/代理对时 `length` 只影响预算不拆分（当前整条取，需断言内容完整）、CJK 预算说明。
9. **真实卡完整断言**：`character_book` 4 条、每条 `uid/keys/position/depth/extensions` 与源 JSON 一致，而不是只断言 `characterBook != null`（`CardParserTest.kt:183`）。
10. **可选**：并发解析 100 次（`Executors` 复用同一 `CardParser`）验证无共享状态；大文件（10MB+）内存/耗时回归。

测试卫生：现有用例全部用内存构造器（`CardParserTest.kt:191-247`），无临时文件，**未发现互相污染**；真实卡用 `getResourceAsStream` 读取，路径正确、只读，用法 OK。

## 4 与 ST 行为差异清单

| 项 | TeaTalk `:core` | SillyTavern | 判定 |
|---|---|---|---|
| PNG 元数据 | 只认 `tEXt`；`ccv3` 优先 | 只认 `tEXt`；`ccv3` 优先（`character-card-parser.js` `read()`） | ✅ 一致 |
| `character_book` 字段来源 | 顶层 `position/depth/role/probability/...` | `extensions` 优先、顶层兜底（`world-info.js:5617-5674`） | ❌ 差异，C-02 |
| `use_regex` | 对 `character_book` 生效 | `convertCharacterBook` 不映射，导入时忽略 | ❌ 差异，C-03 |
| `selective` 缺省 | `true`（`WorldInfo.kt:86`） | `entry.selective \|\| false`，缺省 false | ❌ 差异（缺字段时行为相反） |
| 条目顺序 | `order` 升序 | `order` 降序（`sortFn`，`world-info.js:88/5203`） | ❌ 差异，C-05 |
| entries 对象 key | 丢弃 | uid 取对象 key / `entry.id` | ❌ 差异，C-10 |
| base64 解码 | 严格 Decoder + trim | `Buffer.from` 宽松（忽略空白/非法字符） | ❌ 差异，C-07 |
| V1 升级 | 映射 6 个基础字段 + tags 根回退 | `creatorcomment ?? creator_notes`、`creator`、`talkativeness`→extensions | ❌ 差异，C-06 |
| `matchWholeWords` | `\p{L}\p{N}` 环视 | `(?:^\|\W)...(?:$\|\W)`（ASCII `\W`） | ❌ 差异，C-13 |
| 世界书预算 | `content.length+2` 字符 | 上下文百分比 token，`ignoreBudget` 控制 | ⚠️ 已注明 M0，但需避免调用方误用 |
| `probability/sticky/cooldown/delay` | 解析但不参与 | 参与激活/注入 | ⚠️ 文档已注明 M0 后续 |
| CHARX `card.json` | 任意目录 basename、大小写不敏感、无 `spec` 校验 | 根级精确 `card.json`（`charx.js:68`），要求 `spec` 存在（`charx.js:76`） | ⚠️ TeaTalk 更宽容（可接受，需文档+测试） |
| ZIP 大小限制 | 无 | 未见显式上限（ST 用 `extractFileFromZipBuffer`） | ⚠️ 双方都缺，但移动端更需上限 |
| spec 版本校验 | 不校验版本范围 | validator 校验 V2=`"2.0"`、V3∈[3,4)（独立入口，导入不强制） | ⚠️ 建议补，至少对 V3 必填集 |

## 5 附录：跑过的命令与结果

环境：Windows + PowerShell，JDK 21；Gradle 8.9。

```powershell
$env:JAVA_HOME='D:/DevEnv/java/jdk21/ms-21.0.9'
$env:ANDROID_HOME='D:/DevEnv/Android/Sdk'
$env:GRADLE_USER_HOME='D:/Project/TeaTalkApp/.gradle-home'
$env:TMPDIR=$env:TMP=$env:TEMP='D:/Project/TeaTalkApp/.tmp'
& 'D:/Project/TeaTalkApp/.tools/gradle-8.9/bin/gradle.bat' -p D:/Project/TeaTalkApp/TeaTalk :core:test --rerun-tasks --no-build-cache --no-daemon --console=plain
```

- 首次 `:core:cleanTest :core:test`：`BUILD SUCCESSFUL`，但 `:core:test FROM-CACHE`（Gradle build cache 直接恢复测试输出，**并未真正执行**）。
- 加 `--rerun-tasks --no-build-cache` 后：`5 actionable tasks: 5 executed`，30s 完成；结果 `CardParserTest tests=13 failures=0 errors=0`、`WorldInfoTest tests=8 failures=0 errors=0`，共 21 个，全绿。

独立 Java 探针（编译 `core/build/classes/kotlin/main` + kotlin-stdlib 2.0.21 + kotlinx-serialization-json 1.6.3）：

```text
P1 chunk-length-overflow -> ArrayIndexOutOfBoundsException: Index -2147483636 out of bounds for length 24
P4 base64-with-newline   -> CardImportException: PNG 的 chara 元数据不是合法 base64
P6 unknown-spec          -> source=JSON_V2 spec=chara_card_v2 warning="缺少 spec 字段，按 V2 解析"
P8 st-extensions         -> position=1 depth=4 role=0 probability=100 caseSensitive=false group=""
P10 useRegex             -> matchLiteral("Alice (maid)")=false, matchRegex("Alice maid")=true
P11 entries-key-uid      -> uids=0,0
P13 int-double           -> uid=0 order=100 depth=4
```

真实卡 `core/src/test/resources/cards/default_Seraphina.png`（551,901 字节）：`source=PNG_V3 spec=chara_card_v3 specVersion=3.0 name=Seraphina`；`character_book` 4 条，字段 `[id,keys,secondary_keys,comment,content,constant,selective,insertion_order,enabled,position,use_regex,extensions]`；TeaTalk 解析 `uid=0..3`、`keys` 正确、`pos=0/depth=4/role=0/order=100/selective=true`；warnings 仅一条 V3 提示。**结论：该卡 extensions 值恰好等于默认值，无法暴露 C-02。**

整词匹配对照（同一组 key/text，Java 模拟 TeaTalk 环视，Node 模拟 ST 的 `\W`）：

```text
teatalk-lookaround=false st-ascii-W=true   // key=码头, text=去码头
```

未修改任何源码、测试或 git 状态；`git -C TeaTalk status --porcelain` 仅显示 `?? docs/review/`（本次报告与同会话其他审阅产物）。
