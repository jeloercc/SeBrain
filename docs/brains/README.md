# Brains plan: phase documents

**Status: proposed 2026-11-12. Nothing in this folder is built.** The research and the target
architecture are in [`../plan-brains.md`](../plan-brains.md). This folder turns that plan into
one document per phase, written so that any session can pick a phase up and finish it.

## Phases

| Phase | Document | Delivers | Size | Schema | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | [One memory](phase-1-one-memory.md) | Pinned notes replace `#memory`, "Suggest memories" and "remember" | S | none | **Built 2026-11-12**, on the Pixel; see its document for what is not verified |
| 2 | [Obsidian data model](phase-2-obsidian-data-model.md) | Properties, aliases, heading and block links, embeds, folders, unlinked mentions, vault import and export | L | v6 | Not started; schema to approve |
| 3 | [Brains](phase-3-brains.md) | Brains with scope, pins, instructions, model, own chats and graph | M | v6 | Not started |
| 4 | [Graph retrieval](phase-4-graph-retrieval.md) | Chunk headers, ranking over the link graph, index note per brain | M | none | Not started |
| 5 | [Wiki operations](phase-5-wiki-operations.md) | Ingest, lint, log, with approval of every change | L | v7 (small) | Not started |
| 6 | [Other models](phase-6-other-models.md) | Model on the user's machine over Tailscale; external API per brain | M | none | Not started; changes a hard rule |
| 7 | [Views](phase-7-views.md) | Local graph with depth and groups, Bases-like tables, Canvas | L | none | Not started |

Size: S about a day of work, M several days, L more than a week. These are estimates, not
measurements.

## Order and dependencies

```
1 ──► 2 ──► 3 ──► 4 ──► 5
            │           ▲
            └──► 6 ─────┘ (5 is much faster with a bigger model, but does not require it)
2 ──► 7
```

- Phase 1 stands alone and removes the confusion first.
- Phases 2 and 3 share one schema migration (v6), designed in the Phase 2 document. Phase 2
  ships first because brain scopes use folders and properties.
- Phase 4 needs brains (the scope narrows the search) and the Phase 2 fields (aliases and
  headings go into the chunk headers).
- Phase 6 can start any time after Phase 3. It is placed late because it changes a hard rule.
- Phase 7 only needs Phase 2.

## Decisions

The user answered "okay" to the plan on 2026-11-12 without choosing each option. The
recommended option is therefore **assumed** below, and each one is confirmed again at the
start of the phase that needs it. Decision 3 changes a hard rule of `CLAUDE.md` and is never
assumed: Phase 6 does not start without an explicit answer.

| # | Decision | Assumed | Confirm before |
| --- | --- | --- | --- |
| 1 | Pinned notes replace `#memory`, "Suggest memories" and "remember" | **Confirmed 2026-11-12** | Done |
| 2 | The assistant proposes edits to existing notes; the user approves each one | Propose only | Phase 5 |
| 3 | Where note content may be sent | **Not assumed** | Phase 6 |
| 4 | The encrypted vault stays the source of truth; Obsidian folders are imported and exported | Yes | Phase 2 |
| 5 | Schema v6 as written in the Phase 2 document | To approve | Phase 2 |
| 6 | This plan runs before the work-profile firewall | Yes | Now |

## Rules for every phase

These come from `CLAUDE.md` and from what the earlier phases taught.

- **Schema and key changes are proposed in the phase document and approved before code.**
- **One concept of memory.** No parallel memory stores, no special note kinds.
- **Verification on the Mac first:** `assembleDebug`, `testDebugUnitTest`, `lintDebug`,
  `scripts/check-release-privacy.sh`. Lint can take over ten minutes.
- **Phone tests use `am instrument`, never `connectedAndroidTest`**, which uninstalls the app
  and its data. The phone must be unlocked for vault tests, and SeBrain must not be opened
  while a model test runs: both would load the model in one process.
- **Vault tests run against a test vault** with its own directory and Keystore alias
  (`ConversationStoreTest` is the pattern). The real vault is only touched by an installed
  app whose migration already passed on a test vault.
- **After any prompt change, rerun the 20 conversations** (`AssistantEvalTest`, about 15
  minutes) and add cases for the new behaviour.
- **Limits of the phone model, measured:** 4096 tokens for input and reply together, about
  7.5 tokens/s written, about 300 tokens/s read. Any feature that adds a model call states
  its cost in seconds.
- **Every phase ends with:** the roadmap and this table updated, the results in
  `docs/benchmarks.md` if anything was measured, a local commit, and no push.

## Where things are today

| Concern | Code |
| --- | --- |
| Prompts, memory layers, citations, language | `app/.../chat/Assistant.kt` |
| Context from the vault (search, neighbours, `#memory`, past chats) | `app/.../chat/VaultContext.kt` |
| Note template and cleaning | `app/.../chat/NoteFormat.kt` |
| Screen state (972 lines; split in Phase 3) | `app/.../ui/AppState.kt` |
| Notes, tags, links, graph | `core/vault/.../NoteStore.kt` |
| Conversations, message search, summaries | `core/vault/.../ConversationStore.kt` |
| Schema (v5) and migrations | `core/vault/.../VaultSchema.kt` |
| Backup (format v2) | `core/vault/.../VaultBackupSnapshot.kt` |
| Parsing of `#tags` and `[[links]]` | `cognition/.../NoteMarkup.kt` |
| Chunking and indexing | `cognition/.../Chunker.kt`, `NoteIndexer.kt` |
| Hybrid search (FTS5 + vectors, RRF) | `core/vault/.../HybridSearch.kt` |
| Mini-brains (encrypted file, by source) | `ingest/.../BrainStore.kt` |
| The local model | `core/ml/.../ChatEngine.kt` |
| The only network exit | `core/net/.../TailnetGateway.kt` |
| One-file Markdown import and export | `cognition/.../MarkdownInterop.kt` |
