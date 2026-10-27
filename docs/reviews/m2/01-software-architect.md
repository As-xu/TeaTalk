Software Architect
## Review: commit `a65f2fe` (M2 step 1 — variants / retry / delete / status, DB v4)

I read the full diff plus the current state of all eight files (and the M2 acceptance doc, `<TeaTalk>` build script, test tree). Nothing is edited. Overall shape is sound — engine no longer persists, VM decides, `content` stays the mirror — but the "append vs insert" decision is inferred from a global heuristic (the last row's role) instead of turn identity, and that is where the real defects cluster.

---

### 1. High — `retry()` has no target and no preflight: a blocked first send turns the model's reply into variant 2/2 of the *greeting*
`[ChatScreen.kt:170-184](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L170-L184)`, `[ChatEngine.kt:89-91](TeaTalk/app/src/main/java/com/teatalk/xs/data/ChatEngine.kt#L89-L91)`, `[CharacterRepository.kt:241-253](TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt#L241-L253)`

- `send()` correctly runs `preflightError()` and writes **nothing** when no provider/model is configured (`[ChatScreen.kt:157-163](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L157-L163)`); the error banner then offers `onRetry = viewModel::retry`.
- `retry()` (line 170) does **not** call `preflightError()` and does **not** check that an assistant turn exists to regenerate. It goes straight to `streamReply(excludeLastAssistant = true)`.
- With only the greeting in `messages`, line 90's `list.dropLast(1)` drops the greeting → the prompt sent to the API is **system prompt only, zero user turns**.
- The answer then hits line 242 `last.role == ROLE_ASSISTANT` (the greeting) → `dao.updateVariants(...)` → the greeting bubble now renders `1/2 · 2/2` where variant 2 is a reply to nothing. The user's own message is never persisted.
- Reachable: fresh chat → type "hi" → send (no key) → go configure the provider → back (NavBackStackEntry keeps the VM, `_error` is still set) → tap 重试.

**Fix:** `retry(targetMessageId: Long)`, require `dao.lastMessage(cid)?.id == targetMessageId` before appending as a variant, run `preflightError()` in `retry()`, and when there is no assistant message fall back to re-sending `_restoreText` through `send()`.

### 2. High — the variant/insert decision is a role-based guess with no turn identity
`[CharacterDao.kt:95-96](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/CharacterDao.kt#L95-L96)` + `[CharacterRepository.kt:241-253](TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt#L241-L253)`

`appendAssistantReply` asks "is the newest row an assistant?" — a global predicate read *outside* any transaction, so correctness depends on every other writer's timing. Finding 1 is one instance; the failure mode is generic: any reply that lands while the newest row happens to be an assistant is silently reparented (merged into another message's variant list), and the transcript loses a turn boundary with no error anywhere. Two options:

- **A (cheap, reversible):** thread an explicit `replyToMessageId`/`turnId` from the VM (which already knows what it is regenerating) into `appendAssistantReply`, and make the DAO reject the append when the target no longer matches. ~30 lines, no migration.
- **B (correct, costly):** `messages.turnId` + `variants` as a child table with a real FK. Fixes findings 2, 4 and 12 at once, but needs a v5 migration and rewrites `observeMessages`.

Given M2 scope, A.

### 3. Medium-High — `appendAssistantReply` is a non-atomic RMW with no dedup and no `@Transaction`
`[CharacterRepository.kt:241-251](TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt#L241-L251)`, `[CharacterDao.kt:98-99](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/CharacterDao.kt#L98-L99)`, race partners `[ChatScreen.kt:213-216](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L213-L216)` and `[ChatScreen.kt:236-241](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L236-L241)`

`SELECT lastMessage` → build list → `UPDATE` are three separate transactions. The `Completed` append and `stop()`'s post-`join()` append are on different coroutines (both `Dispatchers.IO`), so a slow-then-cancelled `Completed` write plus `stop()` yields either a **duplicate identical variant** (`1/3 · 2/3 · 3/3` with two identical texts, status flipped to `stopped`, snackbar "已停止生成" shown although the reply finished) or, on interleaved reads, a lost variant. There is also no "same text as the selected variant → no-op" guard, which would make the whole class cheap to kill.

**Fix:** one `@Transaction suspend fun appendVariantIfAbsent(id, body, status)` in the DAO doing a single conditional `UPDATE ... WHERE id = :id AND variantsJson = :expected`; and in `stop()`, re-read the row and skip when `content == partial`.

### 4. Medium — `status` is per-message but a message hosts N variants
`[Entities.kt:44-58](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/Entities.kt#L44-L58)`, writers `[CharacterDao.kt:98-99](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/CharacterDao.kt#L98-L99)` / `[CharacterDao.kt:101-102](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/CharacterDao.kt#L101-L102)`

Every append overwrites the single `status`, and `selectVariant` never touches it. So after one failed retry the whole message is `failed` even though variants 0..n-1 are `complete`; switching back to a good variant leaves `status='failed'`. The doc's "complete/stopped/failed" contract is unanswerable per variant, which is exactly what a "retry the failed message" feature will need. Per-variant status (`statusesJson` parallel array) or the child table from 2-B.

