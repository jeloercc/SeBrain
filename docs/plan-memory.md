# Plan: finish the assistant's memory

**Status (2026-11-12):** steps 1 to 6 are done and on the Pixel. The evaluation ended at 20
of 20 conversations after two fixes found by it (see `docs/benchmarks.md`). What is left is
step 0, items 2 to 5, and the in-app checks of steps 2 to 5: they need the user's hands on
the app, because the tests drive the model, not the screens.

## Step 0 — Check what is already built (no code)

The last four commits were not exercised with the real model. Before building on them:

1. Kernel sequence: ask for the list, "haz la nota del primero", then "haz la nota del
   siguiente" twice. Expected: no repeated note, replies in Spanish.
2. Stop a reply halfway, then send another message.
3. Ask something long, switch apps, wait for "Your answer is ready".
4. Close the app, reopen, continue a chat from **Chats**.
5. "Recuerda que …", then check in a new chat that it is applied.

**Needs:** the user, about 10 minutes with the phone. **Exit:** a list of what failed, fixed first.

## Step 1 — Measure the model's limits (one short phone session)

Every later step spends context tokens, and the real budget is unknown: `maxNumTokens` is left
at the model default and was never measured.

- Log, per reply, `Conversation.getTokenCount()` and `getBenchmarkInfo()` (both exist in
  LiteRT-LM 0.18.1; checked in the jar): prompt tokens, tokens per second, time to first token.
- Find the context limit: send growing prompts until the engine rejects or truncates.
- Compare speed with the app on screen and behind `GenerationService`.

**Needs:** phone connected, about 15 minutes, one debug build. No schema change.
**Exit:** numbers in `docs/benchmarks.md` and the character budgets in `Assistant` set from
them instead of estimated.

## Step 2 — Search over past conversations

No model call, so it is the cheapest gain.

- Schema v5: `message_fts` (FTS5 over `message.text`, kept in step by the code that writes
  messages, like `chunk_fts`).
- `VaultContext` adds a short section "From earlier conversations" with the best 2 matches
  from other conversations (title, date, excerpt).
- A search field at the top of the **Chats** menu.

**Needs:** approval of schema v5. No new dependency. Unit tests on the Mac, one check on the
phone. **Open choice:** word search only (proposed), or also embeddings for each message
(better recall, more storage and indexing time).

## Step 3 — Summary of long conversations

Today older messages are reduced to one outline line each, which loses the content.

- When the outline passes about 12 lines, the model writes a summary of the older part
  (target 120 words) right after a reply ends, while `GenerationService` still runs.
- Schema v5 (same migration as step 2): `conversation.summary` and
  `conversation.summarized_until` (the last message id covered).
- The prompt then carries: summary + outline of what came after + recent messages.

**Needs:** the numbers from step 1 (the summary costs one extra generation; it must fit the
time and token budget). Schema v5 approval. **Risk:** a small model can write a wrong summary;
the original messages are always kept, and the summary can be regenerated.

## Step 4 — Facts about the user, with approval

- After a reply, a second short model call lists at most 3 candidate facts stated by the user
  (preferences, names, ongoing projects), as plain lines.
- They appear under the reply as "Remember this?" chips. Nothing is saved until the user taps
  one; an accepted fact becomes a `#memory` note, exactly like "recuerda que…".
- Rejected candidates are not shown again in that conversation.

**Needs:** step 1 numbers (it adds a generation per reply; it may run only every few messages
or only on demand). No schema change if candidates stay in memory. **Rule kept:** the
assistant never writes to standing memory on its own.

## Step 5 — Keep memory healthy

- A "Memory" filter in Notes that lists the `#memory` notes, with a count and a size warning
  when they pass the prompt budget (today: 8 notes, 300 characters each).
- Conversations in the encrypted backup: backup format version 2. **Needs approval** (it
  changes the backup format).

## Step 6 — Evaluate

- 20 scripted conversations (follow-ups, "the next one", language switches, recall of an old
  conversation, standing facts), each with a pass/fail line, in the style of
  `docs/retrieval-evaluation-template.csv`.
- Run once after step 4 and after any prompt change.

**Needs:** the user writes or approves the 20 scripts from real use; about 30 minutes on the phone.

## Order and decisions

Order: 0 → 1 → 2 → 3 → 4 → 5 → 6. Steps 2 and 3 share one schema migration.

Decisions the user must make before the step that needs them:

| Decision | Needed for |
| --- | --- |
| Schema v5: `message_fts`, `conversation.summary`, `conversation.summarized_until` | Steps 2 and 3 |
| Message search by words only, or also by meaning | Step 2 |
| Fact suggestions after every reply, every few messages, or only on demand | Step 4 |
| Backup format v2 with conversations | Step 5 |

Nothing here needs a new library, a new model or a network request.
