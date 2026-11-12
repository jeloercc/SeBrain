# SeBrain roadmap

**Status: current plan, set by the user on 2026-11-10.** It replaces the phase order of the original research document where they differ.

## Product

SeBrain is a unifying personal app on the Pixel 10: an Obsidian-style second brain (notes, tags, links between notes) stored encrypted on the device, connected to the user's other apps, with a local uncensored AI that answers concrete questions and acts on the notes. Nothing goes to the cloud. Security comes first.

## Decisions (2026-11-10)

- **Local AI:** an uncensored model running on the device is a core feature. Current choice: Gemma 4 E4B abliterated (`olekk/gemma-4-E4B-it-abliterated-litert-lm`), GPU or CPU, because third-party models cannot use the Tensor NPU. It is a community build, pinned by SHA-256.
- **AI actions:** the AI acts on notes only: create, tag, link, search and summarize. It does not launch other apps and does not run code.
- **Network:** SeBrain has internet access. `TailnetGateway` is the single exit: HTTPS only, over the device's normal connection, which goes through Tailscale when the user has it on. A connection can be made to require Tailscale, for example to reach the user's own machines. Note content never leaves in plaintext; that still holds.
- **Models from Hugging Face:** the app searches and downloads LiteRT-LM models, checks each file against the SHA-256 Hugging Face publishes, and the user confirms each model before it is trusted.
- **Trackers:** SeBrain has no telemetry and no third-party network code, and it removes tracking parameters from links that enter it. It does not filter the traffic of other apps.
- **Integration:** share into and out of SeBrain, capture notifications from chosen apps, and import or export Markdown compatible with Obsidian. Exported files are outside the vault and unencrypted; that is the user's explicit choice per export.
- **Cryptography:** widely used modern algorithms now (AES-256-GCM, ECDH P-256 in StrongBox, HKDF-SHA256, HMAC-SHA256). Post-quantum protection is added when data leaves the device or is stored for the long term; see below.

## Plan and status

Updated 2026-11-10. "Built" means it compiles and its local tests pass; "verified" means it also ran on the Pixel.

| Step | Contents | Status |
| --- | --- | --- |
| 0 | Foundations: encrypted vault (SQLCipher, sqlite-vec, FTS5), Keystore keys, Share into the app | Verified on the Pixel |
| 1 | Notes like Obsidian: writing, `#tags`, `[[links]]`, backlinks, tag filter, edit, delete, share out | Built and verified on Pixel |
| 1b | Search by words and by meaning (EmbeddingGemma on the NPU) | Built and verified on Pixel |
| 1c | Internet access and the Hugging Face model browser | Built and verified on Pixel |
| 2 | Local AI chat: questions answered from the notes with citations, using the uncensored model; actions on notes (create, tag, link, search) | Built and verified on Pixel |
| 3 | Link cleaning: strip tracking parameters from links that enter | Built, unit-tested |
| 4 | Integrations and mini-brains: typed captures, provenance, source filters, and notification capture with a per-app allowlist | Implemented and verified on Pixel with Termux notification capture, provenance, source filtering and a saved mini-brain |
| 5 | Obsidian import and export (Markdown) | Built, limitations documented |
| 6 | Hardening: encrypted backup, post-quantum layer, privacy checks, threat model review | Privacy/dependency audit complete; encrypted export, temporary restore validation and derived-index rebuild implemented and SAF E2E verified on Pixel; PQC envelope design complete; provider benchmarks and activation remain pending |

### Markdown interoperability limitations

The Markdown bridge imports one document at a time through the Android file picker and uses the
filename (without the final `.md`) as the note subject. It preserves the document text and
reuses SeBrain's existing `#tags` and `[[links]]` parser; it does not attempt to reproduce
Obsidian plugins, front matter, attachments, canvases, or a folder hierarchy. Export writes one
selected note to a user-chosen unencrypted `.md` file outside the encrypted vault. Duplicate
text is rejected by the existing content-deduplication rule.

**One verification session** on the Pixel covers steps 1, 1b, 1c and 2 together.

Open criteria from the research document, measured in step 6: search over 10,000 notes in under 100 ms, and the right chunk in the top 8 for at least 80% of 50 real questions.

