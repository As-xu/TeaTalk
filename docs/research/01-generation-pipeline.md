# SillyTavern 生成管线规格（Chat Completion 路径）

> **对象**：`D:\Project\TeaTalkApp\SillyTavern`（任务给定 v1.19.0，Node + 原生 JS），只读研究。
> **目标**：让没读过 ST 源码的 Kotlin 工程师实现出**行为等价**的一次生成。
> **证据**：`文件:行号`，路径相对 `SillyTavern/public/`；未标注的 `xxx.js` 指 `public/scripts/xxx.js`。写"存疑/未找到"处即源码无明确答案。
> **范围**：`main_api === 'openai'`（所有 `chat_completion_source`）。Text Completion 的 story string/instruct 见 §13，本管线不用。

规模参考：`script.js` 12599 行、`openai.js` 7396、`PromptManager.js` 2144、`world-info.js` 6408、`tokenizers.js` 1231。

## 0. 结论速览

1. **管线一句话**：`Generate()` → `coreChat` → 世界书扫描 → 注入收集 → `PromptManager` 有序提示词 → `preparePromptsForChatCompletion` 填系统提示词 → `populateChatCompletion` 按预算填入槽位 → `squashSystemMessages` → `POST /api/backends/chat-completions/generate` → 流式增量 → 落 swipe。
2. 最终 messages **不是拼字符串，而是按"提示词顺序下标"组织的稀疏槽位数组**（`ChatCompletion.messages.collection[index]`），`getChat()` 摊平。
3. 世界书是**独立一次扫描**（先于提示词组装），产出 8 类结果：`worldInfoBefore/After`、`ANTop/ANBottom`（并入作者注）、`EMTop/EMBottom`（示例位）、`atDepth`（按深度注入）、`outlet`（宏取用）。
4. 扫描核心 = 倒序 chat + 扫描深度（默认 2）+ 递归缓冲 + sticky/cooldown/delay + 概率 + 包含组 + token 预算（默认 25%）。
5. 宏每环节都调 `substituteParams`，固定三段顺序 `preEnv → env → postEnv`；变量只有 **local(chat) / global** 两域。
6. 预算减法式：`budget = openai_max_context - openai_max_tokens`，每加一条立刻扣；历史从**最新往最旧**加，加不下即 `break`。
7. 所有默认值集中在 `openai.js:411-518`（`default_settings`）+ `PromptManager.js:2001-2136`（默认提示词与顺序），直接照抄。
8. 请求 = `messages`（第 10 节）+ 采样参数 `generate_data`（第 12 节），由 `createGenerationParameters` 合并。
9. 可砍：向量记忆/ChromaDB、工具调用、多模态内联、reasoning 签名、群聊。不可砍：默认提示词顺序、世界书匹配语义、预算算法、宏顺序。
10. 4 处必须逐字复刻：**默认 prompt_order 与默认文本、世界书匹配语义、token 预算算法、宏替换顺序**。

## 1. 端到端调用链
```
UI 点击发送
└─ script.js:Generate(type, {automatic_trigger, quiet_prompt, ...}, dryRun)      [script.js:4290]
 1  GENERATION_STARTED / GENERATION_AFTER_COMMANDS                              [script.js ~4300-4360]
 2  生成锁 + abort + 群聊包装（每个成员再跑一次 Generate）                        [script.js ~4380-4450]
 3  getCharacterCardFields()                                                    [script.js:3476]
 4  深度提示词 setExtensionPrompt(DEPTH_PROMPT…)                                 [script.js ~4470]
 5  coreChat = 过滤后的历史（正则/推理已处理）                                    [script.js:4496]
 6  chatForWI = coreChat 反向 + 可选 name 前缀 + globalScanData                   [script.js:4624]
 7  getWorldInfoPrompt(chatForWI, this_max_context, dryRun, globalScanData)      [script.js:4635 → world-info.js:892]
       └─ checkWorldInfo(...)                                                    [world-info.js:4709]
 8  世界书示例插回 mesExamplesArray；atDepth/outlet → extension_prompts            [script.js ~4639-4681]
 9  作者注 setFloatingPrompt + 人设描述 addPersonaDescriptionExtensionPrompt      [script.js ~4684]
10  oaiMessages = setOpenAIMessages(coreChat); setOpenAIMessageExamples(...)      [script.js ~4833]
11  prepareOpenAIMessages({...}, dryRun)                                          [script.js:5285 → openai.js:1542]
       ├ preparePromptsForChatCompletion(...)                                    [openai.js:1367]
       ├ populateChatCompletion(...)                                             [openai.js:1185]
       └ squashSystemMessages() / getChat()                                      [openai.js:1604-1616]
12  createGenerationParameters(...) → generate_data                              [openai.js:2691]
13  POST /api/backends/chat-completions/generate                                 [openai.js:3138]
14  流式增量 → text/reasoning/toolCalls                                          [openai.js:3222]
15  落库 swipe + token 统计 + GENERATION_ENDED                                   [script.js ~5385-5450]
```

| 中间变量 | 含义 | 证据 |
|---|---|---|
| `coreChat` | 过滤后聊天（新→旧），已应用正则/推理 | `script.js:4496` |
| `chatForWI` | 世界书扫描用纯文本（旧→新） | `script.js:4624` |
| `globalScanData` | 可被世界书匹配的卡片/人设字段 | `script.js:4626-4634` |
| `extension_prompts` | 全局注入表 `key → {value,position,depth,scan,role,filter}` | `script.js:627` |
| `oaiMessages` | `setOpenAIMessages(coreChat)`（新→旧） | `script.js ~4833` |
| `this_max_context` | `getMaxPromptTokens()` = 上下文 − 回复预留 | `script.js ~4610` |

`globalScanData` 字段（`script.js:4626-4634`）：`personaDescription, characterDescription, characterPersonality, characterDepthPrompt, scenario, creatorNotes, trigger`（`trigger = GENERATION_TYPE_TRIGGERS.includes(type) ? type : 'normal'`）。

## 2. 证据地图

| 关注点 | 文件 | 关键行 |
|---|---|---|
| 入口/事件/群聊 | `script.js` | 4290、4455、4635、5285、5329、5385 |
| 消息数组构造 | `openai.js` | 570、656、789、810、885、1101、1185、1367、1542 |
| 默认值/默认文本 | `openai.js` | 102-115、411-518；`PromptManager.js:2001-2136` |
| token 容器 | `openai.js` | 3420-3482、3511+、3808+、3917+ |
| 请求/流 | `openai.js` | 2691、3138、3222 |
| 提示词顺序 | `PromptManager.js` | 37、80、201、1277、1516、2001 |
| 世界书 | `world-info.js` | 27-104、199、479、855、892、4590、4709、5388 |
| 宏 | `macros.js` | 610；`macros/definitions/*.js`；`variables.js:22-102` |
| 注入 | `script.js` | 3203、3281、3301 |
| 作者注 | `authors-note.js` | 324 |
| reasoning | `reasoning.js` | 112、671、744、1461、1503 |
| 模板 | `power-user.js` | 90、2234；`instruct-mode.js:301-650` |
| tokenizer | `tokenizers.js` | 16、569、846 |

## 3. 默认设置与预设字段（`openai.js:411-518` 照抄）

