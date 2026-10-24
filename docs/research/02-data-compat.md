# 02 · SillyTavern 数据兼容规格（TeaTalk 导入层）

> 研究对象：`SillyTavern/`（v1.19.0 源码）+ `SillyTavern-Docs/`（官方文档）
> 目标：让 TeaTalk 无损导入 ST 的**角色卡 / 世界书 / 聊天记录 / 预设 / 群聊**，并评估"直连 PC 端 ST 后端"的可行性。
> 约定：所有证据为 `文件:行号`，路径相对仓库根 `D:\Project\TeaTalkApp\`。带 ✅ 的段落给出可直接落成 Kotlin data class 的字段清单。

---

## 0. 结论速览（TL;DR）

| # | 结论 | 证据 |
|---|---|---|
| 1 | 角色卡 = **PNG 的 tEXt chunk**，keyword `chara`(V2) / `ccv3`(V3)，值是 **base64 后的 JSON 字符串**；`ccv3` 优先。ST 导出时**同时写两个 chunk**（ccv3 内容 = V2 数据改 `spec`/`spec_version`）。 | `SillyTavern/src/character-card-parser.js:15-46,54-78` |
| 2 | 校验器分 V1/V2/V3 三档：V1 必填 6 字段、V2 必填 14 字段、**V3 只校验 `spec`/`spec_version`，data 是 object 就行**（极宽松）。 | `SillyTavern/src/validator/TavernCardValidator.js:32-48,55-64,104-122,143-168` |
| 3 | 世界书 = `worlds/<name>.json`，`{entries:{ "<uid>": {...} }}`，**entries 是对象不是数组**（key = uid 字符串）；CCv2 的 `character_book` 里才是数组。 | `SillyTavern/default/content/Eldoria.json:1-3`、`SillyTavern/public/scripts/char-data.js:45-48` |
| 4 | 世界书最影响行为的字段是 **`position` + `depth` + `role`**（三件套决定注入位置/深度/说话人），其次是 `selective`(默认 true！)、`probability`+`useProbability`、`constant`、`group`/`groupWeight`/`groupOverride`、`excludeRecursion`/`preventRecursion`/`delayUntilRecursion`、`sticky`/`cooldown`/`delay`。 | `SillyTavern/public/scripts/world-info.js:4082-4125,5617-5674` |
| 5 | 聊天记录 = `chats/<角色头像去.png>/<chat名>.jsonl`，**每行一个 JSON**，**第 0 行是 ChatHeader**（`chat_metadata` 等），其余行为 ChatMessage；群聊在 `group chats/<chatId>.jsonl`。 | `SillyTavern/src/endpoints/chats.js:532-543`、`SillyTavern/public/script.js:7427-7445` |
| 6 | 分支 = **swipe**（同一条消息的多个备选回复）+ **checkpoint**（整份 jsonl 复制，头部 `chat_metadata.main_chat` 记录父 chat 名）。 | `SillyTavern/public/scripts/bookmarks.js:47,90-111`、`SillyTavern-Docs/Usage/Characters/chatfilemanagement.md:44-55` |
| 7 | PC 端 ST 后端**可以直连**：纯 HTTP + JSON，但**鉴权只有 cookie session + CSRF token，没有 `X-SillyTavern-User` 之类的用户头**（全仓库 0 命中）。 | `SillyTavern/src/server-main.js:156-210` |
| 8 | 最大导入风险：`post_history_instructions`、`data.extensions.depth_prompt`、`data.extensions.world`、`character_book.extensions.*`、chat `swipes/swipe_info`、`chat_metadata.integrity`、openai 预设的 `prompts`/`prompt_order` —— 丢了**不报错但角色行为全变**。 | 见 §2.7 / §3.8 / §4.7 |

---

## 1. 数据目录布局（导入时按目录结构遍历即可）

证据：`SillyTavern/src/constants.js:16-48`（`USER_DIRECTORY_TEMPLATE`）、`SillyTavern/src/constants.js:9`（`SETTINGS_FILE = 'settings.json'`）

| 目录 | 内容 | 导入价值 |
|---|---|---|
| `characters/*.png` | 角色卡（整份 JSON 内嵌 PNG tEXt，**没有独立 .json**） | ★★★ |
| `worlds/*.json` | 世界书（一个文件一本） | ★★★ |
| `chats/<avatar 去 .png>/<name>.jsonl` | 单人聊天记录 | ★★★ |
| `group chats/<chatId>.jsonl` | 群聊记录 | ★★★ |
| `groups/*.json` | 群定义（成员、生成模式） | ★★★ |
| `settings.json` | 全局设置 + `power_user`（personas/tags 等）+ `oai_settings` 等 | ★★★ |
| `themes/*.json` | 主题（纯 UI，可最后做） | ★ |
| `QuickReplies/*.json` | 快速回复预设 | ★★ |
| `context/*.json` `instruct/*.json` `sysprompt/*.json` `reasoning/*.json` | 格式化预设（**注意：用户目录里是平铺目录名，默认内容在 `default/content/presets/<同名>/`**） | ★★★ |
| `OpenAI Settings/*.json` `TextGen Settings/*.json` `KoboldAI Settings/*.json` `NovelAI Settings/*.json` | API 参数预设 | ★★ |
| `User Avatars/*.png` + `settings.json.power_user.personas` | 用户人设（persona） | ★★ |
| `user/images`, `backgrounds`, `assets` | 图片资源（ChatMessage.extra 会引用文件名） | ★ |

**易被忽略**：`default/content/` 是首启模板；`default/content/index.json` 决定首次运行复制哪些文件（见 `SillyTavern/default/content/index.json`）。ST 的 `data/` 目录在本次环境为空，说明**没有任何真实用户数据可参考**，格式结论全部来自源码。

---

## 2. 角色卡

### 2.1 四种载体

| 载体 | 判定 | 解析入口 |
|---|---|---|
| `.png`（主流，内嵌 JSON） | PNG tEXt chunk | `SillyTavern/src/character-card-parser.js:54-78` |
| `.charx`（zip） | 内含 `card.json` + 资源 | `SillyTavern/src/charx.js:51-279` |
| `.json` | V2/V3 spec、或 V1 裸对象、或 RisuAI/Pygmalion 格式 | `SillyTavern/src/endpoints/characters.js:883-959` |
| `.yaml/.yml`、`.byaf` | 小众格式 | `characters.js:731-755`、`:803-874` |

服务端统一入口：`POST /api/characters/import`，`file_type ∈ {yaml,yml,json,png,charx,byaf}`（`SillyTavern/src/endpoints/characters.js:1560-1599`）。

### 2.2 PNG tEXt 解析规则（必须逐字实现）

`SillyTavern/src/character-card-parser.js`：

```js
// 读：ccv3 优先，其次 chara
const ccv3Index = textChunks.findIndex(c => c.keyword.toLowerCase() === 'ccv3');
if (ccv3Index > -1) return Buffer.from(textChunks[ccv3Index].text, 'base64').toString('utf8');
const charaIndex = textChunks.findIndex(c => c.keyword.toLowerCase() === 'chara');
if (charaIndex > -1) return Buffer.from(textChunks[charaIndex].text, 'base64').toString('utf8');
throw new Error('No PNG metadata.');        // :54-78
```

```js
// 写：先删掉所有 chara/ccv3 chunk，再写 chara，再尝试写 ccv3（V2 数据改 spec 冒充 V3）
const base64EncodedData = Buffer.from(data, 'utf8').toString('base64');
chunks.splice(-1, 0, PNGtext.encode('chara', base64EncodedData));   // :28-29
const v3Data = JSON.parse(data);
v3Data.spec = 'chara_card_v3';
v3Data.spec_version = '3.0';
chunks.splice(-1, 0, PNGtext.encode('ccv3', Buffer.from(JSON.stringify(v3Data),'utf8').toString('base64')));  // :32-42
```

**要点（Android 端实现注意）**
1. tEXt chunk 结构：`length(4B BE) + 'tEXt'(4B) + keyword + 0x00 + text`。**keyword 大小写不敏感**（`toLowerCase()`）。
2. 值是 base64；解码后是 UTF-8 JSON。**可能带 BOM / 前后空白**，解析前 trim 并按需剥 BOM（ST 侧对 `.json` 文件用 `tryParse`，对 chunk 直接 `JSON.parse`）。
3. **导出卡会同时含 `chara` 和 `ccv3`，而 `ccv3` 里的 `data` 仍是 V2 结构**；所以「用 spec 判断版本」比「用 chunk keyword 判断」可靠：别因为读到 `ccv3` 就以为是真 V3。
4. 源码注释 `:10` 写「Writes only 'chara', 'ccv3' is not supported」，与实际代码（`:31-42`）**矛盾**——以代码为准。这是上游注释陈旧，导入方不能据此假设没有 ccv3。

### 2.3 版本校验规则（决定"这张卡算不算合法"）

`SillyTavern/src/validator/TavernCardValidator.js`：

- `validate()` 依次 `validateV1 → validateV2 → validateV3`，返回 `1|2|3|false`（`:32-48`）
- **V1 必填 6**：`name, description, personality, scenario, first_mes, mes_example`（`:55-64`）
- **V2 必填 14**（在 `data` 内）：`name, description, personality, scenario, first_mes, mes_example, creator_notes, system_prompt, post_history_instructions, alternate_greetings, tags, creator, character_version, extensions`；且 `alternate_greetings`/`tags` 必须是 Array，`extensions` 必须是 object（`:104-122`）
- `character_book` 若存在，必须有 `extensions` + `entries`（`:124-141`）
- **V3**：`spec === 'chara_card_v3'`，`spec_version` 满足 `3.0 <= v < 4.0`，`data` 只要是 object（`:143-168`）

**兼容策略（TeaTalk）**：不要照搬严格校验。建议「宽松解析 + 缺失字段补默认值」，因为大量真实卡 V2 字段不全，ST 自己导出时也是补齐后再写（见 §2.5 默认值）。判定顺序：`data` 存在 → 按 V2/V3 解析；顶层有 `name` → 按 V1 解析。

### 2.4 V2/V3 卡片结构（✅ 完整字段）

类型权威定义：`SillyTavern/src/types/spec-v2.d.ts:1-52`；前端运行期 typedef：`SillyTavern/public/scripts/char-data.js:51-85`。

```jsonc
{
  "spec": "chara_card_v2",
  "spec_version": "2.0",
  "data": {
    "name": "Seraphina",
    "description": "……",
    "personality": "……",
    "scenario": "……",
    "first_mes": "……",
    "mes_example": "……",
    "creator_notes": "……",
    "system_prompt": "……",
    "post_history_instructions": "……",
    "alternate_greetings": ["备选开场 1", "备选开场 2"],
    "character_book": { "name": "……", "entries": [ /* 见 §3.7 */ ], "extensions": {} },
    "tags": ["fantasy", "romance"],
    "creator": "author",
    "character_version": "1.0",
    "extensions": {
      "talkativeness": "0.5",
      "fav": false,
      "world": "Eldoria",
      "depth_prompt": { "prompt": "", "depth": 4, "role": "system" },
      "regex_scripts": []
    }
  }
}
```

**V3 相比 V2 的增量**（`data.assets` 在 ST 里只有 CharX 路径读：`SillyTavern/src/charx.js:172-210`）：
`data.assets[] = {type, uri, name, ext}`（`type ∈ {icon, background, emotion/expression(sprite), user_icon}`，`uri` 支持 `embeded://`/`embedded://`/`__asset:` 前缀，`charx.js:9-13`）；CCv3 规范里的 `group_only_greetings`、`creation_date`、`modification_date`、`creator_notes_multilingual`、`source` 在 **v1.19.0 中 grep 无实现**（仅 tokenizer 词汇误命中），ST 会**原样透传但不使用**——TeaTalk 应保存这些字段以免回写时丢失。