### 5. Medium — stopped/failed/truncated fragments are fed back into the prompt forever
`[ChatEngine.kt:92-98](TeaTalk/app/src/main/java/com/teatalk/xs/data/ChatEngine.kt#L92-L98)`

History includes every row regardless of `status`; only the single newest assistant can be excluded (line 90). A `stopped` "半截" or `failed` fragment becomes permanent conversation context. Worse, `truncated` replies are saved as `STATUS_COMPLETE` (`[ChatScreen.kt:242](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L242)`), so a mid-word cut is indistinguishable from a finished turn and the model continues from it. Filter `status != complete` when building history and add `STATUS_TRUNCATED`.

### 6. Medium — `status` is write-only: nothing reads it
Grep across `app/src/main`: the only consumers are the constants and `MessageVariantsTest`. No badge, no filter, no prompt effect (see 5). Combined with 4 and 5 the new column currently earns nothing, while the doc (§5) lists "UI 还没标" as a known limitation — meaning the M2 acceptance table's "消息状态 ✅ 本轮" overstates what shipped.

### 7. Medium (test gap) — `MIGRATION_3_4` has zero automated coverage, and cannot get any as configured
`[TeaTalkDatabase.kt:90-102](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt#L90-L102)`

- `exportSchema = false` (`[TeaTalkDatabase.kt:17](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt#L17)`) means no schema JSON exists → `MigrationTestHelper` cannot be used.
- There is no `app/src/androidTest` source set at all (`find` returns only `main` and `test`), and no `room-testing` dependency in `[build.gradle.kts](TeaTalk/app/build.gradle.kts)`.
- So the only verification of a DB version bump is one manual AVD run (docs §4 row 1). This is the one class of defect that bricks upgrades for existing users.
**Fix:** `exportSchema = true` + `room.schemaLocation` + a `MigrationTestHelper` test asserting v3→v4 keeps 12 rows and yields `variantsJson='[]'`, `selectedVariant=0`, `status='complete'`.

### 8. Low-Medium (migration safety) — fresh installs and upgraded installs end up with physically different `messages` schemas
`[TeaTalkDatabase.kt:97-99](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/TeaTalkDatabase.kt#L97-L99)` vs `[Entities.kt:48-52](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/Entities.kt#L48-L52)`

The migration adds `DEFAULT '[]' / 0 / 'complete'`, but the entity declares no `@ColumnInfo(defaultValue = …)`, so a fresh install's `CREATE TABLE` has **no** defaults. Room's validation tolerates this (entity default is null ⇒ DB default is ignored), so nothing breaks today and `@Insert` always lists all columns. But the next migration that rebuilds `messages` (`CREATE … ; INSERT … SELECT …`) must remember all three columns, and any raw `INSERT` omitting them works on upgraded devices and fails on fresh ones. Declaring `@ColumnInfo(defaultValue = "\"[]\"")` etc. makes both paths identical for free.

Minor on the same file: `version = 4` (line 16) and `const val VERSION = 4` (line 25) are two independent sources of truth, and `[AppContainer.kt:46](TeaTalk/app/src/main/java/com/teatalk/xs/data/AppContainer.kt#L46)` logs only the const — drift would be invisible in logs while being real.

### 9. Low-Medium — `parse` filters empties but `selectedVariant` is not remapped; the mirror invariant is unenforced
`[MessageVariants.kt:23-28](TeaTalk/app/src/main/java/com/teatalk/xs/data/MessageVariants.kt#L23-L28)`

`selectedVariant` indexes the raw JSON array, while `of()` returns a filtered list. `encode` also filters, so the app's own writes can't desync today — but any external writer (future import, hand-edited DB, a future `statusesJson` migration) that stores `["a","","b"]` silently shifts every index below it, and `content` (the mirror everything reads) then disagrees with `variants[selectedVariant]` shown in the bubble. Also: `parse` accepts whitespace-only variants while `encode` keeps them (`isNotEmpty` not `isNotBlank`), and `Json { isLenient = true }` means malformed arrays can be *reinterpreted* rather than fall back — the fallback test passes but strict is the safer default for stored data.

### 10. Low — the `content` mirror has no enforcement, only convention
`[Entities.kt:41](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/Entities.kt#L41)`, `[MessageVariants.kt:11-12](TeaTalk/app/src/main/java/com/teatalk/xs/data/MessageVariants.kt#L11-L12)`

Two writers keep it in sync, nothing validates it, and two independent readers depend on it with *different* sources: chat-list preview (`[CharacterDao.kt:115](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/CharacterDao.kt#L115)`) and prompt history read `content`; the bubble reads `content` **and** `variantsJson`. One half-applied write (finding 3) gives a bubble whose text ≠ the chat-list line ≠ prompt history. Cheapest fix: derive it in the DAO query instead of storing it.