| 字段 | 默认 | 字段 | 默认 |
|---|---|---|---|
| `chat_completion_source` | `'openai'` | `openai_max_context` | 4095（`max_4k`）|
| `openai_max_tokens` | 300 | `temperature` | 1.0 |
| `frequency_penalty` / `presence_penalty` | 0 / 0 | `top_p` | 1.0 |
| `top_k` / `min_p` / `top_a` | 0 / 0 / 0 | `repetition_penalty` | 1 |
| `stream_openai` | `false` | `names_behavior` | `DEFAULT`(0) |
| `send_if_empty` | `''` | `squash_system_messages` | `false` |
| `continue_prefill` | `false` | `show_thoughts` | `true` |
| `n` | 1 | `openai_max_stop_strings` | **4**（`openai.js:144`）|
| `reverse_proxy` | `''` | `reasoning_effort` | `auto` |
| `wi_format` | `{0}` | `personality_format` / `scenario_format` | `{{personality}}` / `{{scenario}}` |

默认文本（`openai.js:102-115`，逐字）：
```
main            : Write {{char}}'s next reply in a fictional chat between {{charIfNotGroup}} and {{user}}.
impersonation   : [Write your next reply from the point of view of {{user}}, using the chat history so far as a guideline for the writing style of {{user}}. Don't write as {{char}} or system.]
new_chat_prompt : [Start a new Chat]        new_group_chat_prompt : [Start a new group chat. Group members: {{group}}]
new_example_chat_prompt : [Example Chat]    continue_nudge_prompt : [Continue your last message without repeating its original content.]
group_nudge_prompt : [Write the next reply only as {{char}}.]        nsfw / jailbreak : ''
```

枚举（`openai.js:206-218`、`world-info.js:33/855`）：
```js
character_names_behavior = { NONE:-1, DEFAULT:0, COMPLETION:1, CONTENT:2 }
continue_postfix_types   = { NONE:'', SPACE:' ', NEWLINE:'\n', DOUBLE_NEWLINE:'\n\n' }
world_info_logic         = { AND_ANY:0, NOT_ALL:1, NOT_ANY:2, AND_ALL:3 }
world_info_position      = { before:0, after:1, ANTop:2, ANBottom:3, atDepth:4, EMTop:5, EMBottom:6, outlet:7 }
```

> 预设（`presets/chatCompletion/*.json`）就是 `oai_settings` 的一份快照，字段同上；`settings.json` 里 `chat_completion_settings` / `openai_settings` 同构。

## 4. 步骤 1：`Generate()`
```js
export async function Generate(type, {
  automatic_trigger, force_name2, quiet_prompt, quietToLoud, skipWIAN,
  force_chid, signal, quietImage, quietName, jsonSchema = null, depth = 0
} = {}, dryRun = false)                                    // script.js:4290
```

* `type ∈ { undefined|'normal','regenerate','continue','impersonate','quiet','swipe' }`；`quiet` 不产生聊天消息。
* `dryRun=true` 只组装不请求（提示词查看器/token 统计）；无活动角色时 `prepareOpenAIMessages` 直接返回 `[null,false]`（`openai.js:1561`）。
* `quiet_prompt` 临时写入 `QUIET_PROMPT` 注入，世界书扫描时可被 `{{input}}` 读到，扫描后清空（`script.js:4623/4636`）。
* `signal` = AbortController，透传 fetch；Kotlin 用 `Job.cancel()`。
* 群聊：每个成员跑一遍并注入 `groupNudge`。
* 事件：`GENERATION_STARTED/AFTER_COMMANDS/AFTER_DATA/ENDED`、`CHAT_COMPLETION_PROMPT_READY`、`WORLD_INFO_ACTIVATED`、`WORLDINFO_SCAN_DONE`。
* `script.js:4490`：`chat[0].mes = substituteParams(chat[0].mes)` —— **首条消息发送前替换一次宏**。

## 5. 步骤 2：`coreChat` 与 `setOpenAIMessages`

`script.js:4455-4530`：
```js
// 空 textarea 且非 dryRun/send_if_empty 时提前返回
let coreChat = chat.filter(m => !m.is_system || (m.extra?.tool_invocations?.length > 0));   // 4496
if (末条是未完成 swipe) coreChat.pop();
for (let i = 0; i < coreChat.length; i++) {
  const depth = coreChat.length - i - (isContinue ? 2 : 1);           // 给宏用
  m.mes = getRegexedString(m.mes, m.is_user ? USER_INPUT : AI_OUTPUT, { isPrompt: true, depth });
  if (m.extra?.file) m.mes += appendFileContent(m.extra);
  if (m.extra?.append_title && m.extra?.title) m.mes += `\n\n${m.extra.title}`;
}
// 推理内容从后往前拼到紧邻的 assistant 消息（PromptReasoning，见 §12.3）
```

`setOpenAIMessages(chat)`（`openai.js:570-649`）产出**新→旧**数组：
```js
for (let i = chat.length - 1; i >= 0; i--) {
  let role = chat[j].is_user ? 'user' : 'assistant';
  if (chat[j].extra?.type === NARRATOR) role = 'system';
  // names_behavior: DEFAULT(0) → 群聊非 user 消息/sendas 加 "name: "；CONTENT(2) → 除 NARRATOR 全加；COMPLETION(1) → 不加（用 name 字段）
  content = content.replace(/\r/g, '');
  messages[i] = { role, content, name, media, invocations, signature?, reasoning? };  // signature/reasoning 仅同 api+model 时带
}
// IGNORE_SYMBOL 消息整体跳过（数组留空洞，后续过滤）
```

> **顺序约定**：进入 `populateChatCompletion` 时 `messages[0]` 是最新消息；`populationInjectionPrompts` 依赖它做深度插入，最后 `reverse()` 成旧→新（`openai.js:873`）。

## 6. 步骤 3：世界书（`world-info.js`）

### 6.1 条目模型与默认值
```js
entry = { uid, key:[], keysecondary:[], comment:'', content:'',
  constant:false, vectorized:false, selective:true, selectiveLogic:0,
  order:100, position:0, disable:false, ignoreBudget:false,
  excludeRecursion:false, preventRecursion:false,
  matchPersonaDescription/matchCharacterDescription/matchCharacterPersonality
  /matchCharacterDepthPrompt/matchScenario/matchCreatorNotes:false,
  delayUntilRecursion:0, probability:100, useProbability:true,
  depth:4, outletName:'', group:'', groupOverride:false, groupWeight:100,
  scanDepth:null, caseSensitive:null, matchWholeWords:null, useGroupScoring:null,
  automationId:'', role:0, sticky:null, cooldown:null, delay:null, triggers:[],
  characterFilter:{names:[],tags:[],isExclude:false},
  decorators:['@@activate'|'@@dont_activate'] }
```

全局默认（`world-info.js:69-82`）：`world_info_depth` **2**、`min_activations` 0、`min_activations_depth_max` 0、`budget` **25**(%)、`budget_cap` 0、`include_names` true、`recursive` false、`max_recursion_steps` 0、`case_sensitive` false、`match_whole_words` false、`use_group_scoring` false、`character_strategy` 1(`character_first`)。

### 6.2 来源与排序 `getSortedEntries()`（`4590-4650`）
```js
const [global, character, chatLore, persona] =
  await Promise.all([getGlobalLore(), getCharacterLore(), getChatLore(), getPersonaLore()]);
// character = 卡片 primary world + 聊天绑定书 + extraBooks；chat = /lorebook 绑定书；persona = 人设绑定书
switch (world_info_character_strategy) {
  case 0: all = [...global, ...character].sort(sortFn); break;      // evenly
  case 1: all = [...character.sort(sortFn), ...global.sort(sortFn)]; break;
  case 2: all = [...global.sort(sortFn), ...character.sort(sortFn)]; break;
}
all = [...chatLore.sort(sortFn), ...personaLore.sort(sortFn), ...all];
// 每条补 world:worldName；parseDecorators 从 content 头部摘出 @@activate/@@dont_activate
// sortFn = (a,b) => b.order - a.order  （降序，world-info.js:88）
```