### 2.5 服务端读卡时的字段抬升与默认值（最重要的一段）

`SillyTavern/src/endpoints/characters.js`：

- `getCharaCardV2(jsonObject, directories, hoistDate = true)`：无 `spec` → `convertToV2()`；有 `spec` → `readFromV2()`（`:450-461`）
- `readFromV2(char)`（`:504-557`）：把 `data.*` 抬到顶层并回填 V1 兼容字段；`talkativeness` 缺省 `0.5`、`fav` 缺省 `false`（`:520-535`）；顶层与 `data` 不一致时只 `console.warn`（`:548-550`）；`chat` 缺省 `` `${name} - ${humanizedDateTime()}` ``（`:554`）
- `charaFormatData(data, directories)`（`:565-657`）：**ST 的"写卡"标准流程**，导入时反着做就等于导出：
  - 先 `tryParse(data.json_data) || {}` 保留不认识的外来键（`:567`），再 `delete data.json_data` 防递归（`:570`）
  - `alternate_greetings` 支持 array / 单个 string / 空（`:573-577`）→ **导入时必须按同样宽松处理**
  - 写 V1 字段 + 旧 ST 字段：`creatorcomment`、`avatar:'none'`、`chat`、`talkativeness`、`fav`（字符串 `'true'` 也认）、`tags`（**逗号分隔字符串也认**）（`:580-593`）
  - `data.extensions.{talkativeness, fav, world}`（`:615-617`）
  - `depth_prompt` 缺省 `{depth:4, role:'system'}`（`:620-626`）
  - 若 `data.world` 存在 → 读该世界书文件写进 `data.character_book`（优先用 `originalData`，否则 `convertWorldInfoToCharacterBook`）（`:628-644`）
  - `data.extensions` 若是字符串则 `JSON.parse` 后 deepMerge（`:646-654`）
- 私有字段清理：`unsetPrivateFields` 把 `char.fav=false`、`data.extensions.fav=false`、并删除 `char.chat`（`:498-502`）——**导出/分享时 chat 名不进卡**
- 头像文件名：`getPngName(file, directories)`（`:1543-1547`），`sanitize(file)` 后 `getUniqueName(..., nameBuilder: (base,i)=> i===0?base:`${base}${i}`, startIndex:0, maxTries:10000)`；文件名校验只禁 `\x00` 和路径分隔符（`SillyTavern/src/middleware/validateFileName.js:3`）。**即：`characters/<任意名>.png`，重名加数字后缀，无长度限制**。
- 导入时 `preserved_name` 可取上传文件名（`:1554-1558`）——用于"导出再导入保持同名"。

### 2.6 列表接口返回的浅字段（做角色列表页用）

`SillyTavern/src/endpoints/characters.js:370-395` `toShallow`：
`shallow: true, name, avatar, chat, fav, date_added, create_date, date_last_chat, chat_size, data_size, tags, data: { name, character_version, creator, creator_notes, tags, extensions: { fav, world } }`
`POST /api/characters/all`（`:1466-1478`）默认走 shallow（`useShallowCharacters` 开关）；`POST /api/characters/get`（`:1480-1497`）返回完整卡。
`POST /api/characters/chats`（`:1499-1535`）：`simple:true` → `[{file_name:'x.jsonl', file_id:'x'}]`；否则 `getChatInfo` 列表（字段见 §4.2）。

### 2.7 ⚠️ 最容易被忽略、但直接改变角色行为的字段

| 字段 | 为什么致命 | 证据 |
|---|---|---|
| `data.post_history_instructions` | 插在聊天历史**之后**的强指令（越狱/风格锁）；V2 必填但很多 UI 不展示。丢了角色立刻"变味" | `TavernCardValidator.js:104-122` |
| `data.system_prompt` | 覆盖全局 system prompt；空串 ≠ 缺失（空串表示"不用"） | 同上 |
| `data.extensions.depth_prompt` | 角色专属的 Author's Note：`{prompt, depth, role}`；`role` 是**字符串** `system|user|assistant`（与世界书里的**数字** role 不同！） | `char-data.js:69-85`、`characters.js:620-626` |
| `data.extensions.world` | 角色绑定世界书名（只存名字，不含内容）；丢了这个角色就"失忆" | `characters.js:628-644`、`char-data.js:69-85` |
| `data.extensions.talkativeness` / `fav` | 群聊发言概率；`fav` 影响排序。缺省 `0.5` / `false` | `characters.js:520-535` |
| `data.extensions.regex_scripts[]` | 角色级正则改写（正则/替换/作用域/深度范围），丢掉会输出脏格式 | `char-data.js:88-102` |
| `data.character_book` | 内嵌世界书；ST 导入时会**解包成独立世界书文件并绑定**，不是留在卡里 | `world-info.js:5731-5759` |
| `alternate_greetings` | 不是"额外文案"，而是首条消息的 **swipes**；顺序即 swipe_id | `script.js:7710-7742` |
| 顶层遗留键 | `convertToV2` 会把顶层同名字段也写一份（V1 兼容），读写不一致时 ST 只 warn 不修正 | `characters.js:548-550` |