### 11. Low — preview/delete dialogs hold a stale snapshot
`[ChatScreen.kt:294-295](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L294-L295)`, `[ChatScreen.kt:479-529](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L479-L529)`

`preview` / `deleteTarget` capture the `MessageEntity` instance. If that row changes while the dialog is up (a `stopped`/`failed` append from the still-running stream, a variant switch), "复制" copies the old text and the dialog body shows the old text. Hold `messageId` and derive the row from `messages`, or re-lookup on open. Related nit on the same lines: the `dismissButton` `Row` stacks 重试 / 删除 / 关闭 inside M3's already-narrow action area — three TextButtons will squeeze on 360dp.

### 12. Low — delete destroys every variant silently, and there is no per-variant delete
`[ChatScreen.kt:518](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L518)` says only "删除后不可恢复，确定删除吗？"; `[CharacterRepository.kt:283-286](TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt#L283-L286)` deletes the row, i.e. 4 alternative texts. Given finding 4/5 (a failed retry inserts a junk variant and there is no way to drop just it), the only remedy for one bad variant is deleting the whole message *including the good ones*. At minimum the confirm text should name the count.

### 13. Low — 26dp variant arrows, no accessibility label
`[ChatScreen.kt:653-665](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L653-L665)`

`Modifier.size(26.dp)` is well below the 48dp minimum touch target; the glyphs `‹ ›` are raw text with no `contentDescription` and the `1/2` counter has no semantic label ("版本 2/3"). Disabled state (`cs.outlineVariant` on `surfaceContainerHigh`) is very low contrast.

### 14. Low — callers ignore `appendAssistantReply`'s return value, so a lost reply is invisible
`[CharacterRepository.kt:237-239](TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt#L237-L239)` returns `-1` on empty text / missing character; `[ChatScreen.kt:215](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L215)`, `[ChatScreen.kt:240](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L240)`, `[ChatScreen.kt:249](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L249)` all discard it. `stop()` still reports "已停止生成", `Completed` still clears `_streamText` — the text is simply gone with no error path.

### 15. Low — logs and KDoc still describe M1's engine contract
`[ChatEngine.kt:61](TeaTalk/app/src/main/java/com/teatalk/xs/data/ChatEngine.kt#L61)` logs `"send.retry"` for **every** generation, including first sends, so that event no longer counts retries (the M1 log-based analyses used it). `[ChatEngine.kt:14-22](TeaTalk/app/src/main/java/com/teatalk/xs/data/ChatEngine.kt#L14-L22)` still documents `[reply]` as "保留给只关心一次完整往返的调用方" — that function was deleted in this commit.

### 16. Low — deleting every message resurrects the greeting
`[ChatScreen.kt:145](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L145)` → `[CharacterRepository.kt:198-206](TeaTalk/app/src/main/java/com/teatalk/xs/data/CharacterRepository.kt#L198-L206)` → `[CharacterDao.kt:78-84](TeaTalk/app/src/main/java/com/teatalk/xs/data/db/CharacterDao.kt#L78-L84)`: with `countMessages == 0` the next `ChatViewModel` init re-inserts `firstMes`, so "delete all" is not idempotent (arguably desirable, but it should be a decision, not a side effect).

Also minor: `MessageVariants.of()` runs a JSON decode inside composition at `[ChatScreen.kt:358](TeaTalk/app/src/main/java/com/teatalk/xs/ui/chat/ChatScreen.kt#L358)` — main-thread work per visible assistant row per list emission; fine at current sizes, worth a `Dispatchers.Default` map if variant counts grow.

---

### Test gaps (concrete)
- `[MessageVariantsTest.kt:28-31](TeaTalk/app/src/test/java/com/teatalk/xs/data/MessageVariantsTest.kt#L28-L31)` (`blankContentStillProducesSingleVariant`) asserts `listOf("")` — it codifies an empty variant that `encode`/`parse` deliberately filter out, i.e. it pins an impossible state instead of the documented one.
- `[MessageVariantsTest.kt:47-56](TeaTalk/app/src/test/java/com/teatalk/xs/data/MessageVariantsTest.kt#L47-L56)` (`appendKeepsOrderAndSelectsLast`) re-implements the append inside the test rather than calling `appendAssistantReply`; nothing covers the new-vs-append branch, the greeting case (finding 1), `selectVariant` bounds, `deleteMessage`, or per-variant status.
- Zero `ChatViewModel` tests, although the gen-counter + `stop()`/`Completed` interleaving (finding 3) is the riskiest state machine in the app — a fake repository and `runTest` would cover both `stop()`→duplicate and finding 1 cheaply.
- No DAO/Room coverage at all (no Robolectric, no in-memory DB), so `lastMessage`'s `ORDER BY createdAt DESC, id DESC LIMIT 1` — load-bearing for findings 1–3 — is only exercised by hand.
- No migration test (finding 7).

**Suggested order of work:** 1 → 3 → 5 → 4/12 (same root: per-variant identity) → 7 → 8 → the rest. Findings 1, 3, 5 are the ones a user can hit within minutes of normal use.

##
