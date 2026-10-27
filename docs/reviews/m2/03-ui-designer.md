UI Designer
## Method

All four M2 shots are 1080×2340 from AVD `teatalk34` (Pixel 5 shell, 440 dpi → **2.75 px/dp, 392.7 dp wide**). I measured rendered pixel geometry (dialog rects, text ink bands, glyph clusters, per-pixel colors) and cross-checked the implementation in [ChatScreen.kt](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt) and [Theme.kt](TeaTalk/app/src/main/java/com/teatalk/xs/ui/theme/Theme.kt). No files were modified (`git status` clean; temp measuring scripts removed).

**Before anything else: [tokens.md](TeaTalk/docs/design/tokens.md) is stale versus `Theme.kt`.** Measured pixels match the code, not the doc:

| Token | tokens.md | Theme.kt | Rendered (measured) |
|---|---|---|---|
| `surfaceContainerHigh` (AI bubble) | `#F5EEE3` | `#EDE4D6` | `#EDE4D6` ✓ |
| `onSurfaceVariant` | `#7B7167` | `#6E6255` | `#6E6255` ✓ |
| `outlineVariant` | `#F0E8DC` | `#DED2C2` | `#DED2C2` ✓ |
| `error` | `#B3261E` | `#BA1A1A` | `#BA1A1A` ✓ |
| `primary` | `#8C5A33` | `#8C5A33` | `#8C5A33` ✓ |

Anyone implementing from tokens.md today lands on **invisible dividers** (`#F0E8DC` on `#FBF7F0` ≈ 1.1:1) and the wrong red. tokens.md was last touched in `e0db913`; Theme.kt changed in `3578582` without a doc update. Fix this first — it is the handoff source of truth.

---

## What already works (keep it)