### 2.8 CharX（`.charx`）要点

- zip；`findZipStart` 兼容"自解压 zip"（找 `PK\x03\x04`）（`SillyTavern/src/charx.js:16-30`）
- 必须含 `card.json` 且含 `spec`，否则 `throw new Error('Invalid CharX card file: missing spec field')`（`:68,74-78`）
- 资源 `card.data.assets[]`：`icon` 取 `name === 'main'` 的优先（`:202-210`）；`emotion/expression` → sprite，`background` → background，其余 → misc；`icon`/`user_icon` 跳过（`:240-278`）
- 落盘：sprite → `characters/<char>/<base>.<ext>`，background → `characters/<char>/backgrounds/`，misc → `userImages/<char>/`（`:309-399`）
- 服务端入口 `importFromCharX`（`characters.js:765-801`）

---

## 3. 世界书（World Info / Lorebook）

### 3.1 文件与 HTTP 端点

`SillyTavern/src/endpoints/worldinfo.js`（全文 157 行）：

| 端点 | Body | 返回/行为 |
|---|---|---|
| `POST /api/worldinfo/list` | — | `[{file_id, name, extensions}]`，`file_id` = 文件名去 `.json`（`:39-69`） |
| `POST /api/worldinfo/get` | `{name}` | 完整 world 对象（`:71-79`） |
| `POST /api/worldinfo/delete` | `{name}` | 删文件（`:81-97`） |
| `POST /api/worldinfo/import` | `{name, convertedData?}` | **必须含 `entries` 键**否则 400 `Is not a valid world info file`（`:116-118`） |
| `POST /api/worldinfo/edit` | `{name, data}` | 同样要求 `data.entries`；`JSON.stringify(data,null,4)` 落盘（`:134-157`） |

文件名经 `sanitize(`${name}.json`)`（`:17-35`）。

### 3.2 文件结构（✅ 真实样例）

真实文件 `D:\Project\TeaTalkApp\SillyTavern\default\content\Eldoria.json`（首条 entry 摘自第 3-40 行）：

```jsonc
{
  "entries": {
    "0": {
      "uid": 0,
      "key": ["eldoria", "wood", "forest", "magical forest"],
      "keysecondary": [],
      "comment": "eldoria",
      "content": "{{user}}: ...\n{{char}}: ...",
      "constant": false,
      "selective": true,
      "order": 100,
      "position": 0,
      "disable": false,
      "displayIndex": 0,
      "addMemo": true,
      "group": "",
      "groupOverride": false,
      "groupWeight": 100,
      "sticky": 0,
      "cooldown": 0,
      "delay": 0,
      "probability": 100,
      "depth": 4,
      "useProbability": true,
      "role": null,
      "vectorized": false,
      "excludeRecursion": false,
      "preventRecursion": false,
      "delayUntilRecursion": false,
      "scanDepth": null,
      "caseSensitive": null,
      "matchWholeWords": null,
      "useGroupScoring": null,
      "automationId": ""
    }
  }
}
```

**关键陷阱**
1. `entries` 是 **object**，key 是 **uid 的字符串形式**；uid 可能不连续、可能被重新编号过。Kotlin 侧建议 `Map<Int, WorldInfoEntry>`，uid 以字段 `uid` 为准、以 key 做回退。
2. 若 `entries` 是**数组**（部分第三方文件/CCv2 转换残留），也要能吃：ST 的 `character_book` 就是数组（§3.7）。
3. `role: null` 在真实文件里出现（不是 0）；`scanDepth/caseSensitive/matchWholeWords/useGroupScoring` 常为 `null` = "继承全局"。

### 3.3 ✅ 世界书 entry 全字段 + 默认值

权威：`SillyTavern/public/scripts/world-info.js:4082-4125`（`newWorldInfoEntryDefinition`，每字段 `default` + `type`），`displayIndex` 由插入顺序决定（`:4127-4149`）。

| 字段 | 类型 | 默认 | 语义/影响 |
|---|---|---|---|
| `uid` | number | 新建时自增 | 文件内唯一 ID |
| `key` | string[] | `[]` | 主关键词（**ST 中始终按正则/大小写规则匹配，非纯 substring**） |
| `keysecondary` | string[] | `[]` | 次关键词，配合 `selectiveLogic` |
| `comment` | string | `""` | 备注/标题（**UI 显示名，不参与匹配**） |
| `content` | string | `""` | 注入正文，支持 `{{user}}`/`{{char}}`/宏 |
| `constant` | bool | `false` | 常驻（不看关键词，必注入） |
| `vectorized` | bool | `false` | 走向量检索（需 vector 扩展） |
| `selective` | bool | **`true`** | ⚠️ 默认开启：有 secondary keys 时需满足 `selectiveLogic` |
| `selectiveLogic` | enum | `0` (AND_ANY) | `0` AND_ANY / `1` NOT_ALL / `2` NOT_ANY / `3` AND_ALL（`:33-38`） |
| `addMemo` | bool | `false` | UI 用 |
| `order` | number | `100` | 插入顺序（**降序排**，见 `:88` `sortFn=(a,b)=>b.order-a.order`） |
| `position` | enum | `0` | ⚠️ 见 §3.4 |
| `disable` | bool | `false` | 禁用（注意**不是 `enabled`**！与 character_book 相反） |
| `ignoreBudget` | bool | `false` | 无视 token 预算 |
| `excludeRecursion` | bool | `false` | 不参与递归扫描 |
| `preventRecursion` | bool | `false` | 自身不触发递归 |
| `delayUntilRecursion` | number/bool | `0` | 延迟到第 N 层递归 |
| `matchPersonaDescription` | bool | `false` | 也匹配 persona 描述 |
| `matchCharacterDescription` | bool | `false` | 也匹配角色 description |
| `matchCharacterPersonality` | bool | `false` | 也匹配角色 personality |
| `matchCharacterDepthPrompt` | bool | `false` | 也匹配深度提示 |
| `matchScenario` | bool | `false` | 也匹配 scenario |
| `matchCreatorNotes` | bool | `false` | 也匹配 creator_notes |
| `delay` / `cooldown` / `sticky` | number | `null` | 时间型效果（消息数） |
| `probability` | number | `100` | 触发概率 % |
| `useProbability` | bool | `true` | 是否启用概率 |
| `depth` | number | `4` (`DEFAULT_DEPTH`) | `position=4` 时的注入深度（0=最底） |
| `outletName` | string | `""` | `position=7` 时的命名出口，`{{outlet::Name}}` 取值（文档 `worldinfo.md:159-163`） |
| `group` | string | `""` | 包含组标签 |
| `groupOverride` | bool | `false` | 组内"优先包含"（同组只留 order 最大） |
| `groupWeight` | number | `100` | 组权重（`world_info_use_group_scoring` 时参与评分） |
| `scanDepth` | number\|null | `null` | 覆盖全局扫描深度（`MAX_SCAN_DEPTH=1000`） |
| `caseSensitive` | bool\|null | `null` | 覆盖全局 |
| `matchWholeWords` | bool\|null | `null` | 覆盖全局 |
| `useGroupScoring` | bool\|null | `null` | 覆盖全局 |
| `automationId` | string | `""` | 自动化扩展标识（quick replies 关联） |
| `role` | enum\|null | `0`/`null` | ⚠️ `position=4` 时注入消息的角色：0=system / 1=user / 2=assistant |
| `triggers` | string[] | `[]` | 生成类型触发限定（`GENERATION_TYPE_TRIGGERS`） |
| `characterFilter` | object | 无 | 角色过滤 `{isExclude, names[], tags[]}`，`excludeFromTemplate` 不入默认模板（`:4127-4129`） |

### 3.4 ⚠️ `position` / `depth` / `role` 的真实语义（最易做错）