### 6.3 扫描缓冲 `WorldInfoBuffer`（`199-474`）
```kotlin
class WorldInfoBuffer(val messages: List<String>, val globalScanData: GlobalScanData) {
  val depthBuffer: List<String> = messages           // depth 0 = 最新
  val recurseBuffer = mutableListOf<String>()
  val injectBuffer  = mutableListOf<String>()
  var skew = 0
  fun depth() = worldInfoDepth + skew                // 当前有效扫描深度

  fun get(entry: Entry, scanState: Int): String {
    val depth = entry.scanDepth ?: depth()
    if (depth <= 0) return ""
    var s = "\u0001" + depthBuffer.take(depth).joinToString("\n\u0001")
    if (entry.matchPersonaDescription)    s += "\n\u0001" + globalScanData.personaDescription
    // …characterDescription / characterPersonality / characterDepthPrompt / scenario / creatorNotes
    if (injectBuffer.isNotEmpty())        s += "\n\u0001" + injectBuffer.joinToString("\n\u0001")
    if (recurseBuffer.isNotEmpty() && scanState != MIN_ACTIVATIONS)
        s += "\n\u0001" + recurseBuffer.joinToString("\n\u0001")
    return s
  }

  fun matchKeys(hay: String, needle: String, entry: Entry): Boolean {
    parseRegexFromString(needle)?.let { return it.containsMatchIn(hay) }            // /re/flags 键
    val cs = entry.caseSensitive ?: worldInfoCaseSensitive
    val h = if (cs) hay else hay.lowercase()
    val n = if (cs) needle else needle.lowercase()
    val whole = entry.matchWholeWords ?: worldInfoMatchWholeWords
    return if (!whole) h.contains(n)
           else if (n.trim().split(Regex("\\s+")).size > 1) h.contains(n)
           else Regex("(?:^|\\W)(${Regex.escape(n)})(?:$|\\W)").containsMatchIn(h)
  }
  fun getScore(entry, state) = 主键命中数 + (AND_ANY/AND_ALL 时次键命中数)           // 428-473
}
```

`\x01` 分隔符防跨消息误匹配。主键/次键每次比较前先 `substituteParams(key)` 再 `trim()`（`4914-4917`）。

### 6.4 主循环 `checkWorldInfo()`（`4709-5282`）
```kotlin
fun checkWorldInfo(chat: List<String>, maxContext: Int, dryRun: Boolean, g: GlobalScanData): WiResult {
  val buffer = WorldInfoBuffer(chat, g)
  for ((k, p) in extensionPrompts) if (p.scan) buffer.addInject(getExtensionPrompt(k, ...))
  var budget = max(1, round(worldInfoBudgetPercent * maxContext / 100.0))
  if (worldInfoBudgetCap > 0) budget = min(budget, worldInfoBudgetCap)

  val sorted = getSortedEntries()
  val timed = WorldInfoTimedEffects(chat, sorted, dryRun); timed.checkTimedEffects()

  var delayLevels = sorted.filter { it.delayUntilRecursion > 0 }.map { if (it.delayUntilRecursion == true) 1 else it.delayUntilRecursion }.distinct().sorted()
  var currentDelayLevel = delayLevels.removeFirstOrNull() ?: 0
  var state = INITIAL; var count = 0; var overflowed = false
  val activated = linkedMapOf<String, Entry>()          // key = "${world}.${uid}"
  val failedProb = hashSetOf<Entry>(); var activatedText = ""

  while (state != NONE) {
    if (worldInfoMaxRecursionSteps > 0 && count >= worldInfoMaxRecursionSteps) break
    count++; var next = NONE; val now = linkedSetOf<Entry>()

    for (entry in sorted) {
      if (entry in failedProb || "${entry.world}.${entry.uid}" in activated) continue
      if (entry.disable) continue
      if (entry.triggers.isNotEmpty() && g.trigger !in entry.triggers) continue
      if (!passesCharacterFilter(entry)) continue                       // names/tags/isExclude
      val sticky = timed.isActive("sticky", entry); val cooldown = timed.isActive("cooldown", entry)
      if (timed.isActive("delay", entry)) continue
      if (cooldown && !sticky) continue
      if (state != RECURSION && entry.delayUntilRecursion > 0 && !sticky) continue
      if (state == RECURSION && entry.delayUntilRecursion > currentDelayLevel && !sticky) continue
      if (state == RECURSION && worldInfoRecursive && entry.excludeRecursion && !sticky) continue
      if ("@@activate" in entry.decorators) { now += entry; continue }
      if ("@@dont_activate" in entry.decorators) continue
      buffer.getExternallyActivated(entry)?.let { now += it; continue }
      if (entry.constant || sticky) { now += entry; continue }
      if (entry.key.isEmpty()) continue

      val text = buffer.get(entry, state)
      val primary = entry.key.firstOrNull { k -> val s = substituteParams(k).trim(); s.isNotEmpty() && buffer.matchKeys(text, s, entry) } ?: continue
      if (!(entry.selective && entry.keysecondary.isNotEmpty())) { now += entry; continue }
      if (!matchSecondary(entry, text, buffer)) continue                 // 见 6.5
      now += entry
    }

    var newEntries = now.sortedWith(compareByDescending<Entry> { timed.isActive("sticky", it) }.thenBy { sorted.indexOf(it) })
    val textTokens = countTokens(activatedText)
    filterByInclusionGroups(newEntries, activated, buffer, state, timed)  // 6.6
    var newContent = ""; var ignores = newEntries.count { it.ignoreBudget }

    for (entry in newEntries) {
      if (entry.ignoreBudget) ignores--
      if (overflowed && !entry.ignoreBudget) { if (ignores > 0) continue else break }
      if (entry.useProbability && entry.probability != 100 && !timed.isActive("sticky", entry)
          && Random.nextDouble() * 100 > entry.probability) { failedProb += entry; continue }
      entry.content = substituteParams(entry.content)                    // 就地替换一次
      newContent += entry.content + "\n"
      if (!entry.ignoreBudget && textTokens + countTokens(newContent) >= budget) { overflowed = true; continue }
      activated["${entry.world}.${entry.uid}"] = entry
    }

    val success = newEntries.filter { it !in failedProb }
    val successForRecursion = success.filter { !it.preventRecursion }
    if (worldInfoRecursive && !overflowed && successForRecursion.isNotEmpty()) next = RECURSION
    if (worldInfoRecursive && !overflowed && state == MIN_ACTIVATIONS && buffer.hasRecurse()) next = RECURSION
    if (next == NONE && !overflowed && worldInfoMinActivations > 0 && activated.size < worldInfoMinActivations) {
      val overMax = (worldInfoMinActivationsDepthMax > 0 && buffer.depth() > worldInfoMinActivationsDepthMax) || buffer.depth() > chat.size
      if (!overMax) { next = MIN_ACTIVATIONS; buffer.advanceScan() }    // skew++
    }
    if (next == NONE && delayLevels.isNotEmpty()) { next = RECURSION; currentDelayLevel = delayLevels.removeFirst() }
    state = next
    if (state != NONE) { val t = successForRecursion.joinToString("\n") { it.content }
      if (t.isNotEmpty()) { buffer.addRecurse(t); activatedText = t + "\n" + activatedText } }
    emit(WORLDINFO_SCAN_DONE)   // 扩展可改 state/budget/overflow，需读回
  }
  return buildOutput(activated)  // 6.7
}
```

