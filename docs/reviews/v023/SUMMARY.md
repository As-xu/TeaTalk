# 0.23.0 代码检视记录（多源降级）

检视对象：0.23.0 工作树（`0.22.0` 之后尚未提交的改动）。
检视方式：独立 reviewer 逐 hunk 读 `git diff` + 新增文件全文，不改文件、不构建；结论由 Lead 复核后才落地修复。

## 结论概览

- **P0：无**。降级循环的终止条件、`remaining` 越界、空 body 兜底、半截文本落库、`ChatEvent` 新增分支的 when 覆盖都确认干净。
- **P1：3 条**，全部用户可见，已修（见下表）。
- **P2：7 条**，修了 2 条（KDoc 与 BAD_RESPONSE 归类），其余记为已知限制。

## P0

无。

## P1（已修）

| 编号 | 问题 | 证据 | 修复 |
| --- | --- | --- | --- |
| P1-1 | 降级成功后气泡不显示来源标签，旧消息反而被标「与当前接口不同」 | `ChatScreen.kt` 在 `ChatEvent.Started` 里覆盖 `_activeProviderId`，判据变成「生成本条消息的接口」而非「配置里的主接口」 | `Started` 只更新本轮 `replyProvider`（落库用）；`_activeProviderId` 只在 init 与每次 `send` 前刷新为 `engine.activeProvider()?.id` |
| P1-2 | 备用列表允许勾选主接口 → 计数虚高、序号错位 | 弹层 `items(providers)` 未排除 `activeId`；`saveFallback` 原样落库 | 弹层过滤 `it.id != activeId`；`ProvidersViewModel.saveFallback` 再过滤一次主接口 |
| P1-3 | 空模型的候选被跳过，但「已切换到 X」指向它 | `ChatEngine` 循环内 `continue`，提示取 `candidates[index+1]` | 循环前剔除空模型候选（保留 `send.no_model` 日志），全部为空时直接 `NO_MODEL` |

## P2

| 编号 | 问题 | 处理 |
| --- | --- | --- |
| P2-1 | `AppPrefs.fallbackProviderIds` KDoc 引用不存在的 `[setFallbackProviderIds]`，且清理责任描述与实现不符 | 已修：改为「delete 主动清理 + resolveChain 跳过失效 id」 |
| P2-2 | 每次尝试都 `PromptDebugStore.publish`，降级后只剩最后一次快照 | 记为已知限制（快照只保留最后一次尝试，日志里保留了每次 attempt） |
| P2-3 | 链顺序 = provider 列表顺序（按 `lastUsedAt` 排序），`touch` 会改变下次弹层顺序 | 记为已知限制（当前按「列表顺序」语义，徽标可见） |
| P2-4 | 删除备用接口时静默改链（`notify = false`） | 保留：删除接口已有一条 Snackbar，再补一条会互相覆盖 |
| P2-5 | `BAD_RESPONSE`（200 但空 body / HTML 错误页 / 无终止事件）不降级 | 已修：纳入可重试集合，`FallbackPolicyTest` 同步断言 |
| P2-6 | 无主接口时，第一个备用槽的 `NoKey` 不计入 `primaryError`，报 `NO_PROVIDER` 而非 `NO_KEY` | 保留（极低频，且不改变可恢复性） |
| P2-7 | `FallbackPolicyTest` 只覆盖纯函数，降级循环没有引擎级用例 | 记为已知缺口（`ChatClient` 是 final class，做引擎级 fake 需要先抽接口，留到 0.23.x） |

## 复核与验证

- `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿（1m29s）。
- 真机复测（TAS-AN00）：主接口 `LocalStub`（端口未监听）→ 自动切 DeepSeek → 气泡出现「由 DeepSeek · deepseek-flash 生成，与当前接口不同」，Snackbar 正确显示两个接口名，见 `docs/screenshots/v023/08-source-label.png`。
- 已知限制与取舍同步写进 `docs/23-多源降级.md`。