枚举：`world-info.js:855-864` `world_info_position = {before:0, after:1, ANTop:2, ANBottom:3, atDepth:4, EMTop:5, EMBottom:6, outlet:7}`；`wi_anchor_position = {before:0, after:1}`（`:866-869`）。
注入角色枚举：`SillyTavern/public/script.js:494-498` `extension_prompt_roles = {SYSTEM:0, USER:1, ASSISTANT:2}`；注入类型枚举 `:484-489` `extension_prompt_types = {NONE:-1, IN_PROMPT:0, IN_CHAT:1, BEFORE_PROMPT:2}`；深度上限 `MAX_INJECTION_DEPTH = 10000`（`:500`）。

| position | 含义 | 用到 depth/role？ |
|---|---|---|
| `0` before | 角色定义（story string）**之前** | 否 |
| `1` after | 角色定义**之后** | 否 |
| `2` ANTop | Author's Note 顶部 | 否 |
| `3` ANBottom | Author's Note 底部 | 否 |
| `4` atDepth | **按 `depth` 插进聊天历史第 N 条**（0=最后一条之后） | ✅ 两者都用 |
| `5` EMTop / `6` EMBottom | 示例消息（mes_example）前/后 | 否 |
| `7` outlet | 命名出口，由 `{{outlet::Name}}` 宏决定位置（文档 `worldinfo.md:159-163`） | 否 |

文档佐证：`SillyTavern-Docs/Usage/worldinfo.md:141-149`（Insertion Position 列表，含 **@ D** = 指定深度、**Depth 0 为提示词最底部**）。

### 3.5 全局启用列表 / 角色绑定 / chat 级绑定

- 角色绑定：`card.data.extensions.world` = 世界书名（单本）；绑定发生在导入卡片时（`world-info.js:5731-5759`）
- 全局启用：`settings.json` 的 `world_info_settings.world_info.globalSelect` = 世界书名数组（`world-info.js:795-812` + `:84-87` `Object.assign(world_info,{globalSelect:selected_world_info})`；旧键 `settings.world_info.globalSelect` 兼容迁移见 `:998`）
- **chat 级绑定**：`chat_metadata.world_info` = 世界书名（`world-info.js:94` `METADATA_KEY = 'world_info'`；读取/不存在时自动建册见 `:1159-1182`，赋值入口 `assignLorebookToChat` 见 `:5947-5989`）；文档 `worldinfo.md:55-63` "Chat Lorebook"
- 人设绑定：persona 的 `lorebook` 字段（`personas.js:515-530`）

**合并策略**（文档 `worldinfo.md:65-83`）：`world_info_character_strategy` 三值 `0 evenly / 1 character_first / 2 global_first`（`world-info.js:27-31`），决定全局书与角色书的先后。

### 3.6 世界书全局设置（settings.json 中的键）

`world-info.js:65-82` 默认值 + `:917-959` 从 settings 读取的键名 + `:795-812` 保存结构：

```jsonc
"world_info_settings": {
  "world_info": { "globalSelect": ["Eldoria"] },
  "world_info_depth": 2,
  "world_info_min_activations": 0,
  "world_info_min_activations_depth_max": 0,
  "world_info_budget": 25,
  "world_info_include_names": true,
  "world_info_recursive": false,
  "world_info_overflow_alert": false,
  "world_info_case_sensitive": false,
  "world_info_match_whole_words": false,
  "world_info_character_strategy": 1,
  "world_info_budget_cap": 0,
  "world_info_use_group_scoring": false,
  "world_info_max_recursion_steps": 0
}
```
注意 `world_info_budget` 若 > 100 会被迁移回 25（`:946-948`）。

### 3.7 CCv2 `character_book` ↔ ST 世界书 双向映射（做导出用）

**CCv2 → ST**：`world-info.js:5617-5674` `convertCharacterBook(characterBook)`：
- `entry.id` 缺失/非数字 → 用数组下标当 uid（`:5622-5624`）
- `position = entry.extensions?.position ?? (entry.position === 'before_char' ? 0 : 1)`（`:5636`）
- `depth` 缺省 4、`role` 缺省 `SYSTEM`、`probability` 100、`useProbability` true、`selectiveLogic` AND_ANY、`groupWeight` 100、`displayIndex` = 下标
- 保留 `extensions` 原件与 `originalData: characterBook`（回写时优先用它，保证往返不丢字段）

**ST → CCv2**：`characters.js:663-722` `convertWorldInfoToCharacterBook(name, entries)`：
- `position: entry.position === 0 ? 'before_char' : 'after_char'`（`:680`）—— **⚠️ 有损！** `position=2..7` 全部塌缩成 `after_char`，真实位置只保留在 `extensions.position` 里
- `use_regex: true`（`:681`，注释：ST 的 key 总是按正则处理）
- 扩展字段映射全表见 `:682-715`（`position/exclude_recursion/display_index/probability/useProbability/depth/selectiveLogic/outlet_name/group/group_override/group_weight/prevent_recursion/delay_until_recursion/scan_depth/match_whole_words/use_group_scoring/case_sensitive/automation_id/role/vectorized/sticky/cooldown/delay/match_persona_description/.../triggers/ignore_budget`）
- 字段名对照权威表：`world-info.js:2687-2724` `originalWIDataKeyMap`（ST 驼峰字段 ↔ character_book 路径）

**CCv2 entry 字段**（`SillyTavern/src/types/spec-v2.d.ts`）：
`keys[]`, `secondary_keys[]?`, `content`, `extensions`(必填), `enabled`, `insertion_order`, `case_sensitive?`, `name?`, `priority?`, `id?`, `comment?`, `selective?`, `constant?`, `position?: 'before_char'|'after_char'`。
⚠️ 注意 **`enabled`（CCv2）与 `disable`（ST 文件）语义相反**，往返转换必须取反。

### 3.8 ⚠️ 世界书易忽略字段（按影响排序）

1. `position` + `depth` + `role`：只留 `content` 会导致世界书内容全部堆到 `before_char` 或最后一条，**语气/触发时机全错**。
2. `selective` 默认 **true** 且常被显式写 `false`：忽略它 → 带 secondary keys 的条目全部变成"只要主键命中就注入"。
3. `probability` + `useProbability`：忽略 → 本该 30% 触发的氛围条目每次必出。
4. `group` + `groupWeight` + `groupOverride`：忽略 → 同组互斥条目**全部**注入，提示词爆炸。
5. `excludeRecursion` / `preventRecursion` / `delayUntilRecursion`：递归扫描开启时行为差异极大。
6. `sticky` / `cooldown` / `delay`：时间型效果，忽略后条目寿命不同。
7. `constant`：忽略 → 常驻设定不出现。
8. `matchCharacterDescription` 等 6 个 `match*`：高级匹配源，默认 false。
9. `outletName` + `{{outlet::}}` 宏：忽略 → 该条目永远不出现。
10. `characterFilter`：角色过滤（v1.19 新增），忽略会串台。

---

## 4. 聊天记录（chat jsonl）

### 4.1 路径规则

- 单人：`chats/<角色 avatar 去掉 .png>/<chat 文件名>.jsonl`（`SillyTavern/src/endpoints/chats.js:545-570`；目录由 `characters.js:1503-1504` 推导）
- 群聊：`group chats/<chatId>.jsonl`（`chats.js:878-880`）
- 文件名默认 = `` `${角色名} - ${humanizedDateTime()}` ``（`characters.js:489-490`、`:554`）；导入的聊天叫 `` `${characterName} - ${time} imported.jsonl` ``（`chats.js:816,855`）

### 4.2 序列化格式（**不是 JSON 数组**）

`chats.js:532-543`：
```js
const jsonlData = chatData?.map(m => JSON.stringify(m)).join('\n');
```
读取：`getChatData` 按 `\n` split 后逐行 `tryParse`，空行丢弃（`chats.js:577-590`）。

**首行是 ChatHeader**（`SillyTavern/public/script.js:7427-7445`）：
```js
const chatHeader = { chat_metadata: metadata, user_name: 'unused', character_name: 'unused' };
// body: { ch_name, file_name, chat: [chatHeader, ...trimmedChat], avatar_url, force }
```