Designed but not built: processing with the phone locked (encrypted outbox, `docs/design-vault-session.md`), and a background `CognitionWorker` with automatic extraction (`docs/design-cognition-worker.md`). Both are lower priority under the current product direction.

Integration architecture is specified in `docs/design-integrations-and-mini-brains.md`.
The graph/database contract must be approved before schema changes are applied.
SeBrain is intentionally a privacy control plane rather than a container or
traffic interceptor for other apps. It can monitor explicitly shared data,
allowlisted notifications and approved providers, but Android remains the
authority for other apps' permissions and network behavior.
The initial hardening audit is recorded in `docs/threat-model.md`.

## Handoff for future agents

This is an operational handoff. Read it before changing backup, indexing,
integration or security behavior.

- **Current branch:** `feature/integrations-mini-brains`.
- **Worktree state:** changes are uncommitted; do not revert unrelated work.
- **Completed:** `NoteIndexer.rebuildAll` rebuilds derived chunks, FTS/markup and
  available vectors in bounded batches.
- **Backup validation:** restore targets a uniquely named temporary SQLCipher
  vault, rebuilds derived indexes, checks the restored note count, then deletes
  the temporary database and wrapped key. The active vault is not replaced.
- **Verified:** real SAF export and restore on Pixel `57301FDCR001DP` passed.
  The flow restored 2 notes into a temporary vault and confirmed the active
  vault was unchanged; the test artifact was deleted afterward.
- **Do not claim:** retrieval-quality accuracy, PQC provider readiness or
  active-vault replacement.
- **Next safe action:** keep active-vault activation as a separately approved
  design decision; do not implement it as an implicit extension of validation.

## Post-quantum cryptography

Decided 2026-11-10: SeBrain uses native post-quantum cryptography instead of the user's TypeScript `pqc-sdk`, which a Kotlin app cannot run.

- **Signatures:** ML-DSA-65 or ML-DSA-87 from Android Keystore, which supports them natively since Android 17.
- **Encryption:** hybrid, the current ECDH plus ML-KEM, so the result is at least as strong as either. Android Keystore has no ML-KEM yet, so it would come from Bouncy Castle, with the private key wrapped by a StrongBox key.
- **Where:** encrypted backups and any data sent over the network. Tailscale's WireGuard is not post-quantum, so data that must stay secret for years gets this layer on top.

This changes the key hierarchy, so it is proposed and approved before it is implemented.

## Interface (2026-11-11)

The app is one Compose activity (`MainActivity`, Material 3 dark mode) with five tabs:
Notes (search, filters, note cards, network preview, full-screen viewer and editor), Graph
(force-directed network with source filters), Ask (chat with the local model, streaming replies,
model picker), Models (Hugging Face search, download, import, remove), and Integrate (encrypted
notification allowlist). Screen state lives in `ui/AppState`;
vault content is cleared from it in `onStop`.
A model file is hashed once and the result is remembered for its exact size and modification
time (`ModelFiles.verifyOnce`), so a multi-gigabyte model is not hashed on every load.

Checked on the Pixel 10 on 2026-11-11: the app starts, Gemma 4 E4B abliterated loads on GPU in
20 to 30 s and answers a question citing a note. That model reports no native function calling.

## Chat and notes (2026-11-11, second pass)

The `ACTION` text protocol was removed: on the phone the model wrote an action for every
message and copied the user's request into the note. Now the model only ever answers.
`AppState.wantsNote` detects a request to save (a "note" word plus a save verb, in Spanish or
English); the prompt then asks for `# Title`, the content, and a `Tags:` line, and
`createNoteFrom` stores the reply as an Obsidian-format note: `# Title`, body, `[[links]]` to
the notes the reply cites, and inline `#tags`. Every answer also has a "Save as note" button.

The Graph tab draws notes as nodes and `[[links]]` as edges with a force-directed layout
(`ui/GraphScreen`); links to notes that do not exist yet are grey nodes. It supports source
filters, pinch to zoom, drag to pan, and node taps. Source-filtered graph,
notification capture and saved mini-brain behavior were verified on the Pixel with Termux.
