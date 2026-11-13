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
- **Integration:** share into and out of SeBrain, and import or export Markdown compatible with Obsidian. Exported files are outside the vault and unencrypted; that is the user's explicit choice per export.
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
| 4 | Integrations and mini-brains: typed captures, provenance, source filters. Notification capture was removed on 2026-11-12 | Implemented and verified on Pixel with Termux notification capture, provenance, source filtering and a saved mini-brain |
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
approved providers, but Android remains the
authority for other apps' permissions and network behavior.
The initial hardening audit is recorded in `docs/threat-model.md`.

## Handoff for future agents

This is an operational handoff. Read it before changing backup, indexing,
integration or security behavior.

- **Current branch:** `feature/integrations-mini-brains`.
- **Worktree state:** all work is committed on this branch (last checked 2026-11-12); it is not merged into `main` yet.
- **Verified 2026-11-12:** `assembleDebug`, `testDebugUnitTest`, `lintDebug` and `scripts/check-release-privacy.sh` pass. The release privacy check now rebuilds the release APK first and fails if `QUERY_ALL_PACKAGES` or a notification listener is declared.
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

## Privacy panel (2026-11-12)

The user wants control over what other apps do. Notification capture was removed because the
app list it showed was confusing; its leftover encrypted allowlist and Keystore key are deleted
at startup (`LegacyNotificationCleanup`). Notes already captured from notifications stay.

The Privacy tab audits the apps in the launcher, read-only and on the device:

- **Trackers:** `DexTrackerScanner` reads each APK's `classes*.dex` in 1 MiB blocks and looks
  for the class descriptors of known SDKs (`TrackerSignatures`, about 60 trackers based on the
  Exodus Privacy signatures, bundled). Obfuscated trackers are missed. Results are kept in
  memory for the process, keyed by package, version and install time; nothing is written.
- **Permissions:** sensitive permissions the user granted (location, microphone, camera,
  contacts, SMS, and so on). The "Manage permissions" button opens Android's app settings,
  which is where they are revoked.
- **Data use:** Wi-Fi plus mobile bytes per app over 30 days from `NetworkStatsManager`. It
  needs usage access (`PACKAGE_USAGE_STATS`), granted by the user in Settings.

Package visibility comes from a `<queries>` launcher intent, not `QUERY_ALL_PACKAGES`.
Not yet checked on the Pixel.

**Next, needs an approved design:** a work profile managed by SeBrain (or a separate
"SeBrain Shield" app) with a local `VpnService` firewall inside the profile, so Tailscale
keeps working in the personal profile. It sees domains, apps and byte counts, never content.
It changes the "does not filter other apps' traffic" decision above.

## Assistant over the graph (2026-11-12)

- **Graph context:** `chat/VaultContext` searches the vault (hybrid search, chunks grouped by
  note, top 4 notes), then adds up to 4 neighbours one step away through `[[links]]`,
  backlinks and shared tags. `chat/Assistant` renders each note with its tags and links, so
  the model sees how ideas relate. A short follow-up is searched together with the previous
  question.
- **Better notes:** the note prompt lists the titles the new note may link to and the tags in
  use. Citations `[n]` become `[[Title]]` links, and a `[[link]]` to a title that does not
  exist becomes plain text, so made-up links no longer add false nodes to the graph.
- **Stop fixed:** every reply runs in a fresh LiteRT-LM conversation (`ChatEngine.reply`, with
  `ConversationConfig.initialMessages` as history, checked in the 0.18.1 jar). The wait no
  longer depends on a native callback after `cancelProcess`, which left the chat stuck.
- **Saved chats:** schema v4 (approved 2026-11-12) adds `conversation` and `message` tables in
  the vault. They are not chunked, so they never show in note search, and they are not in the
  backup snapshot yet. The Chats button next to Model lists, opens and deletes them.
- History sent to the model: the last 6 messages, 700 characters each. The model's context
  size (`EngineConfig.maxNumTokens`) is left at the model default; not measured.
- Not yet checked on the Pixel: the v4 migration and the new chat.

## Model lifetime and background replies (2026-11-12)

- The language model is loaded when the app starts, not when the chat opens, and it stays
  loaded when the app leaves the screen. It is released 5 minutes after leaving, if no reply
  is being written. Measured on the Pixel: about 23 s to load Gemma 4 E4B on GPU at launch,
  and no reload after going home and back.
- `GenerationService` is a foreground service of type `shortService` that runs only while a
  reply is being written, so Android does not freeze the process when the user switches apps.
  It is not a resident service. Android limits a short service to about 3 minutes; a longer
  reply loses the protection. When the reply ends off screen it is stored in the vault,
  cleared from memory, and an "answer is ready" notification is shown (no vault content).