真实文件形态（示例，字段全部来自上面两个函数）：
```jsonl
{"chat_metadata":{"integrity":"hK3…","note_prompt":"","note_interval":1,"main_chat":"Seraphina - 2024-06-01@12h00m00s"},"user_name":"unused","character_name":"unused"}
{"name":"Seraphina","is_user":false,"is_system":false,"send_date":"2024-06-01T12:00:03.123Z","mes":"*她抬起头* 你终于来了。","extra":{}}
{"name":"User","is_user":true,"is_system":false,"send_date":"2024-06-01T12:00:20.456Z","mes":"抱歉，路上耽搁了。","extra":{}}
```

**兼容规则**
- 老文件可能**没有 header**：判断依据是"第 0 行是否有 `chat_metadata`"（`chats.js:458-464`；groups 迁移时补 header 见 `SillyTavern/src/endpoints/groups.js:78-87`）
- 末尾行可能被截断：`getChatInfo` 会用 `itemCounter - 2` 兜底计算条数（`chats.js:489-499`）——**导入时末行解析失败应丢弃该行而不是整份失败**
- 行内可能含 `\n`（`mes` 里的换行是 JSON 转义，不会真的出现裸换行）

### 4.3 ✅ ChatMessage 全字段（直接写 Kotlin）

权威：`SillyTavern/public/global.d.ts:66-88,90-128`；`MessageTimestamp = string | number | Date`（`:21`）。

```jsonc
{
  "name": "Seraphina",          // 显示名（群聊里是发言角色名）
  "mes": "正文",                 // 消息正文（含 markdown / 宏残留）
  "title": "…",                 // 系统消息标题
  "gen_started": "2024-06-01T12:00:01Z",
  "gen_finished": "2024-06-01T12:00:03Z",
  "send_date": "2024-06-01T12:00:03.123Z",
  "is_user": false,
  "is_system": false,
  "force_avatar": "xxx.png",    // 覆盖头像（用户自定义）
  "original_avatar": "Seraphina.png", // 群聊里标识真实发送者！
  "swipes": ["备选1", "备选2"],
  "swipe_info": [{ "send_date": "…", "gen_started": "…", "gen_finished": "…", "extra": {} }],
  "swipe_id": 0,
  "extra": { "api": "openai", "model": "gpt-4o", "token_count": 123 }
}
```

`ChatMessageExtra`（`global.d.ts:90-128`，`BaseMessageExtra & Partial<ReasoningMessageExtra> & Record<string, any>`）：
`api, model, type, gen_id, bias, uses_system_ui, memory, display_text, reasoning_display_text, tool_invocations, title, isSmallSys, token_count, swipeable, overswipe_behavior, files[], inline_image, media_display, media_index, media[], [IGNORE_SYMBOL]`；
以下**已废弃但真实文件里存在**：`file, image, video, image_swipes, append_title, generationType, negative`。

`ChatMetadata`（`global.d.ts:58-64`）：`{ tainted?, integrity?, scenario?, persona?, [key:string]: any }`，实测还会带 `system_prompt` / `mes_example`（chat 级覆盖角色卡同名项，`script.js:3415,3451,9003-9004`）、`main_chat`（checkpoint 父文件名，`bookmarks.js:113-127`）；Author's Note 的 `note_prompt/note_interval` 由 `scripts/authors-note.js:31-32` 映射写入。

### 4.4 分支（swipe / checkpoint）

- **swipe**：一条消息多份回复。`swipes[i]` 存正文，`swipe_info[i]` 存该份的 `send_date/gen_started/gen_finished/extra`，`swipe_id` 指向当前。缺失 `swipe_info` 时 ST 会 backfill（`script.js:6787-6882`）；编辑/重生成逻辑 `:6917-7006`。
- **checkpoint（=分支）**：**整份 jsonl 的复制**，文件名 `<主 chat> - Checkpoint #N`，头部 `chat_metadata.main_chat` 存父 chat 名（`SillyTavern/public/scripts/bookmarks.js:47,90-111`；`getMainChatName()` `:113-127`）。
- 语义与"改名会断链"：文档 `SillyTavern-Docs/Usage/Characters/chatfilemanagement.md:44-55,57-63`。
- **import 时**：不要试图把 checkpoint 合并成一棵树；按扁平 chat 列表导入 + `parentChatName` 字段即可 100% 还原。

### 4.5 群聊

- 群定义：`groups/*.json`，字段见 `global.d.ts:26-43`：
  `id, name, members: string[]（角色 avatar 文件名）, disabled_members: string[], chat_id, chats: string[]（群聊文件名）, generation_mode?: number, generation_mode_join_prefix?, generation_mode_join_suffix?, activation_strategy?: number, auto_mode_delay?: number, allow_self_responses?: boolean, avatar_url?, hideMutedSprites?, fav?, date_last_chat?`
- 创建默认值：`activation_strategy 0`、`generation_mode 0`、`disabled_members []`、join prefix/suffix `''`（`SillyTavern/src/endpoints/groups.js:156-190`）
- `POST /api/groups/all` 会补 `date_added/create_date/chat_size/date_last_chat`（`groups.js:113-144`）
- 群聊消息靠 **`original_avatar`** 区分发送者；`force_avatar` 是自定义头像（`global.d.ts:66-88`）
- 旧格式（群文件里带 `chat_metadata`/`past_metadata`）会被迁移到各 jsonl 首行（`groups.js:44-96`）
- 群聊生成模式枚举见 `group-chats.js`（`group_generation_mode`），TeaTalk 只需存数字即可往返

### 4.6 导入其它格式（PC 端可借道 ST 转换）

`src/endpoints/chats.js:771-870` `POST /api/chats/import`（`file_type` ∈ `json` / `jsonl`）：
- `json`：`savedsettings` → Kobold Lite；`histories` → CAI Tools；`data_visible[]` → oobabooga；`messages[]` → Agnai；`type === 'risuChat'` → RisuAI（`:800-813`）
- `jsonl`：首行需含 `user_name` / `name` / `chat_metadata` 之一；会尝试 `flattenChubChat`（Chub 聊天格式）（`:833-865`）
- 文档列出的可导入来源：`SillyTavern-Docs/Usage/Characters/chatfilemanagement.md:20-32`
- 导出：`.jsonl` 可再导入（但**不含图片/附件**）；`.txt` 不可再导入（文档 `:34-42`）

### 4.7 ⚠️ 聊天记录易忽略字段

| 字段 | 影响 | 证据 |
|---|---|---|
| `chat_metadata.integrity` | 写保护 slug：ST 载入聊天时若缺失会补 `uuidv4()`（`script.js:7665-7667`），保存时比对失败抛 `IntegrityMismatchError`（`chats.js:511-543`）。外部导入的聊天**不要伪造该值**，留空让 ST 自己生成 | `chats.js:511-543` |
| `swipes` + `swipe_info` + `swipe_id` | 丢 → 只剩当前分支，用户"重生成的历史"全没 | `global.d.ts:66-88` |
| `original_avatar` | 群聊里丢 → 所有消息变成同一人 | `global.d.ts:66-88` |
| `is_system` | 系统消息（如"场景已切换"）会被当成普通对话 | 同上 |
| `send_date` 的三种类型（string/number/Date） | 解析必须兼容三种 | `global.d.ts:21` |
| `chat_metadata.main_chat` | checkpoint 父子关系 | `bookmarks.js:113-127` |
| `extra.token_count/api/model` | 统计与"重放相同参数"能力 | `global.d.ts:90-128` |
| 尾部截断行 | 整份文件不可用 | `chats.js:489-499` |

---

## 5. 预设（Presets）

ST 有 5 类"预设"，导入导出要分开处理。目录映射见 `SillyTavern/src/endpoints/presets.js:16-38`：
`kobold/koboldhorde → KoboldAI Settings`、`novel → NovelAI Settings`、`textgenerationwebui → TextGen Settings`、`openai → OpenAI Settings`、`instruct → instruct`、`context → context`、`sysprompt → sysprompt`、`reasoning → reasoning`（均 `.json`，body `{name, preset, apiId}`）。
`POST /api/settings/get` 一次性返回全部预设清单 + 内容（`SillyTavern/src/endpoints/settings.js:219-296`）。

### 5.1 Context / Story String 预设（✅ 全文真实样例）

`D:\Project\TeaTalkApp\SillyTavern\default\content\presets\context\Default.json`（14 行，全文）：

