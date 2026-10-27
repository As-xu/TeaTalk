UX Architect
## What I reviewed

- `TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt` (686 lines: ViewModel + UI)
- `TeaTalk/app/src/main/java/com/teatalk/xs/data/MessageVariants.kt`, `TeaTalk/app/src/main/java/com/teatalk/xs/data/db/Entities.kt:36-59`, `TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt:230-286`, `TeaTalk/app/src/main/java/com/teatalk/xs/data/ChatEngine.kt:57-96`
- `TeaTalk/docs/screenshots/m2/01-retry-dialog.png`, `02-variants-2of2.png`, `03-variant-1of2.png`, `04-delete-confirm.png`
- `TeaTalk/docs/09-M2-进度与验收.md`, `TeaTalk/docs/research/03-feature-inventory.md:459`

Baseline is solid: variants survive process death, arrows reflect bounds correctly (‹ greyed at 1/2, › greyed at 2/2), delete is confirmed, and the `content` mirror keeps prompt/list code untouched. Two things it does *not* do: tell you **what state a reply is in**, and let you **see the action surface without knowing a hidden gesture**. No files were modified.

---

## P0 — correctness / trust (fix before step 2)

**1. `status` is written to the DB and never rendered, and retry destroys it.**
`ChatScreen.kt:609-651` — `AssistantBubble` takes no `status`. `stopped`/`failed` replies look byte-identical to complete ones. Worse, `CharacterRepository.kt:237-251` stores status **per message**, and `:250` (`status = status`) overwrites it on every retry. So: stop generation → half reply marked `stopped` → retry → status becomes `complete` → switch back to `1/2`, the truncated reply now claims to be complete. `TeaTalk/docs/09-M2-进度与验收.md:56` already logs "UI 还没标" as the open item — I'd put it first, and the fix has to be per-variant, not per-message.
Fix: add `variantStatusesJson` (or `List<Pair<String,String>>`), render a small chip under the bubble (`已停止` / `发送失败 · 重试` / `已截断`), and let the chip carry the retry action for *that* message. Keep the banner (`ChatScreen.kt:376-378`) only for preflight/no-model errors — right now one failure produces two distant error surfaces (banner at the input, silent bubble in history).

**2. Retry is a silent no-op while streaming.**
`ChatScreen.kt:171` `if (job != null) return`. The dialog's Retry button (`:499-504`) is enabled whenever the message is the last one, closes the dialog (`:501`), then does nothing if a stream is running. Zero feedback, dialog gone. Either disable Retry with visible disabled state while `streaming` (and explain why), or make it queue/replace the in-flight generation. Same guard needed for Delete and variant arrows: `docs/research/03-feature-inventory.md:590` already flags "生成中禁用会改上下文的消息操作" as the state-machine risk.

**3. Delete silently destroys all N versions, with no undo.**
`ChatScreen.kt:514-529` + `CharacterRepository.kt:283-286`: `deleteMessage` drops the row and every variant in it. The confirm copy ("This cannot be undone. Delete it?") never mentions that 3 regenerations are going with it. For a roleplay app where a user retries 5×, that's the most expensive accidental tap in the app.
Fix (cheap): when `variants.size > 1` change the body to "将删除这条回复的 3 个版本"; add a Snackbar with `actionLabel = "撤销"` (persist the entity, re-insert with the same id/timestamp) — this satisfies `undo-support` and lets you drop the modal confirm for the single-version case, matching QQ/WeChat lightness.

**4. Mid-stream mutations can merge a reply into the wrong message.**
`CharacterRepository.kt:241-268` decides "append as new version" purely from `last.role == assistant`. Delete the in-flight turn's user message while generating → the reply lands as a *version of an older assistant reply*. Anchor generation to the user message id (`generationUserMessageId`) and have `appendAssistantReply` insert a new row when the tail no longer matches, or hard-disable delete/retry/variant-switch during generation.

---

## P1 — discoverability & gesture model