### 6.5 次键逻辑（`4943-4978`）

| `selectiveLogic` | 通过条件 |
|---|---|
| `AND_ANY`(0) | 任一命中 |
| `NOT_ALL`(1) | 任一未命中 |
| `NOT_ANY`(2) | 全部未命中 |
| `AND_ALL`(3) | 全部命中 |

### 6.6 概率 / 预算 / 时间效果 / 包含组

* **概率**（`5028-5049`）：`random()*100 <= probability` 才激活；**sticky 不重掷**；失败进 `failedProbabilityChecks`，本轮不再参与。
* **预算**（`4736-4741`、`5057-5077`）：单位是"世界书文本 token"，不扣其他提示词；第一次超预算即 `overflowed=true`，**放弃后续所有非 ignoreBudget 条目**（不挑小的塞）。
* **时间效果**（`479-793`，状态存 `chat_metadata.timedWorldInfo`，**跨回合持久化**）：
  `sticky=N` 激活后 N 条内保持激活、结束若带 `cooldown` 则立即进冷却；`cooldown=N` 激活后 N 条内禁止再激活（sticky 例外）；`delay=N` 聊天消息数 < N 时永久抑制。
* **包含组**（`5388-5475`）：①先按时间效果过滤组内条目（sticky 组保留 sticky）→ ②`use_group_scoring` 时按 `getScore` 取最高 → ③`groupOverride` 条目按 `sortFn` 取首个 → ④否则按 `groupWeight` 加权随机 1 条 → ⑤若该组本轮已激活过，组内其余全部移除。

### 6.7 输出与插入位置（`5189-5281`）
```kotlin
activated.values.sortedByDescending { it.order }.forEach { e ->
  val content = applyRegex(e.content, WORLD_INFO, depth = if (e.position == atDepth) e.depth ?: 4 else null, isPrompt = true)
  if (content.isEmpty()) return@forEach
  when (e.position) {
    before   -> before.add(0, content)              // unshift
    after    -> after.add(0, content)
    EMTop    -> em.add(0, Em(before, content)); EMBottom -> em.add(0, Em(after, content))
    ANTop    -> anTop.add(0, content);           ANBottom -> anBottom.add(0, content)
    atDepth  -> depthGroup[(e.depth ?: 4, e.role ?: SYSTEM)].add(0, content)
    outlet   -> outlets[e.outletName].add(content)
  }
}
// 按 order 降序遍历 + unshift ⇒ 最终文本内按 order 升序
```

| 产物 | 去向 | 证据 |
|---|---|---|
| `worldInfoBefore/After` | `formatWorldInfo()` → 同名槽位；`wi_format` 默认 `{0}` 不包裹 | `openai.js:789-801`、`1376-1377` |
| `EMEntries` | 按 position unshift/push 到 `mesExamplesArray` | `script.js ~4639-4655` |
| `WIDepthEntries` | `setExtensionPrompt('customDepthWI_{depth}_{role}', text, IN_CHAT, depth, scan=false)` | `script.js ~4664-4681` |
| `outletEntries` | `setExtensionPrompt('customWIOutlet_{name}', text, NONE, 0)`，模板用 `{{outlet::name}}` | `script.js ~4681` |
| `ANTop/ANBottom` | 拼进作者注：`ANTop + "\n" + 原AN + "\n" + ANBottom` | `world-info.js:5268` |

## 7. 步骤 4：宏（`macros.js:610-715`、`script.js:2815-3015`、`variables.js`）

### 7.1 替换顺序（固定三段）
```js
substituteParams(content, {name1, name2, original, group, replaceCharacterCard, groupOverride, additionalMacro, postProcessFn})
  → 若不含 '{{' 直接返回
  → 构造 env（见 7.2）
  → evaluateMacros(content, env, postProcessFn)            // macros.js:610
       preEnvMacros  → env 宏逐 key 替换 → postEnvMacros
       // 每阶段结果都过 postProcessFn；env 用 nonce 处理宏里嵌套宏
```

### 7.2 宏清单（按 `macros/definitions/*.js` 注册，全部为大括号语法，`{{key}}` 大小写不敏感，可带参数 `{{macro::arg}}`）

| 类别 | 宏 |
|---|---|
| 纯文本工具 | `space` `newline` `noop` `trim` `input` `//注释` |
| 随机 | `roll`（`{{roll:1d6}}`）`random`（`{{random::a,b}}`）`pick` `banned` |
| 时间 | `time` `date` `weekday` `isotime` `isodate` `datetimeformat` `idleDuration` `timeDiff`；env 注入的 `time_UTC±N` |
| 聊天查询 | `lastMessage` `lastMessageId` `lastUserMessage` `lastCharMessage` `firstIncludedMessageId` `firstDisplayedMessageId` `lastSwipeId` `currentSwipeId` `allChatRange` |
| 卡片/环境 | `user` `char` `group` `groupNotMuted` `notChar` `charPrompt` `charInstruction` `charDescription` `charPersonality` `charScenario` `persona` `mesExamples` `mesExamplesRaw` `charDepthPrompt` `charCreatorNotes` `charFirstMessage` `charVersion` `original` `model` `system`（旧版 `{{system}}`）|
| 预算 | `maxPrompt` `maxContext` `maxResponse`（及 `*Tokens` 变体）|
| 变量 | `getvar/setvar/addvar/incvar/decvar/hasvar/deletevar/setvarkey/getvarkey` + `global` 同族（`getglobalvar`…）|
| 状态 | `lastGenerationType` `hasExtension` `isMobile` |
| 块语法（新引擎） | `{{#if}} {{else}} {{/if}}`（`macros/definitions/core-macros.js:134/217`），`experimental_macro_engine` 默认 **false** |
| 扩展/内置 | `summary` `authorsNote` `charAuthorsNote` `defaultAuthorsNote` `outlet` 等 |

### 7.3 时机与作用域

* **发送前（组装期）**：卡片字段、世界书条目、每条提示词、最终注入文本各替换一次（`openai.js:1277` `preparePrompt`、`world-info.js:5030`、`script.js:4490/4690/4750`）。
* **显示时**：`messageFormatting(mes, ch_name, ...)` 在渲染前调 `substituteParams(mes, undefined, ch_name)`（`script.js:1800-1808`）；因此同一文本"显示值"与"发送值"可能不同（正则/宏时机不同）。
* **变量作用域**（`variables.js:22-102`）：只有两种 ——
  * local：`chat_metadata.variables`（**随当前聊天保存**，`{{getvar}}`）；
  * global：`extension_settings.variables.global`（随设置保存，`{{getglobalvar}}`）。
  无独立 character/chat 第三域；`resolveVariable(name, scope)` 仅 `local`/`global`/`all`（`variables.js:218-236`）。

## 8. 步骤 5：`PromptManager` 有序提示词

### 8.1 默认提示词与默认顺序（`PromptManager.js:2001-2136`，照抄）