```json
{
    "story_string": "{{#if anchorBefore}}{{anchorBefore}}\n{{/if}}{{#if system}}{{system}}\n{{/if}}{{#if wiBefore}}{{wiBefore}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}{{#if personality}}{{personality}}\n{{/if}}{{#if scenario}}{{scenario}}\n{{/if}}{{#if wiAfter}}{{wiAfter}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}{{#if anchorAfter}}{{anchorAfter}}\n{{/if}}{{trim}}",
    "example_separator": "***",
    "chat_start": "***",
    "use_stop_strings": false,
    "names_as_stop_strings": true,
    "story_string_position": 0,
    "story_string_depth": 1,
    "story_string_role": 0,
    "always_force_name2": true,
    "trim_sentences": false,
    "single_line": false,
    "name": "Default"
}
```
⚠️ `story_string` 是 **Handlebars 模板**（`{{#if}}` / `{{trim}}`），字段顺序即提示词顺序；TeaTalk 若自研 prompt 组装，**必须保留该模板并能求值**，否则"角色定义顺序"会变（ST 文档 `Usage/Prompts/context-template.md`）。

### 5.2 Instruct 预设（✅ 全文真实样例）

`D:\Project\TeaTalkApp\SillyTavern\default\content\presets\instruct\ChatML.json`（25 行，全文）：

```json
{
    "input_sequence": "<|im_start|>user",
    "output_sequence": "<|im_start|>assistant",
    "last_output_sequence": "",
    "system_sequence": "<|im_start|>system",
    "stop_sequence": "<|im_end|>",
    "wrap": true,
    "macro": true,
    "names_behavior": "force",
    "activation_regex": "",
    "first_output_sequence": "",
    "skip_examples": false,
    "output_suffix": "<|im_end|>\n",
    "input_suffix": "<|im_end|>\n",
    "system_suffix": "<|im_end|>\n",
    "user_alignment_message": "",
    "system_same_as_user": false,
    "last_system_sequence": "",
    "first_input_sequence": "",
    "last_input_sequence": "",
    "sequences_as_stop_strings": true,
    "story_string_prefix": "<|im_start|>system",
    "story_string_suffix": "<|im_end|>\n",
    "name": "ChatML"
}
```
`names_behavior ∈ {none, force, always}`；`wrap/macro/sequences_as_stop_strings/skip_examples` 是行为开关，**漏一个就会多/少 token**。

### 5.3 OpenAI / Chat Completion 预设（结构最复杂）

`D:\Project\TeaTalkApp\SillyTavern\default\content\presets\openai\Default.json`（245 行）关键结构：

```jsonc
{
  "chat_completion_source": "openai",
  "temperature": 1, "frequency_penalty": 0, "presence_penalty": 0,
  "top_p": 1, "top_k": 0, "top_a": 0, "min_p": 0, "repetition_penalty": 1,
  "openai_max_context": 4095, "openai_max_tokens": 300,
  "names_behavior": 0, "stream_openai": true,
  "wi_format": "{0}", "scenario_format": "{{scenario}}", "personality_format": "{{personality}}",
  "prompts": [
    { "name": "Main Prompt", "system_prompt": true, "role": "system", "content": "…", "identifier": "main" },
    { "identifier": "chatHistory", "name": "Chat History", "system_prompt": true, "marker": true },
    { "name": "World Info (before)", "system_prompt": true, "role": "system", "content": "…", "identifier": "worldInfoBefore" }
  ],
  "prompt_order": [
    { "character_id": 100000, "order": [ { "identifier": "main", "enabled": true }, … ] },
    { "character_id": 100001, "order": [ … ] }
  ]
}
```
- `prompts[]` 每项：`{name, system_prompt: bool, role, content, identifier}` 或 `{identifier, name, system_prompt, marker: true}`
- `identifier` 取值：`main, nsfw, dialogueExamples, jailbreak, chatHistory, worldInfoAfter, worldInfoBefore, enhanceDefinitions, charDescription, charPersonality, scenario, personaDescription`（`Default.json:51-129`）
- `prompt_order[]`：`{character_id, order: [{identifier, enabled}]}`；**`character_id` 100000 = 角色聊天默认、100001 = 群聊默认**（`:130-233`）
- ⚠️ **只导入采样参数、不导入 `prompts`/`prompt_order`，等于把提示词顺序全部重置**——这是"预设导入失败感"的最大来源。
- 其余大字段：`openai_model, claude_model, openrouter_model/use_fallback/group_models/sort_models, google_model, vertexai_model, mistralai_model, ai21_model, custom_model/custom_url/custom_include_body/custom_exclude_body/custom_include_headers, reverse_proxy, proxy_password, max_context_unlocked, send_if_empty, impersonation_prompt, new_chat_prompt, new_group_chat_prompt, new_example_chat_prompt, continue_nudge_prompt, group_nudge_prompt, bias_preset_selected, assistant_prefill, assistant_impersonation, use_sysprompt, squash_system_messages, media_inlining, bypass_status_check, continue_prefill, continue_postfix, seed, n`

### 5.4 快速回复（Quick Replies）✅ 全文真实样例

`D:\Project\TeaTalkApp\SillyTavern\default\content\presets\quick-replies\Default.json`（23 行）：
```json
{
    "name": "Default",
    "quickReplyEnabled": true,
    "quickReplySlots": [
        { "mes": "/?", "label": "HELP", "enabled": true },
        { "mes": "/newchat", "label": "New Chat", "enabled": true },
        { "mes": "/bgcol", "label": "Match UI to Background", "enabled": true }
    ],
    "numberOfSlots": 3,
    "selectedPreset": "Default"
}
```
端点 `POST /api/quick-replies/save|delete`，body 原样落盘 `QuickReplies/<name>.json`（`SillyTavern/src/endpoints/quick-replies.js:10-32`）。`mes` 是**斜杠命令**，TeaTalk 不支持斜杠命令时应保留原文并提供纯文本回退。

### 5.5 主题 / movingUI

`POST /api/themes/save` body 即主题对象，落盘 `themes/<name>.json`（`SillyTavern/src/endpoints/themes.js:10-19`）；movingUI 同构（`/api/moving-ui`）。纯 UI，可在 P2 阶段再做。

---

## 6. settings.json / persona / tags

### 6.1 顶层结构（保存时机见 `script.js:8069-8094`）

```jsonc
{
  "firstRun": false, "currentVersion": "1.19.0",
  "username": "User", "user_avatar": "user-default.png",
  "active_character": "Seraphina.png", "active_group": null,
  "amount_gen": 400, "max_context": 4096, "main_api": "openai",
  "world_info_settings": { /* §3.6 */ },
  "textgenerationwebui_settings": {…}, "horde_settings": {…},
  "power_user": { /* §6.2 */ },
  "extension_settings": {…}, "tags": [...], "tag_map": {...},
  "nai_settings": {…}, "kai_settings": {…}, "oai_settings": {…},
  "background": {…}, "proxies": […], "selected_proxy": null,
  "swipes": true, "accountStorage": {…}
}
```
`POST /api/settings/save` 整个 body 原子写 `settings.json`（`SillyTavern/src/endpoints/settings.js:206-216`）——**没有字段级校验**，所以"导入设置"= 合并 JSON。

### 6.2 Persona（用户人设）

存储：`settings.power_user.personas` + `persona_descriptions`（`SillyTavern/public/scripts/power-user.js:286-288`）。
- `personas: Record<avatarId, personaName>`（avatarId = `User Avatars/xxx.png` 的文件名）
- `persona_descriptions[avatarId] = { description, position, depth, role, lorebook, title, connections? }`（`personas.js:515-530`）
- `position` 枚举：`IN_PROMPT:0, AFTER_CHAR:1(废弃), TOP_AN:2, BOTTOM_AN:3, AT_DEPTH:4, NONE:9`（`personas.js:88-98`）；`DEFAULT_DEPTH = 2`、`DEFAULT_ROLE = 0`（`:104-105`）
- `connections[] = {type:'character'|'group', id}`：人设自动绑定某角色/群（`personas.js:71-75`）
- 另有 `default_persona`（`power-user.js:287`）与 persona 锁（chat/character/default，`personas.js:77-85`）

### 6.3 Tags（角色标签/文件夹）