**5. The entire action surface is one invisible gesture.**
`ChatScreen.kt:626` and `:674` use `pointerInput { detectTapGestures(onLongPress = …) }`: no ripple, no press state, no haptic, no long-click label. Nothing on screen says a bubble is pressable, so Copy/Retry/Delete are undiscoverable — and this violates `gesture-alternative` (never gesture-only for critical actions) and `press-feedback`. The variant row is likewise invisible until you already used Retry.
Fix: `Modifier.combinedClickable(onClick = { /* expand */ }, onLongClick = …, onLongClickLabel = "消息操作")` (gives ripple + platform haptic), and add a visible path: the top-right `⋯` (`ChatScreen.kt:564-570`) is currently a **no-op `IconButton`** — wire it to a message-actions sheet for the last reply ("重新生成 / 复制 / 删除") and to per-chat settings later. That single change makes the whole feature reachable without the hidden gesture.

**6. Modal `AlertDialog` as an action menu ≠ QQ/WeChat.**
Screenshot `01-retry-dialog.png` shows a scrim'd modal with a flat 4-button strip: `Retry Delete Close Copy`. Problems:
- Four actions in one visual band with no hierarchy; the *primary* slot (`confirmButton`, `:490-496`) holds Copy, a secondary action, while `Delete` sits next to `Close` — violates `primary-action` and `destructive-emphasis` (destructive must be separated).
- `dismissButton = Row { … }` (`:497-511`) is an inner `Row` that **cannot wrap**; the M3 dialog only flow-wraps three children. Longer locales (de "Wiederholen/Löschen/Schließen/Kopieren") will squeeze or clip that Row.
- A modal for a per-message action costs a scrim + two taps for Copy, and breaks the reading context that long-press was meant to preserve.
Fix: replace with an anchored `Popup`/`DropdownMenu` positioned above-or-below the pressed bubble (standard `modal-motion`: animate from the trigger), rows = icon + label: `复制` / `重新生成` / `查看全文`(only when text is clipped) / divider / `删除`(error color). Keep the full-text modal *only* as an explicit "查看全文" with `SelectionContainer` inside — today the dialog body (`:486-489`) isn't selectable, so the only way to grab a paragraph is Copy-all.

**7. The version control is too small, unlabeled, and identity-free.**
`TeaTalk/app/.../ChatScreen.kt:654-665`: 26.dp box, 14.sp glyph `‹`/`›`, no `contentDescription`, disabled arrow only changes color; the counter (`:640-645`) is 11.sp `onSurfaceVariant` reading `2/2` — "2 of 2 what?". Fails 48dp touch target and a11y labeling; in a 14dp-spaced list, a scroll attempt landing on the row taps it.
Also: no per-variant identity (no time/model/index beyond a number), no way to delete just one version, and Retry is gated to the last message (`:499`) so older multi-version replies can't be extended.
Fix:
```kotlin
Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "版本 2/3" }) {
  IconButton(onClick = prev, enabled = selected > 0, modifier = Modifier.size(40.dp)) {
    Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.variant_prev))
  }
  Text(stringResource(R.string.chat_variant_pos, selected + 1, size), fontSize = 12.sp, …) // "版本 2/3"
  IconButton(onClick = next, enabled = …, modifier = Modifier.size(40.dp)) { … }
}
```
plus in the preview sheet: `v2 · 21:04 · <model>` per variant, and "仅删除此版本" once you accept per-variant rows. Do not add horizontal swipe-to-switch (`docs/research/03-feature-inventory.md:459`) until the pill is discoverable — swipe must skip the 20dp screen-edge back-gesture zone (`system-gestures`) and needs haptic per switch (`swipe-clarity`).

**8. Streaming tail stops being followed; anchor index is off by one.**
`ChatScreen.kt:312-317`: `count = messages.size + (if (streaming) 1 else 0)` then `listState.scrollToItem(count - 1)`, but index 0 is the "Today" header item (`:343-353`). So it anchors the **last persisted message** at the viewport top, not the streaming bubble. While idle the list clamps to the bottom so you don't notice; while a long reply streams in, its newest lines fall below the fold and never come back. It also fires per token (`streamText.length` key) with an instant jump.
Fix: keep the stick flag, then after each delta:
```kotlin
LaunchedEffect(streamText.length, streaming) {
  if (!stickToBottom) return@LaunchedEffect
  val info = listState.layoutInfo
  val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
  val overflow = last.offset + last.size - info.viewportEndOffset
  if (overflow > 0) listState.scrollBy(overflow.toFloat().coerceAtLeast(0f))
}
```
and correct the index to `listState.layoutInfo.totalItemsCount - 1` where you jump.

