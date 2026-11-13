# Phase 3 — Brains

**Status: not started.** Size M. Schema v6, in the same migration as Phase 2.

## Goal

The user can keep several brains over one vault. A brain is a saved view with its own
behaviour: which notes it covers, what it always keeps in mind, how it should answer, which
model it uses. Notes are never copied; one note can be in several brains.

## What the user sees

- A **brain switcher** at the top of Notes, Graph and Ask. The current brain filters all
  three.
- **Everything**, a brain that covers the whole vault, exists from the start and cannot be
  deleted. It behaves as the app does today.
- **New brain:** a name, then a scope built from conditions joined by "and" / "or":
  - in folder `Redes` (and its subfolders);
  - has tag `#trabajo`;
  - property `status` is `active`;
  - captured from a source (share, import, a given chat);
  - linked from the note `Proyecto Atlas`, up to N steps.
- Per brain: **pinned notes**, **instructions** ("answer as a network engineer, briefly"),
  and a **model** (the local ones now; others in Phase 6).
- Conversations belong to the brain they were started in. The Chats panel lists the current
  brain's conversations.
- The existing mini-brains become brains with a source condition.

## Schema v6 (the brain part)

```sql
CREATE TABLE brain(
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
    scope TEXT NOT NULL,          -- JSON, see below; '{"all":true}' for Everything
    instructions TEXT NOT NULL DEFAULT '',
    model TEXT,                   -- model file name; NULL means the app default
    egress TEXT NOT NULL DEFAULT 'device',  -- device | tailnet | external (Phase 6)
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE brain_pin(
    brain_id INTEGER NOT NULL,
    capture_id INTEGER NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(brain_id, capture_id)
);
ALTER TABLE conversation ADD COLUMN brain_id INTEGER;   -- NULL means Everything
```

Scope JSON, a small tree:

```json
{"any": [
  {"all": [{"folder": "Redes"}, {"tag": "trabajo"}]},
  {"linkedFrom": {"title": "Proyecto Atlas", "depth": 2}},
  {"property": {"key": "status", "is": "active"}}
]}
```

Leaves: `folder`, `tag`, `property`, `source`, `linkedFrom`, `all: true`. Nodes: `all` and
`any` with a list. `egress` is stored now so Phase 6 adds no migration; in this phase it is
always `device`.

## Design

- **A scope compiles to one SQL condition** that returns capture ids. Every reader takes it:
  `NoteStore.recent`, `withTag`, `graph`, and `HybridSearch` (as `capture_id IN (...)` on the
  chunk join). `linkedFrom` is resolved first into an id set by a bounded breadth-first walk
  over `link` (depth at most 3), since SQL recursion over titles is slow and hard to bound.
- **`BrainScope` is pure Kotlin** in `:core:vault`: parse, validate, compile to SQL with
  bound arguments. No string from the user is ever concatenated into SQL.
- **Pins move to `brain_pin`.** The `pinned` tag of Phase 1 is read once: those notes are
  pinned in Everything, and the tag is removed.
- **Instructions are appended to the system prompt** under a fixed line, "Instructions for
  this brain:", capped at 600 characters. They cannot replace the base rules (cite, do not
  invent, language).
- **The scope is a filter, not a wall.** If the brain's notes do not answer, the assistant
  says so, as today; it does not silently search the rest of the vault.
- **`AppState` is split first.** It has 972 lines and four concerns. Before adding brain
  state: `ChatState` (model, conversation, generation), `NotesState` (list, detail, draft,
  graph), `PrivacyState`, with `AppState` holding the three and the current brain.
- **`BrainStore` (the encrypted file in `:ingest`) is retired:** its mini-brains are
  inserted as brains at the first start after the update, then the file and its Keystore key
  are deleted, as was done for the notification allowlist.

## Steps

1. Split `AppState` with no behaviour change; build and the 20 conversations pass.
2. Tables in `upgradeToV6`; `BrainStore` in `:core:vault` (create, rename, delete, pins).
3. `BrainScope`: parser, validator, SQL compiler, `linkedFrom` walk; unit tests.
4. Thread the scope through `NoteStore`, `HybridSearch`, `VaultContext`, `ConversationStore`.
5. Migrations in code: pinned tag → `brain_pin`; mini-brains → brains; conversations keep
   `brain_id` NULL.
6. UI: switcher, brain editor with the scope builder and a live count ("42 notes"), pins and
   instructions in the editor.
7. Backup format v3 carries brains and pins.
8. Evaluation cases: the same question in two brains with overlapping notes; a brain whose
   notes do not contain the answer; instructions that ask for a style.

## Tests

- Unit: every scope leaf and combination against an in-memory description; invalid JSON and
  unknown leaves are rejected; injection strings in names stay data.
- Phone, test vault: scope results for folder, tag, property, source and `linkedFrom` at
  depths 1 to 3; pins per brain; deleting a brain deletes its pins and keeps the notes.
- `AssistantEvalTest`: three new cases (above); rerun all.

## Acceptance

- [ ] Two brains over overlapping notes answer the same question from their own notes.
- [ ] A note pinned in one brain does not reach the prompt of another.
- [ ] Deleting a brain never deletes a note or a conversation's messages; its conversations
      move to Everything.
- [ ] Search inside a brain of 1,000 notes in a vault of 10,000 is not slower than a search
      of the whole vault (measured once, recorded in `benchmarks.md`).
- [ ] Mini-brains that existed appear as brains.

## Risks and limits

- A scope that matches nothing gives an empty brain; the editor shows the count before
  saving.
- `linkedFrom` with depth 3 on a hub note can cover most of the vault; the count shows it.
- Vector search with a scope filter: `sqlite-vec` filters after the nearest-neighbour step,
  so a narrow brain can get fewer than the wanted candidates. The search then asks for more
  candidates (up to a cap) before filtering. To verify against the `sqlite-vec` version in
  the build before coding.

## Out of scope

Models other than the local ones (Phase 6). Sharing a brain. Brains with their own key.