`SillyTavern/public/scripts/tags.js`：
- 定义列表 `settings.tags: Tag[]`，`Tag = { id: uuid, name, folder_type, color, color2, create_date }`（`:327-332`、`:1149-1157`）
- 归属映射 `settings.tag_map: Record<entityKey, tagId[]>`，`entityKey` 是角色 avatar / 群名（`:351,615-616,628`）
- 默认标签：`Plain Text / OpenAI / W++ / Boostyle / PList / AliChat`（`:294-299`）
- 导入校验：`tags` 必须数组、`tag_map` 必须对象（`:1827`）

---

## 7. 扩展（Extension）清单

- 路径：内置 `public/scripts/extensions/<name>/`、第三方 `public/scripts/extensions/third-party/<name>/`、用户级 `{userData}/extensions/`（`SillyTavern/src/constants.js:5-6,37`）
- 必须有 `manifest.json`，否则该目录被跳过（`SillyTavern/src/endpoints/extensions.js:490-503`）
- **真实 manifest 样例**（`SillyTavern/public/scripts/extensions/regex/manifest.json`，全文 14 行）：
```json
{
    "display_name": "Regex",
    "loading_order": 1,
    "requires": [],
    "optional": [],
    "js": "index.js",
    "css": "style.css",
    "author": "kingbri",
    "version": "1.0.0",
    "homePage": "https://github.com/SillyTavern/SillyTavern",
    "hooks": { "activate": "init" }
}
```
字段：`display_name, loading_order, requires[], optional[], js, css, author, version, homePage, hooks{activate}`（`requires/optional` 是**其他扩展名**；`hooks` 目前只有 `activate`）。
- 端点：`/api/extensions/install|update|delete|version|branches|switch|discover|move`；`global=true` 的删除需要 admin（`extensions.js:142-148,442-474`）
- **对 TeaTalk 的意义**：扩展是 JS，无法在 Android 原生复用；但 `extension_settings`（settings.json 内）里的**配置数据**要保留，尤其是 **Regex 扩展的全局正则脚本**（对应角色级 `data.extensions.regex_scripts`，§2.7）。

---

## 8. 连接 PC 端 ST 后端（可行性 + 最小接口集）

### 8.1 鉴权现实（重要，和常见猜测不同）

- 全仓库 **grep `X-SillyTavern-User` = 0 命中**；鉴权只有：
  1. **cookie session**：`cookieSession({ name: getCookieSessionName(), sameSite:'lax', httpOnly:true, secret: getCookieSecret(...) })`（`SillyTavern/src/server-main.js:156-162`）
  2. **CSRF token**：`GET /csrf-token` → `{token}`；请求头 **`x-csrf-token`**（`server-main.js:166-202`）；`--disableCsrf` 时 token 恒为 `'disabled'`（`:203-210`）
  3. 多用户模式下先 `POST /api/users/login`（公开路由 `/api/users`，`server-main.js:244-245`）
- 路由分层：`/api/users` 公开 → `app.use(requireLoginMiddleware)`（`:248`）之后**所有**接口需登录；`GET /version` 返回 `{agent, pkgVersion, gitRevision, gitBranch, commitDate, isLatest}`（`:272-275`）
- `hostWhitelistMiddleware`（`:150`）会校验 Host 头：**从手机直连时要用局域网 IP 加入 `whitelist`，或用 `--listen` 配置**（否则 403 `host-not-allowed`，页面模板见 `default/content/errors/host-not-allowed.html`）。

**结论**：Android 直连可行，但要自己实现 cookie jar（`connect.sid` 之类签名 cookie）+ 每次拿 `/csrf-token`。文档 `SillyTavern-Docs/Administration/remote-connections.md` 也建议用 **`--listen` + 白名单**；更省事的路径是走官方 **`/api/users/login`**（若开了 `enableUserAccounts`）。

### 8.2 最小接口集（"把 ST 当后端"）

生成/对话相关：
| 用途 | 接口 | 证据 |
|---|---|---|
| 模型列表/校验 | `POST /api/backends/chat-completions/status`、`/api/backends/text-completions/status` | `server-startup.js:176-180` |
| 生成 | `POST /api/backends/chat-completions/generate`、`POST /api/backends/text-completions/generate` | 同上 |
| 停止 | `POST /api/backends/chat-completions/stop` | 同上 |

数据 CRUD（全部 `Content-Type: application/json`）：
| 用途 | 接口 | Body |
|---|---|---|
| 角色列表 | `POST /api/characters/all` | `{}`（可用 `shallow`） |
| 角色详情 | `POST /api/characters/get` | `{avatar_url}` |
| 角色导入 | `POST /api/characters/import` (multipart) | `{file_type, preserved_name?}` + file |
| 角色导出 | `POST /api/characters/export` | `{format:'png'|'json', avatar_url}` |
| 角色编辑 | `POST /api/characters/edit`、`/edit-attribute`、`/merge-attributes` | 前端表单字段 |
| 聊天列表 | `POST /api/characters/chats` | `{avatar_url, simple?, metadata?}` |
| 聊天读 | `POST /api/chats/get` | `{ch_name, file_name, avatar_url}` → **数组** |
| 聊天写 | `POST /api/chats/save` | `{ch_name, file_name, chat:[header,...msgs], avatar_url, force?}` |
| 聊天删/改名/导出 | `/api/chats/delete` `/rename` `/export` | — |
| 群聊 | `/api/groups/all` `/create` `/edit` `/delete`；`/api/chats/group/{get,info,delete,save,import}` | 见 `groups.js`、`chats.js:872-940` |
| 世界书 | `/api/worldinfo/{list,get,import,edit,delete}` | 见 §3.1 |
| 设置/预设 | `POST /api/settings/get`（一次拿全部）、`POST /api/settings/save` | 见 §5/§6 |
| 预设 CRUD | `POST /api/presets/{save,delete,restore}` | `{name, preset, apiId}` |
| 主题 | `POST /api/themes/{save,delete}` | — |
| 快速回复 | `POST /api/quick-replies/{save,delete}` | — |
| 人设头像 | `/api/avatars/{get,upload,delete}` | — |
| 扩展开关 | `/api/extensions/*` | — |

完整挂载表：`SillyTavern/src/server-startup.js:140-188`（48 个 router）。

### 8.3 明确标注"不稳定/不建议依赖"的接口

| 接口 | 风险 | 证据 |
|---|---|---|
| `/api/settings/save` 整体覆盖 | 无字段校验、无版本号；并发写会互相覆盖 | `settings.js:206-216` |
| `/api/characters/edit` 系列 | Body 是**前端表单形状**，随 UI 改动 | `characters.js:1101-1140` |
| `/api/backends/*/generate` | 请求/响应随 API 源（openai/claude/google/…）变化，`chat_completion_source` 分支极多 | `src/endpoints/backends/*` |
| `/api/extensions/*` | 依赖 git 与磁盘布局 | `extensions.js` |
| `/api/characters/export` (png) | 会重写 PNG chunk（ccv3 由 V2 伪造），**不是原始字节** | `characters.js:1646-1687` + `character-card-parser.js:32-42` |
| `/api/chats/import` | 依赖第三方格式识别顺序，误判会 `error:true` | `chats.js:800-813` |
| 旧路径（`/savetheme` 等） | 已 302 到新路径 | `server-startup.js:100-133` |

### 8.4 两种产品路线对比

| 路线 | 优点 | 缺点 |
|---|---|---|
| **A. TeaTalk 自己解析文件（离线导入）** | 不依赖 PC、不依赖 ST 版本、可独立演进；本文档即为规格 | 需要实现 PNG chunk、Handlebars、prompt 组装、宏系统（`{{char}}/{{user}}/{{random}}` 等，见 `Usage/macros.md`） |
| **B. 直连 PC 端 ST（当后端）** | 0 解析成本；生成/世界书/分支逻辑全在 PC 上跑；Android 只是壳 | 必须保持 PC 开机；`hostWhitelist`/CSRF/cookie 都是坑；版本升级后私有接口可能变；**离线不可用** |
| **C. A+B 混合（推荐）** | 平时离线用 A；用户可选"连接 PC 上的 ST"走 B 做对照/回写 | 需要两套代码路径，但共享 data class |

---

## 9. 导入优先级与解析风险

### 9.1 建议实现顺序（按"用户感知价值 / 解析成本"）

