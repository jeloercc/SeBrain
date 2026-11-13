# Phase 5 — Wiki operations

**Status: not started.** Size L. Schema v7 (one small table). Confirms decision 2.

## Goal

The assistant helps keep the vault as a connected body of knowledge, the way the LLM wiki
pattern does: it folds new sources into the notes, checks the vault's health, and keeps a
record of what it did. It never changes an existing note without the user's approval.

## The three operations

### Ingest

A source (a shared article, an imported file, a long capture) becomes knowledge:

1. A **summary note** of the source, in the note format, linked to the source.
2. A list of **proposed changes** to existing notes: "add this point to `TCP`", "link
   `QUIC` to `HTTP/3`", "new note `Head-of-line blocking`".
3. The user reviews the list. Each change shows the note before and after. Accept, edit or
   reject, one by one or all.

The source itself is never edited.

### Lint

A health report of a brain, in two parts.

Without the model, instant:

- orphan notes (no link in or out);
- links to notes that do not exist;
- two notes with the same title or alias;
- **concepts without a note:** a phrase that is the target of links, or is mentioned in three
  or more notes, and has no note of its own;
- unlinked mentions (from Phase 2), as a count per note.

With the model, on demand, for a chosen set of notes:

- **contradictions** between two notes about the same thing;
- **superseded claims:** an older note contradicted by a newer one. The older note is not
  rewritten; a line `> Superseded by [[Newer note]] (date)` is proposed.

Each finding has a proposed fix, approved like an ingest change.

### Log

One append-only record per brain of what the assistant did: `2026-11-20 ingest | Article
title | 1 note created, 3 changed`. Shown as a list in the brain's screen and included in the
context when the user asks "what did you change?".

## The cost problem, stated

On the phone model one note takes 15 to 30 seconds to write (measured). An ingest that
touches ten notes is about five minutes of generation and several prompts that each must fit
4096 tokens. So:

- **On the phone, ingest is deferred work.** It runs as a `WorkManager` job while charging
  (the constraint the project already chose for heavy work), one source at a time, and its
  proposals wait for review. It is never run while the user is chatting.
- **Interactive ingest needs a bigger model**, which is Phase 6. With the user's own machine
  the same steps run in seconds.
- **Each step is its own small prompt:** summarize the source; pick which existing notes it
  touches (from the brain index, Phase 4); for each one, propose the change. No step sends
  the whole vault.
- Vault closed while locked: the job can only run with the phone unlocked and charging,
  because cognition while locked is not implemented. This limits when it runs and is stated
  in the UI.

## Schema v7 (proposal)

```sql
-- A change the assistant proposes and the user has not decided yet.
CREATE TABLE proposal(
    id INTEGER PRIMARY KEY,
    brain_id INTEGER,
    kind TEXT NOT NULL,        -- create | append | link | supersede
    target_id INTEGER,         -- the note to change; NULL for create
    title TEXT NOT NULL,       -- the note's title (new or existing)
    body TEXT NOT NULL,        -- the text to add, or the whole new note
    reason TEXT NOT NULL,      -- one sentence, shown to the user
    origin TEXT NOT NULL,      -- 'ingest:<capture id>' or 'lint'
    created_at TEXT NOT NULL
);
CREATE TABLE brain_log(
    id INTEGER PRIMARY KEY,
    brain_id INTEGER,
    at TEXT NOT NULL,
    operation TEXT NOT NULL,   -- ingest | lint | file
    summary TEXT NOT NULL
);
```

Proposals are rows, not notes, so an unreviewed change is never searched or cited.

## Design

- **Changes are additive.** `append` adds a section to a note; `link` adds a link line;
  `supersede` adds a marked line. No kind rewrites or deletes the user's text. A change that
  cannot be expressed that way is proposed as a new note that links to the old one.
- **Applying a proposal** goes through the same path as editing a note (`NoteStore.updateText`
  + `NoteIndexer.index`) in one transaction, then the row is deleted and a log line written.
- **Provenance:** notes created by ingest carry `source: assistant` and
  `derived_from: [[Source title]]` in their front matter (Phase 2).
- **Sources and wiki notes are told apart by a property**, `kind: source`, set on capture.
  Sources are searched last and never changed.
- **"File an answer"** already exists as "Save as note". It gains the properties above and a
  log line.

## Steps

1. Lint without the model: queries in `NoteStore`, a report screen per brain, fixes that
   need no model (create the missing note as a stub, link a mention). This part is useful
   alone and ships first.
2. Schema v7; `ProposalStore`, `BrainLog` in `:core:vault`; test vault tests.
3. The review screen: list of proposals, before and after, accept / edit / reject.
4. Ingest prompts in a new `chat/Ingest.kt` (pure): summarize, choose notes, propose a
   change; parsing of each reply; unit tests with replies of the real model saved as fixtures.
5. `IngestWorker` (`WorkManager`, charging, unlocked): runs the steps, writes proposals.
6. Lint with the model: contradiction and supersede prompts over pairs of notes that share a
   title word or a link; proposals.
7. Evaluation: five ingest scripts with a known expected set of touched notes; two
   contradiction pairs.

## Tests

- Unit: every lint query on a small vault description; proposal application for each kind;
  ingest reply parsing, including malformed replies.
- Phone: `IngestWorker` on a test vault with one source; the proposal count and the log line.
- Evaluation with the real model: for each ingest script, which notes it proposed to touch
  against the expected set (recall and wrong proposals), recorded in `benchmarks.md`.

## Acceptance

- [ ] No existing note changes without an accepted proposal (tested by hashing every note
      before and after an ingest with all proposals rejected).
- [ ] Lint without the model reports the orphans, dead links, duplicates and concepts
      without a note of the fixture vault, and nothing else.
- [ ] An ingest of one article on the phone finishes while charging and leaves a summary
      note and its proposals.
- [ ] Rejecting every proposal leaves the vault as it was, plus the summary note if kept.
- [ ] The log lists every operation, and the assistant can answer "what did you change
      today?" from it.

## Risks and limits

- A small model proposes weak or wrong changes. The review step is the safeguard; the
  evaluation measures how often. If the wrong-proposal rate is high on the phone model,
  ingest proposals are limited to links and new notes, and `append` waits for Phase 6.
- Contradiction detection compares pairs; the number of pairs is capped per run and shown.
- A backlog of unreviewed proposals grows stale when notes change. A proposal whose target
  changed since it was made is marked and must be re-run.

## Out of scope

Automatic application of any change. Web search to fill gaps. Images in sources.