| identifier | role | 默认内容 |
|---|---|---|
| `main` | system | `Write {{char}}'s next reply in a fictional chat between {{charIfNotGroup}} and {{user}}.` |
| `nsfw` | system | `''` |
| `jailbreak` | system | `''` |
| `enhanceDefinitions` | system | `If you have more knowledge of {{char}}, add to the character's lore and personality to enhance them but keep the Character Sheet's definitions absolute.` |
| `worldInfoBefore` `worldInfoAfter` `charDescription` `charPersonality` `scenario` `personaDescription` `dialogueExamples` `chatHistory` | — | marker（仅占位）|

默认顺序（**即最终槽位顺序**）：
```json
["main","worldInfoBefore","personaDescription","charDescription","charPersonality","scenario",
 "enhanceDefinitions","nsfw","worldInfoAfter","dialogueExamples","chatHistory","jailbreak"]
```

### 8.2 `Prompt` 字段与注入语义（`80-199`、常量 `31-37`）
```js
{ identifier, name, role:'system', content, system_prompt:false,
  injection_position: 0,   // RELATIVE(0) 相对 main 插入 / ABSOLUTE(1) 作为 in-chat 注入
  injection_depth: 4,      // ABSOLUTE 时插到倒数第 N 条前
  injection_order: 100,    // 同深度内优先级；=100 的组额外合并 extension_prompts
  injection_trigger: [],   // 空=全部生成类型；否则 includes(type)
  forbid_overrides:false, marker:false, extension:false }
DEFAULT_DEPTH = 4; DEFAULT_ORDER = 100;
```

### 8.3 采集（`1516-1553`、`1277-1290`）
```js
getPromptCollection(type):
  order = getPromptOrderForCharacter(activeCharacter) || global order
  for (entry of order) { p = getPromptById(entry.identifier); if (!p) continue
    if (entry.enabled && shouldTrigger(p, type)) collection.add(preparePrompt(p))
    else if (entry.identifier === 'main') collection.add({...p, content: ''}) }  // main 永远占位
shouldTrigger = p.injection_trigger?.length ? p.injection_trigger.includes(type) : true
preparePrompt = substituteParams(content, {groupOverride?, original?})   // 群聊传成员列表
```

长度告警：`(context - max_tokens)` 使用率 >0.8 且 `chatHistory` ≤500 tokens → danger、≤1500 → warning（`1677-1688`）。

## 9. 步骤 6：`preparePromptsForChatCompletion()`（`openai.js:1367-1516`）
```kotlin
fun prepare(input: Input): PromptCollection {
  // 1) 字段格式化：对"格式模板"做宏替换，而不是往模板里塞字段
  val scenarioText = if (input.scenario.isNotEmpty() && scenarioFormat.isNotEmpty()) substituteParams(scenarioFormat) else input.scenario
  val personality  = if (input.personality.isNotEmpty() && personalityFormat.isNotEmpty()) substituteParams(personalityFormat) else input.personality

  // 2) 系统提示词候选（顺序即"无序提示词"的添加顺序）
  val systemPrompts = mutableListOf(
    Sys("system", formatWorldInfo(input.worldInfoBefore), "worldInfoBefore"),
    Sys("system", formatWorldInfo(input.worldInfoAfter),  "worldInfoAfter"),
    Sys("system", input.charDescription, "charDescription"),
    Sys("system", personality,           "charPersonality"),
    Sys("system", scenarioText,          "scenario"),
    Sys("system", impersonation,         "impersonate"),      // type=impersonate 时非空
    Sys("system", input.quietPrompt,     "quietPrompt"),
    Sys("system", groupNudge,            "groupNudge"),
    Sys("assistant", input.bias,         "bias"))

  // 3) 已知扩展注入（position 来自 injection）
  mapOf("1_memory" to "summary", "2_floating_prompt" to "authorsNote", "3_vectors" to "vectorsMemory",
        "4_vectors_data_bank" to "vectorsDataBank", "chromadb" to "smartContext")
    .forEach { (k, id) -> ext[k]?.takeIf { it.value.isNotEmpty() }?.let { systemPrompts += Sys(it.role, it.value, id, getPromptPosition(it.position)) } }

  // 4) 人设描述（仅 persona_description_position == IN_PROMPT）
  if (personaDescription.isNotEmpty() && personaPos == IN_PROMPT) systemPrompts += Sys("system", personaDescription, "personaDescription")

  // 5) 其他扩展注入：key 清洗为 identifier，position ∈ {BEFORE_PROMPT, IN_PROMPT} 才收
  for ((k, p) in ext) { if (k in known || p.value.isEmpty() || p.position !in setOf(BEFORE_PROMPT, IN_PROMPT)) continue
    if (p.filter?.invoke() == false) continue
    systemPrompts += Sys(p.role, p.value, k.replace(Regex("\\W"), "_"), getPromptPosition(p.position), extension = true) }

  // 6) 合并进 PromptManager 集合（槽位存在则继承注入设置）
  val prompts = promptManager.getPromptCollection(type)
  for (sp in systemPrompts) {
    prompts.get(sp.identifier)?.let { cp -> sp.injectionPosition = cp.injectionPosition ?: sp.injectionPosition
      sp.injectionDepth = cp.injectionDepth ?: sp.injectionDepth
      sp.injectionOrder = cp.injectionOrder ?: sp.injectionOrder; sp.role = cp.role ?: sp.role }
    val prepared = promptManager.preparePrompt(sp)
    val i = prompts.index(sp.identifier)
    if (i != -1) prompts.collection[i] = prepared else prompts.add(prepared)
  }

  // 7) 覆盖：卡片 system_prompt → main；post_history_instructions → jailbreak（forbid_overrides 除外）
  prompts.get("main")?.let { p -> if (systemPromptOverride.isNotEmpty() && !p.forbidOverrides && !disabledForCharacter("main")) {
    val orig = p.content; p.content = systemPromptOverride
    prompts.override(promptManager.preparePrompt(p, orig), prompts.index("main")) } }
  return prompts
}
getPromptPosition: BEFORE_PROMPT→"start"，IN_PROMPT→"end"，否则 false   // 1140-1150
```

`formatWorldInfo(value) = stringFormat(wi_format /* {0} */, value)`（`789-801`）。

## 10. 步骤 7：`populateChatCompletion()`（`openai.js:1185-1347`，照抄）