| P | 内容 | 理由 |
|---|---|---|
| P0 | PNG 角色卡（V2/V3）+ 内嵌 `character_book` 解包 | 老用户第一诉求；占资产 90% |
| P0 | 世界书 `worlds/*.json` + 角色绑定 `extensions.world` + 全局 `globalSelect` | 角色"记得住"的关键 |
| P0 | 单人 chat jsonl（含 header/swipes/original_avatar） | 聊天记录是最难重建的资产 |
| P1 | `.charx`、`.json` 角色卡 | 覆盖进阶用户 |
| P1 | 群定义 `groups/*.json` + `group chats/*.jsonl` | 群聊用户占比不高但口碑关键 |
| P1 | Context / Instruct / OpenAI 预设（尤其 `prompts` + `prompt_order`） | 决定"输出风格一致性" |
| P2 | settings.json 的 persona / tags / tag_map / 快速回复 / 主题 | 迁移体验加分 |
| P3 | 扩展配置（regex_scripts 全局版）、背景图、立绘 sprite | 锦上添花 |

### 9.2 解析风险清单（按严重度）

| 风险 | 具体表现 | 对策 |
|---|---|---|
| **V3 幻觉** | 读到 `ccv3` chunk 就以为是真 V3，按 V3 规范找 `data.assets` 等字段，结果为空 | 以「`spec` 字段值」判版本，`ccv3` 只当来源优先级（§2.2 第 3 点） |
| **严格校验误杀** | 照 `TavernCardValidator` 严格实现 → 大量真实卡因缺 1 个字段被拒 | 宽松解析 + 默认值表（§2.5） |
| **`enabled` vs `disable` 取反** | CCv2 用 `enabled`，ST 文件用 `disable`，往返转换忘记取反 → 世界书条目全部反选 | 转换层单一入口，写单测（§3.7） |
| **`role` 类型不一致** | 世界书 `role` 是**数字**；`depth_prompt.role` 是**字符串** | 两个独立 enum，别复用 |
| **`position` 有损往返** | ST→CCv2 把 2..7 全塌成 `after_char` | 往返时优先用 `extensions.position` + `originalData`（§3.7） |
| **entries 是对象还是数组** | CCv2 数组 / ST 对象，混用导致解析崩 | 双分支解析，统一成 `Map<uid, Entry>` |
| **chat 首行不是消息** | 把 header 当消息渲染（出现 `user_name/unused` 气泡） | 首行含 `chat_metadata` 即 header；无 header 的旧文件也要兼容 |
| **integrity 冲突** | 导入后写回 ST 报 integrity mismatch | 导入时删 `chat_metadata.integrity` 或重算（§4.7） |
| **尾部截断** | 崩溃产生的半行 JSON 让整份聊天不可读 | 逐行 tryParse，末行失败丢弃（`chats.js:489-499` 同策略） |
| **预设只导参数** | `prompts/prompt_order` 丢失 → 输出风格完全变 | 预设导入必须检查这两个键存在 |
| **Handlebars `story_string`** | 不支持模板 → 角色定义顺序错乱 | 至少支持 `{{#if}}/{{/if}}/{{var}}/{{trim}}` 子集，或保存源码待后处理 |
| **avatar 文件名当主键** | 改名会断 checkpoint/chats 链接 | 内部用稳定 id，avatar 名只做导入期映射 |
| **编码** | ST 文件为 UTF-8 无 BOM；Windows 用户可能产生 BOM/CRLF | 读时剥 BOM，`\r\n` 归一化成 `\n`（jsonl 按 `\n` 切分） |
| **同名冲突** | 两个角色同名（`getPngName` 加数字后缀） | 导入时列冲突让用户选择"覆盖/重命名/跳过" |

---

## 10. Kotlin 映射要点（字段清单见 §2.4 / §3.3 / §4.3 / §4.5）

- **类型映射**：`Record<string, any>` → `Map<String, JsonElement>`，未知键必须原样保留（ST 靠 `json_data`/`extensions` 透传外来字段，丢了回写即失真）；`send_date/gen_started/gen_finished` 是 `string|number|Date`（`global.d.ts:21`），用 `JsonElement` 存，禁止强转 Long。
- **同名不同型（最易踩）**：世界书 `entry.role: Int?`（0=system/1=user/2=assistant，`world-info.js:5656`）；`data.extensions.depth_prompt.role: String`（`"system"|"user"|"assistant"`，`char-data.js:69-85`）。
- **极性相反**：世界书 `disable: Boolean`（true=停用）↔ character_book `enabled: Boolean`（`characters.js:663-722`），转换必须取反。
- **`null` = 未设置/继承全局**：`scanDepth/caseSensitive/matchWholeWords/useGroupScoring/sticky/cooldown/delay` 的 `null` 不能用 `false/0` 顶替（`world-info.js:4082-4125`）；其余字段按 §3.3 默认值填。
- **WorldBook.entries 是对象不是数组**：`{"<uid>": {...}}`，Kotlin 用 `entries: Map<Int, WorldEntry>`，uid 解析失败回退 `entry.uid`（§3.2）。
- **稳定主键**：角色 = `characters/*.png` 文件名（ST 的唯一主键，改名即换卡）；聊天 = `(avatarFileName, fileName)`；checkpoint 用 `chat_metadata.main_chat` 建父子关系。
- **无损兜底**：`swipes/swipe_info/tags/prompts/prompt_order` 用 JSON 文本列原样存，保证 ST→TeaTalk→ST 往返不丢字段。

---

## 11. 证据索引（速查）

| 主题 | 文件:行 |
|---|---|
| PNG tEXt 读写 | `SillyTavern/src/character-card-parser.js:15-46,54-78` |
| 卡版本校验 / V2 类型 | `SillyTavern/src/validator/TavernCardValidator.js:32-48,55-64,104-141,143-168`；`SillyTavern/src/types/spec-v2.d.ts:1-52` |
| 卡运行期 typedef | `SillyTavern/public/scripts/char-data.js:2-102,105-123` |
| 读卡/写卡/字段抬升 | `SillyTavern/src/endpoints/characters.js:450-461,498-502,504-657` |
| 世界书 ⇄ character_book | `SillyTavern/src/endpoints/characters.js:663-722`；`SillyTavern/public/scripts/world-info.js:2687-2724,5617-5674` |
| 世界书端点 / 默认值 / 枚举 | `SillyTavern/src/endpoints/worldinfo.js:17-157`；`SillyTavern/public/scripts/world-info.js:27-100,795-812,855-869,4082-4149` |
| 注入位置/角色枚举 | `SillyTavern/public/script.js:484-500` |
| chat jsonl 序列化/读取 | `SillyTavern/src/endpoints/chats.js:420-543,577-619` |
| chat header / ChatMessage | `SillyTavern/public/script.js:7396-7459,7710-7742`；`SillyTavern/public/global.d.ts:21,23,45-48,58-64,66-88,90-128` |
| checkpoint/分支 | `SillyTavern/public/scripts/bookmarks.js:47,90-127`；`SillyTavern-Docs/Usage/Characters/chatfilemanagement.md:44-63` |
| 群聊定义 | `SillyTavern/public/global.d.ts:26-43`；`SillyTavern/src/endpoints/groups.js:113-190` |
| 设置/预设/主题/快速回复 | `SillyTavern/src/endpoints/settings.js:206-296`；`SillyTavern/src/endpoints/presets.js:16-103`；`SillyTavern/src/endpoints/themes.js:10-38`；`SillyTavern/src/endpoints/quick-replies.js:10-32` |
| settings 保存结构 | `SillyTavern/public/script.js:8051-8109`（payload :8069-8094） |
| persona | `SillyTavern/public/scripts/personas.js:71-105,515-537` |
| tags | `SillyTavern/public/scripts/tags.js:294-332,615-628,1149-1157,1827` |
| 扩展 manifest | `SillyTavern/src/endpoints/extensions.js:20-33,142-148,442-503` |
| CSRF/cookie/API 挂载 | `SillyTavern/src/server-main.js:150-210,244-275`；`SillyTavern/src/server-startup.js:140-188` |
| 目录模板 | `SillyTavern/src/constants.js:9,16-48` |
| 文档：世界书字段语义 | `SillyTavern-Docs/Usage/worldinfo.md:65-83,85-163,335-455` |
