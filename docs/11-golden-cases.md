# Golden cases：用真实 SillyTavern 产出 prompt 作为对齐基准

## 为什么

M3 的 prompt 组装、世界书注入、行内字段顺序，之前只有「看起来合理」的实现和自测。
为了不靠猜，这里用真实 SillyTavern（1.19.0，本机 `SillyTavern/`）把同一张卡、同一条用户消息
发出的 **完整 chat-completion 请求体** 抓下来，作为后续逐条对齐的 ground truth。

## 组件

| 文件 | 作用 |
| --- | --- |
| `golden-card-aster.json` | 合成测试卡（V2 JSON）：每个字段带可检索标记 `DESC-1`/`PERS-2`/`SCEN-3`/`GREET-4`/`EXAMPLE-USER-5`/`EXAMPLE-CHAR-6`/`SYSPROMPT-7`/`PHI-8`/`ALT-9`，并内嵌 3 条世界书条目 |
| `mock-openai.mjs` | 本地 OpenAI 兼容端点（`GET /models`、`POST /chat/completions`），把请求体原样写入文件 |
| `golden.mjs` | Playwright 驱动 SillyTavern：导入卡 → 选中角色 → `/newchat` → 切 `main_api=openai / source=custom` → 发送一条消息 |
| `capture-aster.json` | 第一份 ground truth（Aster 卡 + `Tell me about the dragon.`） |

## 运行

前置：SillyTavern 已 `pnpm install --prod` 并用 `node server.js` 起在 `http://127.0.0.1:8000`；
`playwright-core` 装在任意目录，Chromium 用 `ms-playwright/chromium-1228`。

```bash
# 1) mock 端点
node tools/golden/mock-openai.mjs 9101 /tmp/capture-aster.json

# 2) 抓包（MOCK_URL 指向 mock）
MOCK_URL=http://127.0.0.1:9101/v1 node tools/golden/golden.mjs \
  "C:/Users/xusong/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" \
  tools/golden/golden-card-aster.json json /tmp/capture-aster.json \
  "Tell me about the dragon." Aster
```

无浏览器依赖的说明：SillyTavern 的 web UI 为纯前端，prompt 组装在浏览器里完成，
再 POST `/api/backends/chat-completions/generate` 由服务端转发；因此 mock 必须由 ST 服务端可达（本机即 127.0.0.1）。

## 第一份 capture 的结构（ST 真实行为）

`capture-aster.json` 共 11 条 message，逐条如下（`len` 为字符数）：

| # | role | 内容 | 来源 |
| --- | --- | --- | --- |
| 0 | system | `SYSPROMPT-7 …` | 卡 `system_prompt` |
| 1 | system | `DESC-1 …` | 卡 `description` |
| 2 | system | `PERS-2 …` | 卡 `personality` |
| 3 | system | `SCEN-3 …` | 卡 `scenario` |
| 4 | system | `[Example Chat]` | `mes_example` 起始标记 |
| 5 | system | `EXAMPLE-USER-5 …` | mes_example 第一轮 user 行 |
| 6 | system | `EXAMPLE-CHAR-6 …` | mes_example 第一轮 char 行 |
| 7 | system | `[Start a new Chat]` | 对话起点标记 |
| 8 | assistant | `GREET-4 …` | `first_mes` |
| 9 | user | `Tell me about the dragon.` | 本次输入 |
| 10 | system | `PHI-8 …` | `post_history_instructions`（最后一条） |

采样参数：`temperature=1, top_p=1, max_tokens=300, frequency_penalty=0, presence_penalty=0, stream=false`
（来自 ST 默认预设，用于和我们的 preset 映射对照。）

同时确认：**卡内 `character_book` 的三条（含 `position=before_char`、`after_char`、`position=4/depth=2`）本次未出现在
请求里**——ST 的嵌入式 lorebook 需要额外「导入为世界书并绑定」才生效。这本身也是一条待确认行为，
下一步用 UI/API 显式绑定后重新抓一次（见 docs/11 §下一步）。

## 对齐进展（2026-10-30）

- 新增 `app/src/main/java/com/teatalk/xs/data/PromptAssembler.kt`：按 ST 结构组装（system_prompt 或默认句 / 世界书 before / description / personality / scenario / `[Example Chat]` + 逐行示例 / `[Start a new Chat]` / 历史 / PHI / 世界书 after）。
- 新增 `app/src/test/java/com/teatalk/xs/data/GoldenPromptTest.kt`：以 `app/src/test/resources/golden/capture-aster.json` 为输入做**逐条等值断言**，已通过。
- `ChatEngine` 改为调用 `PromptAssembler`（原 `systemPrompt()` 拼接与 `PromptOrder.assemble()` 路径移除）。
- AVD 复查「查看本次请求」面板：结构与 ST 一致（System prompt 76 + 世界书 3165 + 描述 2845 + …）；截图 `docs/screenshots/m3-golden/01-prompt-panel-structure.png`。
- 剩余差异：ST 世界书块的 3 条顺序与激活集合由 ST 扫描器裁决，本机 capture 的 block 长度已一致（3165 vs 3165），顺序需在 case B 里逐条断言；`{{user}}` 名称目前固定为 `User`（待接入个人资料名）。

## 与 TeaTalk 当前实现的差异（第一轮，历史记录）

| 维度 | ST（ground truth） | TeaTalk 当前 |
| --- | --- | --- |
| 字段承载 | 每个字段各成一条 `system` message（system_prompt / description / personality / scenario / 示例各条） | 合并进一条 system prompt 字符串 |
| mes_example | 解析成 `[Example Chat]` + 逐轮 system 消息 | 未解析（`mes_example` 丢弃） |
| 对话起点 | `[Start a new Chat]` system 标记 | 无 |
| first_mes | assistant 消息在历史里 | 相同（作为开场白入会话） |
| 世界书 | 取决于世界书绑定；本次未注入 | 由 `WorldBookMerger` + `WorldInfoInjector` 注入到 system/at-depth 等位置 |
| PHI | 最后一条 system | `PromptOrder` 已支持「历史之后 system」，需核对是否与 ST 同序 |
| 采样参数 | 请求体顶层 `temperature/top_p/max_tokens/frequency_penalty/presence_penalty` | `SamplingPreset` + Provider 字段，需核对默认值映射 |

## 下一步（对齐顺序）

1. 在 ST 里把 Aster 的嵌入式 lorebook 导入并绑定（World Info 面板或 API），重抓第二份 capture，确认 `before_char/after_char/atDepth` 的插入位置与顺序。
2. 把 `ChatEngine` 的 prompt 组装抽成纯函数（`PromptAssembler`），以 `capture-aster.json` 作为单元测试输入：
   `assertThat(assemble(...)).isEqualTo(stMessages)`，先允许白名单差异（宏替换、名字替换）。
3. 先对齐「字段分条 + 示例解析 + 起点标记 + PHI 位置」，再对齐世界书位置与采样参数。
4. 对齐后用同一套 harness 扩到 3–5 张卡（含 CHARX、V1、超大世界书），作为 M3 的验收底座。

## 已知坑

- 首次 `require` 时 ST 会自动生成 `config.yaml` 与默认内容；`skipContentCheck` 可跳过内容校验网络请求。
- `sendGenerationRequest` 是底层函数，不是「发一条消息」的入口；驱动 UI 点 `#send_but` 或走 `/ask` 才会走完整组装。
- `main_api` 必须切到 `openai`（默认是 `textgenerationwebui`），否则请求不会发往 `/api/backends/chat-completions/generate`。
- mock 的 capture 文件是启动参数固定的：换 case 要换文件或换进程。