数据结构：`ChatCompletion.messages.collection` = **稀疏数组**，下标 = 提示词槽位序号，元素 = `MessageCollection`。
```kotlin
suspend fun populateChatCompletion(prompts: PromptCollection, cc: ChatCompletion, opt: Opts) {
  suspend fun add(source: String, target: String? = null) {
    if (!prompts.has(source)) return
    if (promptManager.isPromptDisabledForActiveCharacter(source) && source != "main") return
    val p = prompts.get(source); if (p.injectionPosition == ABSOLUTE) return    // 绝对注入不占槽位
    val c = MessageCollection(source); c.add(Message.fromPrompt(p))
    cc.add(c, if (target != null) prompts.index(target) else prompts.index(source))
  }

  cc.reserveBudget(3)                                    // 每条回复固定的 assistant 起始开销
  // A. 角色与世界信息
  add("worldInfoBefore"); add("main"); add("worldInfoAfter")
  add("charDescription"); add("charPersonality"); add("scenario"); add("personaDescription")

  // B. 控制提示词（永远最后）
  cc.setOverriddenPrompts(prompts.overriddenPrompts)
  val control = MessageCollection("controlPrompts")
  if (opt.type == "impersonate") control.add(Message.fromPrompt(prompts.get("impersonate")))
  val quiet = Message.fromPrompt(prompts.get("quietPrompt"))
  if (quiet.content.isNotEmpty()) control.add(quiet)
  cc.reserveBudget(control)

  // C. 有序提示词
  (listOf("nsfw", "jailbreak") + prompts.collection.filter { !it.systemPrompt && it.injectionPosition != ABSOLUTE }.map { it.identifier })
    .forEach { add(it) }
  add("enhanceDefinitions")
  if (opt.bias.isNotBlank()) add("bias")

  // D. 相对 main 的注入（作者注/摘要/向量/扩展）
  fun injectToMain(p: Prompt, pos: String) =
    if (cc.has("main")) cc.insert(Message.fromPrompt(p), "main", pos)
    else { val i = absolutePrompts.indexOfFirst { it.identifier == "main" }
           if (i >= 0) absolutePrompts.add(if (pos == "end") i + 1 else i, p.copy(role = main.role, injectionPosition = main.injectionPosition, injectionDepth = main.injectionDepth, injectionOrder = main.injectionOrder)) }
  listOf("summary", "authorsNote", "vectorsMemory", "vectorsDataBank", "smartContext")
    .forEach { k -> prompts.get(k)?.let { if (it.position != null) injectToMain(it, it.position) } }
  prompts.collection.filter { it.extension && it.position != null }.forEach { injectToMain(it, it.position) }

  // E. 工具数据预留（可砍）
  if (ToolManager.canPerformToolCalls(opt.type)) cc.reserveBudget(...)

  // F. continue 预填充：把"要续写的那条"抽出放最后
  if (opt.type == "continue" && continuePrefill && messages.isNotEmpty()) {
    val m = messages.removeFirst()
    val prefix = if (m.role == "assistant" && source == CLAUDE) substituteParams(assistantPrefill) else ""
    val content = listOf(prefix, m.content).filter { it.isNotEmpty() }.joinToString("\n\n")
    val c = Message.create(m.role, content, "continuePrefill"); control.add(c); cc.reserveBudget(c)
  }

  // G. in-chat 深度注入
  messages = populationInjectionPrompts(absolutePrompts, messages)      // 10.1

  // H. 示例与历史（pin_examples 决定先后）
  if (pinExamples) { populateDialogueExamples(...); populateChatHistory(...) }
  else             { populateChatHistory(...); populateDialogueExamples(...) }

  // I. 控制提示词最后加入
  cc.freeBudget(control); if (control.collection.isNotEmpty()) cc.add(control)
}
```

### 10.1 深度注入 `populationInjectionPrompts`（`810-875`）
```kotlin
var inserted = 0
for (i in 0..MAX_INJECTION_DEPTH) {                     // i=0 → 最新消息之前
  val roleMessages = mutableListOf<Message>()
  prompts.filter { it.injectionDepth == i && it.content.isNotEmpty() }
    .groupBy { it.injectionOrder }.toSortedMap(reverseOrder()).forEach { (order, list) ->
      for (role in listOf("system", "user", "assistant")) {
        var joint = list.filter { it.role == role }.joinToString("\n") { it.content }
        if (order == 100) { val ext = getExtensionPrompt(IN_CHAT, i, "\n", role, wrap = false)
                            joint = listOf(joint, ext).filter { it.isNotEmpty() }.joinToString("\n") { it.trim() } }
        if (joint.isNotEmpty()) roleMessages += Message(role, joint, injected = true)
      }
    }
  if (roleMessages.isNotEmpty()) { messages.addAll(i + inserted, roleMessages); inserted += roleMessages.size }
}
messages.reverse()                                       // 变回 旧→新
```

`getExtensionPrompt(position, depth, separator, role, wrap)`（`script.js:3301-3329`）：取 position 相符、depth 相符（undefined 通配）、role 相符（通配）且 `filter()` 为真的注入，各自 trim 后按 `\n` 连接，整体再 `substituteParams` 一次；`wrap=true` 首尾加分隔符。`getExtensionPromptMaxDepth` 见 `script.js:3281-3289`。

### 10.2 历史 `populateChatHistory`（`885-1092`）
```kotlin
if (!prompts.has("chatHistory")) return
cc.add(MessageCollection("chatHistory"), prompts.index("chatHistory"))       // 注意：先占槽位（0 条消息）

val newChat = Message.create("system", substituteParams(if (isGroup) newGroupChatPrompt else newChatPrompt), "newMainChat")
cc.reserveBudget(newChat)                                                    // 预留但不加入
var groupNudge: Message? = null
if (isGroup && prompts.has("groupNudge") && type != "impersonate") { groupNudge = ...; cc.reserveBudget(groupNudge) }

var continueColl: MessageCollection? = null
if (type == "continue" && cyclePrompt != null && !continuePrefill) {
  val idx = messages.indexOfLast { !it.injected }
  if (idx >= 0) { val removed = messages.removeAt(idx); continueColl = MessageCollection("continueNudge")
                  continueColl.add(Message.fromPrompt(prompts.prepare(removed))) }
  val nudge = Prompt("continueNudge", "system", substituteParams(continueNudgePrompt, mapOf("lastChatMessage" to cyclePrompt.trim())))
  continueColl.add(Message.fromPrompt(prompts.prepare(nudge))); cc.reserveBudget(continueColl)
}

if (messages.lastOrNull()?.role == "assistant" && sendIfEmpty.isNotEmpty() && cc.canAfford(emptyUser))
  cc.insert(emptyUser, "chatHistory")

for (m in messages.reversed()) {                     // 新→旧
  val prepared = prompts.prepare(Prompt(m).apply { identifier = "chatHistory-${messages.size - index}" })
  val msg = Message.fromPrompt(prepared)
  if (m.invocations != null && canUseTools) { /* tool_calls + tool 结果，塞不下则 break */ continue }
  if (cc.canAfford(msg)) cc.insertAtStart(msg, "chatHistory") else break     // 加不下就停，不跳过
}

cc.freeBudget(newChat); cc.insertAtStart(newChat, "chatHistory")
groupNudge?.let { cc.freeBudget(it); cc.insertAtEnd(it, "chatHistory") }
continueColl?.let { cc.freeBudget(it); cc.add(it, -1) }                       // 追加到最末
```

### 10.3 示例 `populateDialogueExamples`（`1101-1134`）
```kotlin
cc.add(MessageCollection("dialogueExamples"), prompts.index("dialogueExamples"))
val sep = Message.create("system", substituteParams(newExampleChatPrompt), "newChat")   // [Example Chat]
for ((di, dialogue) in messageExamples.withIndex()) {
  val msgs = dialogue.mapIndexed { pi, p -> Message.create("system", p.content, "dialogueExamples $di-$pi").setName(p.name) }
  if (!cc.canAffordAll(listOf(sep) + msgs)) break                       // 整块塞不下即停止
  cc.insert(sep, "dialogueExamples"); msgs.forEach { cc.insert(it, "dialogueExamples") }
}
```

示例来源（`openai.js:656-667`、`729-787`）：`mesExamplesArray` 每块把 `<START>` 换成 `{Example Dialogue:}`，按 `name1:`/`name2:` 前缀切成多条，**role 一律 `system`**，`name` 为 `example_user` / `example_assistant`，首行丢弃。

## 11. 步骤 8：token 预算与计数
```kotlin
ChatCompletion.setTokenBudget(context, response) { tokenBudget = context - response }   // openai.js:3982-3989
add(collection, position) → decreaseTokenBudgetBy(collection.tokens)
canAfford(msg) = tokenBudget - msg.tokens >= 0
reserveBudget(x) / freeBudget(x)                                                       // 4210-4220
```