- Screen off stops the reply, because the vault closes then and nothing is processed while
  locked. The partial reply is kept in memory and stored at the next start.
- Not yet checked on the Pixel: a reply finishing while another app is on screen, and GPU
  speed in that state.

## Memory architecture (2026-11-12)

The model has a small context window, so memory is layered. Each layer is built without an
extra model call (`chat/Assistant.remember`, `chat/VaultContext`).

| Layer | What it holds | Where it lives |
| --- | --- | --- |
| Working memory | The last messages word for word: up to 6, 1200 characters each, 3000 in total | Sent as chat history (`initialMessages`) |
| Conversation outline | One line for each older message: what the user asked, the title of what was answered | In the prompt |
| Covered topics | Titles (`# Heading`) of the notes and answers already written in this conversation | In the prompt, with "do not write them again" |
| Standing memory | Facts the user asked to keep. "Recuerda que…" / "Remember that…" saves a note tagged `#memory` without calling the model; the user edits or deletes it in Notes | Vault notes, in every prompt |
| Knowledge | Notes matching the message, plus graph neighbours | Vault (hybrid search + links + tags) |
| Episodes | Saved conversations | Vault, `conversation` and `message` tables |

Fixes that came with it: a note already written in the conversation is left out of the
retrieved context (the model was copying it when asked for "the next one"), and the prompt
ends by asking for the language of the message, because notes in English pulled replies into
English.

Search over past conversations (schema v5, approved 2026-11-12): `message_fts` indexes every
message; the assistant gets the best 2 matches from other conversations ("From earlier
conversations with the user"), and the Chats panel has a search field. Words under four
letters do not search. Schema v5 also adds `conversation.summary` and
`conversation.summarized_until`, not used yet. Built and unit-tested; `ConversationStoreTest`
passes on the Pixel, and v5 is applied to the real vault.

Summary of long conversations: when 8 messages have left the recent window since the last
summary, the model summarizes the oldest part (120 words) right after a reply, on the chat
thread. It is stored in `conversation.summary` and replaces those messages in the prompt. A
new message from the user cancels a summary in progress; it is tried again after the next
reply. Measured on the Pixel: 19 s to write, and the facts were recalled from it.

Suggested facts: the "Suggest memories" button under the last reply asks the model for at most
3 lasting facts the user stated in the conversation. They appear as "Remember this?" rows;
"Save" turns one into a `#memory` note, dismissing one hides it for that conversation. The
assistant never writes to standing memory on its own. Measured on the Pixel: 9 s, three
correct facts, the known one not repeated.

Backup format v2 (approved 2026-11-12): the snapshot now carries the conversations, their
messages and summaries; v1 backups are still read. Checked on the Pixel with a test vault:
export, restore into another vault, messages searchable. The Notes screen, filtered by
`#memory`, says how many memory notes there are and warns when they pass what the assistant
reads (8 notes, 300 characters each).

Left in `docs/plan-memory.md`: the 20-conversation evaluation.

## Note format (2026-11-12)

`chat/NoteFormat` owns the format of the notes the assistant writes.

- **Template given to the model:** `# Title`, one or two sentences, `## Key points` (three to
  six bullets), optional `## Details`, and a last `Tags:` line. Section names are in Spanish
  ("Puntos clave", "Detalles") when the request is in Spanish.
- **Several notes in one request:** each note starts at its own `# Title`; `##` is only for
  sections. At most 5 notes per reply. Measured: three notes in 77 s.
- **Cleaning, whatever the model wrote:** text before the first title and a closing question
  are dropped; a code fence around the whole reply is removed; `*` and `•` bullets become
  `-`; the title loses bold marks, "Title:" labels and final punctuation; tags are lowercased,
  hyphenated and limited to 4; a `#` comment inside a code block is not taken for a title.
- A note whose title already exists is not saved again, because links point to titles.
- The titles written in a conversation (all of them, for a reply with several notes) feed the
  "already written" list, so "the next one" skips them.

## Next direction: one memory, several brains (proposed 2026-11-12)

The user asked for one kind of memory (the notes), several brains that combine data, full
Obsidian-style combining of concepts, and memory that can feed the local model or an
external one. The research and architecture are in `docs/plan-brains.md`; the seven phases,
each with design, steps, tests and acceptance criteria, are in `docs/brains/`. Nothing is
built. Phase 6 (other models) changes the hard rule that note content never leaves the
device and waits for an explicit decision.

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
model picker), Models (Hugging Face search, download, import, remove), and Privacy (privacy panel,
encrypted backup, mini-brains). Screen state lives in `ui/AppState`;
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