**9. Regeneration has no in-place state.**
Tapping Retry shows the *old* text still in place plus a brand-new "thinking" bubble at the bottom (`:369-373`), then the text swaps and `2/2` appears out of nowhere. ChatGPT/ST both regenerate in place. Minimum: pass `retrying = retryingMessageId == message.id` into `AssistantBubble` and render the thinking placeholder inside the existing bubble; on success, flash the new `n/m` pill once (150–250ms fade) so the change is legible (`state-transition`, `motion-meaning`). This needs `retryingMessageId: StateFlow<Long?>` in the VM — the six independent StateFlows here were already flagged in `docs/reviews/m1/00-专家审阅原文-2026-10-27.md:525`; per-message action state is the piece M2 actually needs.

---

## P2 — polish, a11y, i18n

| # | Finding | Fix |
|---|---|---|
| 10 | TalkBack cannot open the menu at all: `detectTapGestures` exposes no click/long-click semantics; arrows are non-merging unlabeled clickables | `combinedClickable(onLongClickLabel=…)` + `semantics { customActions = listOf(CustomAccessibilityAction("重试"){…}) }` on bubbles; `contentDescription` on arrows |
| 11 | Snackbar offset is a magic `88.dp` (`ChatScreen.kt:530-533`); the input grows to 4 lines (`:417`) and font scaling breaks the guess → toast overlaps the composer | Put the `SnackbarHost` in the same `Column` above the input block instead of absolutely positioning it |
| 12 | Dead affordances (`⋯` at `:564-570`, `+` at `:394-401`, emoji icon `:429-434` has no click) — same class of problem the M1 review rejected | Wire `⋯` to actions, hide or "敬请期待"-badge the rest |
| 13 | `preview`/`deleteTarget` are `remember` (`:294-295`) → dialogs vanish on rotation | `rememberSaveable` (store message id, re-resolve from list) |
| 14 | en `chat_preview_title` = "Message" (`TeaTalk/app/src/main/res/values-en/strings.xml:10`) vs zh "消息预览" (`TeaTalk/app/src/main/res/values/strings.xml:12`) — the en title describes nothing and doesn't hint at scroll | "Full message" / "消息全文"; and add a fade/scroll hint at the `heightIn(max = 420.dp)` boundary |
| 15 | Counter string has no noun: zh/en both `%1$d/%2$d` (`values/strings.xml:238`, `values-en/strings.xml:229`) | "版本 %1$d/%2$d" / "Version %1$d of %2$d" |
| 16 | No copy affordance for a *selection*; no "已复制到剪贴板" beyond a snackbar (fine), but no `SelectionContainer` | Add `SelectionContainer` in the full-text view |

---

## Suggested order (≈1.5–2 days, all small diffs)

1. Per-variant status + bubble status chip + status-aware retry (P0-1, ~4h) — closes the tracked gap in `docs/09`:56.
2. Streaming/action guards: disable Retry/Delete/arrows while generating, per-message `retryingMessageId` (P0-2, P0-4, P1-9, ~3h).
3. Replace the 4-button dialog with an anchored popup + wire `⋯` (P1-5, P1-6, P2-12, ~3h) — biggest discoverability win per line of code.
4. Version pill: 40dp `IconButton`s, "版本 n/m", per-variant meta in the sheet, delete-one-version (P1-7, ~3h).
5. Delete: version-count copy + Undo snackbar (P0-3, ~1.5h).
6. Scroll tail-follow fix + `rememberSaveable` dialogs + `SelectionContainer` (P1-8, P2-10/13, ~1.5h).

One caveat on scope: items 1 and 4 both want per-variant rows (`variantStatusesJson`, per-variant `createdAt`). If `conversations`/branching is queued for the next milestone anyway, do the schema change once now rather than adding a second JSON column later.

##