* 消息 token 在 `Message.createAsync` 时算（`openai.js:3511+`），走 `tokenHandler.countAsync({role, content})`；`TokenHandler` 计数键：`start_chat/prompt/bias/nudge/jailbreak/impersonate/examples/conversation`（`3420-3479`）。
* 计数实现：后端 `tokenizers.js`，本地枚举 `tokenizers`（`tokenizers.js:16-38`：GPT2/OPENAI/LLAMA/LLAMA3/MISTRAL/CLAUDE/DEEPSEEK/…）；`getTokenizerModel()`（`569+`）按 source 选：OpenAI→模型名、DeepSeek→`deepseek`、OpenRouter→按 `architecture.tokenizer` 映射（Llama2→`llama`、Llama3→`llama3`、Mistral、Yi、Gemini→`gemma`、Qwen→`qwen2`、Cohere→`command-r` 等）。
* 计数接口 `countTokensOpenAIAsync(messages, full=false)`（`846+`），非 `full` 时每条消息 `-2` 作为格式开销近似。
* 上下文/回复/提示词预算：`getMaxContextTokens()`（`script.js:5929`，openai → `oai_settings.openai_max_context`）、`getMaxResponseTokens()`（`5966`，openai → `openai_max_tokens`）、`getMaxPromptTokens()`（`5981`，= 上下文 − 回复）。
* 裁剪 = 预算减法 + 加不下 `break`（`populateChatHistory`），**不重排、不摘要**；`squashSystemMessages`（`3922-3954`，仅 `squash_system_messages && !dryRun`）合并相邻无名 system（跳过 `newMainChat/newChat/groupNudge`），空 system 丢弃。
* `openai_messages_count` = 最终 chat 中 role ∈ {user,assistant,tool} 的条数（`openai.js:1621`）。
* `getChat()`（`4120-4141`）：过滤空 content 且无 tool_calls，输出 `{role, content, name, tool_calls, tool_call_id, signature, reasoning}`。

## 12. 步骤 9-11：请求、流式、停止串、reasoning

### 12.1 请求体 `createGenerationParameters()`（`openai.js:2691-3127`）
```js
{ type, messages, model, temperature, frequency_penalty, presence_penalty, top_p, top_k, min_p, top_a,
  repetition_penalty, max_tokens: openai_max_tokens,
  stream: stream_openai && type !== 'quiet' && !isO1 && !isWorkersAIJsonMode,
  logit_bias, stop: getCustomStoppingStrings(4), chat_completion_source, n,
  user_name, char_name, group_names, include_reasoning: show_thoughts, reasoning_effort,
  custom_prompt_post_processing, verbosity, json_schema? }
```

分支：Claude 用 `stop_sequences`、`n>1` 不合法；o1/gpt-5 用 `max_completion_tokens`、`role:system→user`、删采样参数；Gemini/MakerSuite 改 `candidateCount`；空 stop → `delete generate_data.stop`；`reverse_proxy` 仅白名单 source。

`POST /api/backends/chat-completions/generate`（`3138-3211`）：`headers: getRequestHeaders()`、`body: JSON.stringify(generate_data)`、`signal`；`!ok` 解析错误；stream 逐行 SSE，`data: '[DONE]'` 结束。**ST 前端不直连厂商**，由 Node 后端做协议适配；TeaTalk 可直连，但 messages 语义须一致。

### 12.2 流解析 `getStreamingReply()`（`3222-3306`）

| source | 文本 | reasoning |
|---|---|---|
| Claude | `delta.content` | `delta.thinking` |
| DeepSeek / xAI | `delta.content` | `delta.reasoning_content` |
| OpenRouter | `delta.content` | `delta.reasoning` 或 `reasoning_content` |
| Mistral | `delta.content[0].thinking[0].text` 等 | 同左 |
| MakerSuite | parts | `thought: true` 的 part |

多 swipe：`chunk.choices[0].index > 0`；tool_calls 按 index 累积分片。断流：`AbortController` 关流，已产出文本保留为 swipe（`script.js:5385-5451`）。

### 12.3 停止串

* Chat Completion 只传**用户自定义**停串：`getCustomStoppingStrings(count=4)`（`power-user.js:3078`，解析 `power_user.custom_stopping_strings` JSON 数组，空值过滤；`custom_stopping_strings_macro` 为真时逐条 `substituteParams`），超过 4 条截断。
* 不使用 instruct 的 `stop_sequence`（那只给 Text Completion，`instruct-mode.js:301-366`）。

### 12.4 reasoning 的提取、入提示词、展示（`reasoning.js`）
```kotlin
// 提取：见 12.2；来源标记 ReasoningType = model | parsed | manual | edited
// 入提示词（PromptReasoning，671-786）：
fun addToMessage(content: String, reasoning: String, isPrefix: Boolean, duration: Long?): String {
  if (!isPrefix && (!addToPrompts || counter >= maxAdditions)) return content
  if (reasoning.isEmpty() || reasoning == "\u200B") return content
  counter++
  val prefix = substituteParams(reasoningPrefix); val suffix = substituteParams(reasoningSuffix)
  val sep = substituteParams(reasoningSeparator)
  return "$prefix$reasoning$suffix$sep$content"
}
// 展示：power_user.reasoning.auto_parse 时用 prefix/suffix 正则从回复中剥离
//   regex = ^\s*?<prefix>(.*?)<suffix> /s   （parseReasoningFromString 1461-1490）
//   formatReasoning 反向拼回（1503-1520）；reasoning 存 message.extra.reasoning / reasoning_type / reasoning_duration / reasoning_signature
```

默认模板 `DEFAULT_REASONING_TEMPLATE = 'Think XML'`（`reasoning.js:34`）。`power_user.reasoning.max_additions` 限制入提示词条数。

## 13. 模板：context template 与 instruct template

**这两套只在 Text Completion 生效，Chat Completion 不用**；列出是为了理解"预设字段"与宏/占位符体系。

### 13.1 context template（story string，Handlebars 语法）

默认（`power-user.js:90`）：
```handlebars
{{#if system}}{{system}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}{{#if personality}}{{char}}'s personality: {{personality}}\n{{/if}}{{#if scenario}}Scenario: {{scenario}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}
```

渲染 `renderStoryString(params)`（`power-user.js:2234-2269`）：`Handlebars.compile(storyString, {noEscape:true})` → 渲染 → 再 `substituteParams(output)` → 去首部空行 → 非 IN_CHAT 时补末尾 `\n`。可用占位符：`system, description, personality, scenario, persona, char, user, wiBefore/wiAfter（旧 loreBefore/loreAfter）, anchorBefore/anchorAfter`。字段设置：`story_string_position`（IN_PROMPT/IN_CHAT/NONE）、`story_string_depth`、`story_string_role`（`power-user.js:249/356`、`script.js ~4690-4701`）。

### 13.2 instruct template（instruct mode 序列）

字段（`instruct-mode.js:25-45`）：`input_sequence`、`output_sequence`、`system_sequence`、`last_system_sequence`、`stop_sequence`、`first_input_sequence`、`last_input_sequence`、`first_output_sequence`、`last_output_sequence`、`wrap`、`macro`、`names_behavior`、`system_same_as_user`。
序列内 `{{name}}` 替换为 `name1/name2/'System'`（`330-336`）；`wrap` 用 `'\n'` 作为分隔符；`macro=true` 时序列再走 `substituteParams`。格式化函数：`formatInstructModeChat`(387)、`formatInstructModeSystemPrompt`(466)、`formatInstructModeStoryString`(478)、`formatInstructModeExamples`(511)、`formatInstructModePrompt`(593)。