- **Text contrast is solid**: user bubble white-on-`#8C5A33` = **5.79:1**; AI bubble `#2B241D` on `#EDE4D6` = **12.1:1**; dialog body `#6E6255` on `#F3ECE4` = **4.70:1** (passes AA, but only just — don't lighten it).
- **Scrim** measures exactly 0.60 alpha black — inside the 40–60 % recommendation, isolates the modal properly.
- **Delete confirm wording** ("This cannot be undone. Delete it?") states the consequence and asks a question — textbook.
- **Variants row alignment is deliberate and correct**: chevron ink starts at **68.0 dp**, bubble text ink at **67.1 dp** (+1.1 dp) — the row hangs off the text column, not the avatar. Nice detail.
- `stopped` truncation *is* persisted (`Entities.kt:51-56`) and `chat_stopped` exists — the data foundation for D is already there.

---

## A. Variant arrow row `‹ n/m ›` — 4 P0/P1 issues

Screenshot `02-variants-2of2.png`, source `ChatScreen.kt:637-665`.

| # | Finding | Measured | Fix |
|---|---|---|---|
| **A1 P0** | **Disabled chevron is invisible.** `enabled = false` → `cs.outlineVariant` (`ChatScreen.kt:663`). | disabled `›` ink = `#DED2C2` = **1.39:1** on `#FBF7F0`. Dark theme computes to **1.85:1** (`#4A4038` on `#17130F`). | At 2/2 the row reads "‹ 2/2" with a phantom trailing gap — it looks broken, not "at the end". Use `onSurfaceVariant.copy(alpha = 0.6f)` (≈2.5:1) or a new `onSurfaceFaint` token: light `#A29586` (≈2.6:1), dark `#5F544A` (≈2.3:1). Keep ≥1.8× delta from enabled `#6E6255` (5.55:1). |
| **A2 P0** | **Touch target 26 dp** (`.size(26.dp)`, line 658) — 42 % of the 48 dp Android minimum / 59 % of 44 pt. Two arrows sit 26 dp apart. | 71 px hit circle on this device | Wrap with `Modifier.minimumInteractiveComponentSize()` (→48 dp hit, 26 dp visual) — costs no layout height. If you want visible affordance, `.size(40.dp)` + 18 dp glyph. |
| **A3 P1** | **Iconography is punctuation, not an icon.** `"\u2039"`/`"\u203a"` at 14 sp text glyph vs the screen's vector set (`Add` 22 dp, `SentimentSatisfied`/`Send`/`Stop` 20 dp, `ArrowBack` 24 dp). | chevron ink only **2.9–3.3 dp wide**, ~8.7 dp tall | `Icons.AutoMirrored.Filled.ChevronLeft/Right` at 18 dp (or `KeyboardArrowLeft/Right` 20 dp). One stroke language across the screen. |
| **A4 P1** | **Row is optically loose**: each glyph is centered in a 26 dp box ≈11.4 dp dead air per side, plus the counter's 2 dp padding. | `‹`→counter gap **14.2 dp**; counter→`›` **15.6 dp**; counter ink 16.7 dp wide | Target 6–8 dp optical gap: 32 dp boxes + `spacedBy(4.dp)` and drop the counter's 2 dp padding, or collapse to a single `Text("‹ 2/2 ›")` at 11.5 sp/14 W600 (cheapest, most consistent with the "label/chip" token). |
| **A5 P1** | **Counter is off-scale and non-tabular.** 11 sp is not in the type scale (nearest: 标签/chip 11.5 sp, 次要 12.5 sp), and tokens.md §2 mandates `FontFeatureSetting("tnum")` for counts — grep shows **zero `tnum` in the whole app**. | 11 sp | `11.5sp / 14 / W600` + `tnum`, or `12.5sp / 18 / W400` to match timestamps. Either way add tnum so `1/2 → 10/10` doesn't reflow. |
| **A6 P1** | **No semantics.** Bare glyph `Text` inside `clickable` — TalkBack announces "‹", no role, no state. | — | `clickable(onClickLabel = …)` + `contentDescription("Previous variant"/"Next variant")`; on the counter `semantics { contentDescription = "Reply 2 of 2" }` (and `liveRegion = Polite` so the switch is announced). |
| **A7 P2** | **No transition on switch** — the whole bubble snaps to the other variant; after a regeneration users can miss that it changed. | — | `AnimatedContent` / `Crossfade` 150–200 ms (micro-motion band; keep the 650 ms page transition for navigation only). |

---

## B. Long-press dialog (Retry / Delete / Close / Copy)

Screenshot `01-retry-dialog.png`, source `ChatScreen.kt:479-513`. Measured: dialog **297.1 × 189.1 dp**, side margins 47.6/48.0 dp, action label centers at **53.1 / 114.4 / 176.5 / 244.0 dp**, label gaps 26.2 / 25.5 / 34.5 dp.

| # | Finding | Fix |
|---|---|---|
| **B1 P0** | **Destructive action sandwiched.** Order is Retry → **Delete** → Close → Copy. A 1-tap slip where Retry/Delete are 61 dp apart destroys a message (a confirm follows, but you've still forced a recovery flow). | Destructive leftmost and spatially separated: `[Delete] … [Retry] [Copy] — [Close]`, or use M3 stacked actions (Delete alone in its own row). Distance target ≥ 72 dp from any non-destructive action. |
| **B2 P1** | **Four flat text actions, three of them `primary` (`#8C5A33`).** Retry, Close, Copy are visually identical in weight → no single primary action; the row reads as 4 peers spanning the full dialog width. | Copy = affirmative (`FilledTonalButton` or W700 text); Close = plain; Retry = plain; Delete = error. One primary per dialog. |
| **B3 P1** | **The action set shifts per message.** Retry renders only for `message.id == lastAssistantId` (`ChatScreen.kt:499`), so on older messages the row is `[Delete] [Close] [Copy]` — Delete and Copy move horizontally between messages. Muscle memory breaks, and the layout jumps between long-press targets. | Reserve the Retry slot (`Modifier.alpha(0f)`, non-clickable) or move Retry out of the dialog into the per-message inline status row (see D3). |
| **B4 P1** | **Hierarchy too flat in the dialog head.** Title `16.sp / W400` vs body `14.sp` — 2 sp and no weight change. Text also duplicates the payload ("Message" + the message itself). | Title `17sp / 24 / W700` (tokens 页面内标题), or drop the title for a meta line `Old Card · 17:07` at `12.5sp/18` `onSurfaceVariant`. |
| **B5 P1** | **Payload rendered as a caption.** The message content uses the default `onSurfaceVariant` gray — it's the primary content of the dialog, not helper text. | `onSurface` at `14.5sp / 22` (正文 token); keep gray for the meta line only. |
| **B6 P2** | **Dialog width is content-driven and changes size** between the two dialogs in one flow: **297.1 dp** (actions) vs **280.0 dp** (confirm) — a 17 dp shrink mid-flow. | One house composable (`TeaTalkDialog`) with `widthIn(min = 280.dp, max = 320.dp)`; AlertDialog has no entry in tokens.md at all. |
| **B7 P2** | Action band sits **45.5 dp** below the body ink (confirm: 48.0 dp) — over-detached; title→body is 28 dp. | If you build the house dialog, use 24 dp content gap + 48 dp action height (see B8). |
| **B8 P2** | Action touch height is M3's default **40 dp** < 48 dp minimum. | `heightIn(min = 48.dp)` on dialog action buttons. |
| **B9 P2** | Dialog never says *which* role/message you're acting on, and Retry only makes sense for assistant turns. | Add a role/meta chip, or include a 2-line excerpt (also fixes C4). |

---

## C. Delete confirmation

Screenshot `04-delete-confirm.png`, source `ChatScreen.kt:514-529`. Measured: dialog **280.0 × 188.0 dp**, «Close» center 153.3 dp, «Delete» center 223.8 dp, gap 33.8 dp, both at 37.5 dp from the dialog edges.

| # | Finding | Fix |
|---|---|---|
| **C1 P1** | **"Close" is the wrong dismiss label here.** Same word means "close the preview" in dialog 1 and "cancel the deletion" in dialog 2. | "Cancel" / "取消" for the destructive dismiss; keep "Close" for the preview. |
| **C2 P1** | **Delete is under-weighted for an irreversible action** — identical plain red text button, and it occupies the same rightmost slot that *Copy* (non-destructive) held one step earlier. | Filled error button (`containerColor = error`), or warning icon (`Icons.Outlined.WarningAmber`, 20 dp) + bold. Keep it in a stable slot across both dialogs (see B1). |
| **C3 P2** | **Context is lost.** Tapping Delete sets `preview = null` (`ChatScreen.kt:505-508`), so the confirm shows no excerpt of what is about to disappear. | Include `message.content`, `maxLines = 2`, ellipsis, `12.5sp/18`, `onSurfaceVariant`, 8 dp below the body. |
| **C4 P2** | Body text `13.5.sp` is off-scale (not 12.5, not 14.5). | `14.5sp / 22` (decision content) or `12.5sp / 18`. |
| **C5 P2** | No undo path, only a toast. | Snackbar action `UNDO` (5 s) is cheaper for users than the confirm step alone — you already show a "deleted" toast, so make it actionable. |

---

## D. stopped / failed status absence

Confirmed in `03-stopped.png`: the half-answer at "…began at the edge of a field where" looks **identical to a complete reply**. The only signal is a transient black snackbar that (a) vanishes in 3–5 s, (b) **overlaps and hides the very message it describes**, (c) is the only near-black surface in a cream theme. `status` is written (`ChatScreen.kt:215, 249`) and never read by any composable; `UiText.chatStopped()` is snackbar-only. [docs/09-M2-进度与验收.md](TeaTalk/docs/09-M2-进度与验收.md) admits this at line 56 but line 11 still marks 消息状态 as ✅ — the acceptance table should say "data only / UI pending".

That's a **content-integrity perception** problem: on re-entry, truncated or failed history is indistinguishable from finished history, and `chat_truncated` (context cut) has the same gap.

**Proposed spec** (fits existing tokens, no new colors):

```
after the affected bubble, 4 dp below, left-aligned to the text column (54 dp):
  stopped : 10 dp dot (outline)  +  "已停止" / "Stopped"      12.5sp/18  onSurfaceVariant
  failed  : 12 dp WarningAmber (error) + "失败" / "Failed"    12.5sp/18  error
            + inline TextButton "重试" / "Retry"             11.5sp/14 W600, error, h=28dp
              → pill background errorContainer 8% alpha, radius 8dp, padding 10×4dp
  both    : contentDescription carries the state for TalkBack; append "…" to truncated text
```

This also gives Retry a per-message home, which removes it from the 4-action dialog (B3) and fixes the "which message does Retry act on" ambiguity.

---

## E. Cross-cutting consistency

1. **Three different error/ended feedback styles**: pink inline banner (`errorContainer #F9DEDC`, radius 12 dp, 12.5 sp — `m1/08-chat-error.png`), near-black snackbar (`inverseSurface`, `m1-p1/03-stopped.png`), red dialog text. Pick two: persistent inline banner + error-tinted action; reserve the black snackbar for neutral confirmations ("Copied").
2. **Icon set**: text glyphs `‹ ›` are the only non-vector "icons" in the chat surface (A3).
3. **Radii/gutters drift from tokens**: bubble corner **16 dp** vs tokens 18 dp (`ChatScreen.kt:627, 675`); list gutter **14.2 dp measured** (`contentPadding` 14 dp, line 340) vs tokens 16 dp; avatar **32 dp / 10 dp** in list vs **36 dp / 12 dp** header — three avatar specs on one screen, and tokens.md only defines 列表头像 12 dp.
4. **Dark mode is unverified for all M2 elements** — the app defaults to dark (`57158f2`) but every M2 screenshot is light. Pre-computed: disabled chevron **1.85:1** in dark, and the 4-action row with `#FFB4AB` error text on `#2C251E` will read as a *brighter* destructive action than the rest — needs a shot before shipping.
5. **i18n**: `chat_preview_title` is "消息预览" (zh) but "Message" (en) — different meaning. English labels fit (135.9 dp of ink in 249.1 dp of content width, ~35 % slack), but the row is already at the 37.5 dp edge insets; any longer locale wraps. Keep `FlowRow` fallback, or go stacked if you add a 3rd language.

---

## Priority

**P0** — disabled chevron 1.39:1 (A1); 26 dp tap target (A2); destructive action sandwiched (B1).
**P1** — tokens.md ↔ Theme.kt drift (E-preamble); 4 equal-weight actions (B2); action set shifts per message (B3); dialog title/body flat hierarchy (B4); payload in caption gray (B5); text-glyph chevrons (A3); row spacing (A4); counter 11 sp + no tnum (A5); a11y semantics (A6); "Close" vs "Cancel" (C1); under-weighted Delete (C2); missing status UI (D).
**P2** — dialog width jump (B6), action height 40 dp (B8), excerpt in confirm (C3/C9), off-scale 13.5 sp (C4), undo (C5), switch transition (A7), bubble radius 16 vs 18 dp, 14 vs 16 dp gutter, avatar spec split, dark-mode shots, feedback-style consolidation (E1/E4).

**Not verified (out of reach in this session)**: dark-theme renders of all four elements (no device attached, no dark shots exist), and the zh-locale rendering of both dialogs.

##
