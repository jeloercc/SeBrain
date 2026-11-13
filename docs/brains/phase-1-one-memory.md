# Phase 1 — One memory

**Status: not started.** Size S. No schema change. Confirms decision 1.

## Goal

There is one kind of memory: the notes. A note can be **pinned**, which means the assistant
reads it in every prompt. Pinning replaces three things that confused the user: the `#memory`
tag with special behaviour, the "Suggest memories" button, and the "recuerda que…" command.

## What the user sees

- In a note's view: a **Pin** toggle, "Always in the assistant's context".
- In Notes: a **Pinned** filter chip, with the count and the budget warning that the
  `#memory` filter shows today.
- In Ask: "Suggest memories" is gone. "Save as note" stays.
- "Recuerda que prefiero respuestas cortas" is answered by the model like any message. To
  keep it, the user saves the answer or writes a note and pins it.
- Notes tagged `#memory` today become pinned notes and keep their tag as a normal tag.

## Design

- **Where the pin is stored:** a tag, `pinned`, reserved by the app and hidden from the tag
  chips. It needs no schema change, it survives backup and Markdown export, and Obsidian
  shows it as an ordinary tag. Phase 3 moves pins to a `brain_pin` table so a note can be
  pinned in one brain and not in another; the tag is then read once to migrate.
- **Budget:** pinned notes share one budget in the prompt, `PINNED_CHARS` (proposed 1200
  characters in total, about 350 tokens of the 4096). Notes are taken newest first; a note
  that does not fit whole is shortened; the rest are left out and the Pinned filter says so.
  Today's rule is 8 notes of 300 characters each, which can take 2400 characters.

## Steps

1. `VaultContext`: rename `MEMORY_TAG` to `PINNED_TAG = "pinned"`; read pinned notes by tag;
   apply the shared budget; keep excluding pinned notes from the search results so they are
   not shown twice.
2. One-time migration at vault open: every note with tag `memory` gets tag `pinned`. Done in
   code with `NoteStore`, inside a transaction, guarded by a flag in the app preferences so
   it runs once. No schema version change.
3. `Assistant`: rename the "What the user asked you to always remember" section to "Pinned
   notes"; remove `factToRemember`, `factsRequest`, `parseFacts`, `FACTS_*`.
4. `AppState`: remove `rememberFact`, `suggestMemories`, `acceptSuggestion`,
   `dismissSuggestion`, `memorySuggestions`, `memoryNotice`; add `togglePinned(noteId)`.
5. `ChatScreen`: remove the "Suggest memories" button and the suggestions card.
6. `NotesScreen`: Pin toggle in the note view; Pinned chip replacing the `#memory` special
   case; hide the `pinned` tag from the chips and from the note's tag line.
7. Tests and documents (below).

## Tests

- Unit (`AssistantTest`): pinned notes open the prompt; the shared budget shortens and drops
  notes in the stated order; a message starting with "recuerda que" produces a normal prompt.
  Remove the tests of the deleted functions.
- Phone, test vault: the migration turns `#memory` into `#pinned` once and is idempotent.
- `AssistantEvalTest`: cases 09 and 10 (standing instruction, standing fact) keep passing
  with the notes given as pinned. Rerun the 20.

## Acceptance

- [ ] No screen or prompt mentions "memory" as something different from a note.
- [ ] A pinned note changes the answer of a new chat; unpinning it stops that.
- [ ] Existing `#memory` notes are pinned after the update, with no user action.
- [ ] The prompt never exceeds `Assistant.INPUT_CHARS` with ten long pinned notes.
- [ ] The 20 conversations pass.

## Risks and limits

- A user who relied on "recuerda que…" loses a shortcut. The reply to such a message could
  offer "Save and pin"; left out of this phase to keep one way of doing it.
- The reserved tag is visible in an exported Markdown file as `#pinned`. Accepted.

## Out of scope

Pins per brain (Phase 3). Automatic extraction of facts: not planned at all.