## 14. Kotlin 实现映射
```kotlin
data class ChatMessage(val name: String, val mes: String, val isUser: Boolean,
  val isSystem: Boolean = false, val extra: MessageExtra? = null,
  val swipes: List<String> = emptyList(), val swipeId: Int = 0)

interface GenerationPipeline {
  suspend fun prepare(req: GenerationRequest): PreparedPrompt
  fun generate(p: PreparedPrompt, cfg: GenerationConfig): Flow<StreamEvent>
}

class PromptAssembler(
  val macros: MacroEngine,        // §7
  val worldInfo: WorldInfoScanner,// §6
  val promptManager: PromptProvider, // §8
  val tokenizer: TokenCounter,    // §11
)
```

* 三大块（coreChat 构建 / 世界书 / 消息组装）做成**纯函数**，用 ST 导出的 chat JSON 做回归测试。
* 槽位模型：`TreeMap<Int, MutableList<Message>>` 模拟稀疏 `collection`。
* 持久化：`chat_metadata.timedWorldInfo` 随聊天存（sticky/cooldown/delay），否则时间效果丢失。
* 事件：用 `SharedFlow<GenerationEvent>` 对应 ST 的 eventSource，便于插件化。

## 15. 必须精确复刻 vs 可以简化

| 模块 | 建议 | 简化后的用户可感知差异 |
|---|---|---|
| 默认提示词文本 + 默认 `prompt_order` | **精确** | 少一条或顺序错位 → OOC、设定被忽略 |
| 世界书主/次键、正则键、大小写、整词 | **精确** | 条目不触发/误触发，剧情串味 |
| 世界书预算 25%、order 排序、position 分类 | **精确** | 上下文撑爆或设定被砍 |
| 宏替换顺序 preEnv→env→postEnv | **精确** | `{{original}}`/`{{lastMessage}}` 错值 |
| 预算减法 + "加不下就 break" | **精确**（算法）| 长聊天截断点不同 |
| sticky/cooldown/delay 持久化 | 建议精确 | 条目该消失不消失 |
| 递归扫描 `world_info_recursive` | 可选 | 默认关，不影响默认体验 |
| 包含组/权重/组评分 | 可选 | 多条目互斥场景 |
| 向量记忆/ChromaDB/Smart Context | **可砍** | 少了"记忆回溯"（默认不开）|
| 工具调用 tool_calls | **可砍** | 无 function calling |
| 图片/音频/视频内联 | **可砍** | 多模态输入不可用 |
| reasoning 签名回传 | **可砍** | 多轮思维链略差 |
| 群聊 nudge / 多角色 | 二期 | 单角色 App 可先不做 |
| Text Completion（story string/instruct） | **不做** | 仅支持 chat-completion 后端 |
| 正则脚本 regex scripts | 二期建议做 | 预设里的输出清理失效 |
| `squash_system_messages` | 建议实现（默认 false）| 消息条数多，部分模型对连续 system 敏感 |

## 16. 未找到 / 存疑

1. `extension_prompt_types` / `extension_prompt_roles` 数值枚举在 `src/constants.js`（按符号使用，未逐行取证）。
2. `parseRegexFromString` 非法正则的**具体退化路径**只确认"解析失败→纯文本匹配"（`world-info.js:2888-2937`）。
3. `MAX_INJECTION_DEPTH` 常量值未确认（`script.js:3281-3289` 注释给出 1..MAX clamp）。
4. `parseExampleIntoIndividual` 首行处理按 1.19 代码"首行丢弃"（`openai.js:729-787`）。
5. 新版宏引擎块语法（`macros/engine/*`）未完整通读；`experimental_macro_engine` 默认 false，不影响默认行为。
6. `n`（多 swipe 条数）在 `default_settings` 无显式赋值，按 UI 推断为 1（存疑）。
7. `{{charIfNotGroup}}` 为 legacy 尖括号体系迁移来的宏，注册处未逐行确认（env 构造里存在）。

## 17. 附录 A：默认 `prompt_order`（可直接落库）
```json
[{"identifier":"main","enabled":true},{"identifier":"worldInfoBefore","enabled":true},
 {"identifier":"personaDescription","enabled":true},{"identifier":"charDescription","enabled":true},
 {"identifier":"charPersonality","enabled":true},{"identifier":"scenario","enabled":true},
 {"identifier":"enhanceDefinitions","enabled":false},{"identifier":"nsfw","enabled":true},
 {"identifier":"worldInfoAfter","enabled":true},{"identifier":"dialogueExamples","enabled":true},
 {"identifier":"chatHistory","enabled":true},{"identifier":"jailbreak","enabled":true}]
```

## 18. 附录 B：函数索引（按调用顺序）

| # | 函数 | 位置 |
|---|---|---|
| 1 | `Generate(type, opts, dryRun)` | `script.js:4290` |
| 2 | `getCharacterCardFields()` | `script.js:3476` |
| 3 | `parseMesExamples(mes, isInstruct)` | `script.js:3501` |
| 4 | `getWorldInfoPrompt(chat, maxContext, dryRun, globalScanData)` | `world-info.js:892` |
| 5 | `checkWorldInfo(...)` | `world-info.js:4709` |
| 6 | `getSortedEntries()` | `world-info.js:4590` |
| 7 | `WorldInfoBuffer.get / matchKeys / getScore` | `world-info.js:279 / 337 / 428` |
| 8 | `setFloatingPrompt()`（作者注） | `authors-note.js:324` |
| 9 | `addPersonaDescriptionExtensionPrompt()` | `script.js:3203` |
| 10 | `setOpenAIMessages(chat)` | `openai.js:570` |
| 11 | `setOpenAIMessageExamples(arr)` | `openai.js:656` |
| 12 | `prepareOpenAIMessages(content, dryRun)` | `openai.js:1542` |
| 13 | `preparePromptsForChatCompletion(...)` | `openai.js:1367` |
| 14 | `getPromptCollection(type)` | `PromptManager.js:1516` |
| 15 | `preparePrompt(prompt, original)` | `PromptManager.js:1277` |
| 16 | `populateChatCompletion(prompts, cc, opts)` | `openai.js:1185` |
| 17 | `populationInjectionPrompts(prompts, messages)` | `openai.js:810` |
| 18 | `populateChatHistory(...)` | `openai.js:885` |
| 19 | `populateDialogueExamples(...)` | `openai.js:1101` |
| 20 | `ChatCompletion.squashSystemMessages / getChat` | `openai.js:3922 / 4120` |
| 21 | `createGenerationParameters(...)` | `openai.js:2691` |
| 22 | `sendOpenAIRequest(type, messages, signal)` | `openai.js:3138` |
| 23 | `getStreamingReply(data, ...)` | `openai.js:3222` |
| 24 | `substituteParams(content, options)` | `script.js:2981` |
| 25 | `evaluateMacros(content, env, fn)` | `macros.js:610` |
| 26 | `messageFormatting(mes, ...)`（显示时宏） | `script.js:1800` |
| 27 | `renderStoryString(params)` | `power-user.js:2234` |
| 28 | `PromptReasoning.addToMessage` | `reasoning.js:744` |
