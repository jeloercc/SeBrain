# Phase 1 verification log

This file records device verification attempts separately from design and implementation
documents. A feature is marked **verified** only after it has run on the Pixel 10; compiling
or passing local tests is not enough.

## 2026-11-11 — verification session started

### Baseline

- Repository: `jeloercc/SeBrain`
- Branch: `phase1-vault`
- Worktree: clean before this documentation change
- Verification scope: roadmap steps 1, 1b, 1c and 2
- Device required: unlocked Pixel 10, screen on, USB debugging enabled

### First gate: device connection

Command:

```text
adb devices
```

Result:

```text
List of devices attached
```

No device was listed. No instrumented tests or interactive app verification were run.

### Current decision

Verification is paused at the device-connection gate. Do not mark roadmap steps 1, 1b, 1c
or 2 as verified until the Pixel session has completed and each result is recorded here.

## 2026-11-11 — visual identity pass

The initial launcher icon was replaced with a simple post-punk mark, and the app shell palette
was changed from neon cyberpunk to ink black, bone, oxidized red and muted olive. The detailed
direction is recorded in `docs/design-postpunk.md`.

The app built and installed successfully on the Pixel. The launcher icon was visible after
installation. The first attempted in-app screenshot was not counted as verified because
Instagram remained the focused visible window; no feature verification was claimed.

## 2026-11-11 — Notes redesign check

The redesigned Compose shell was rebuilt and installed with:

```text
ANDROID_SERIAL=57301FDCR001DP ./gradlew :app:assembleDebug :app:installDebug
```

The Pixel UI hierarchy confirmed these rendered elements:

- `SeBrain`
- `[ ENCRYPTED VAULT - SQLCIPHER ]`
- `[ LOCAL AI: ONLINE ● ]`
- `03. NETWORK GRAPH`
- `Markdown elements` represented by the link-pill label
- `NOTES`, `GRAPH`, `ASK`, `MODELS`

This confirms composition and installation, not complete visual or interaction acceptance.
Release builds intentionally exclude screenshots because `FLAG_SECURE` protects vault content.
Debug builds allow screenshots for visual verification on the connected test device.

## 2026-11-11 — Notes and wiki-link interaction

The debug build was rebuilt and installed after removing the visible `[[` and `]]` markers from
link pills:

```text
ANDROID_SERIAL=57301FDCR001DP ./gradlew -q :app:assembleDebug :app:installDebug
```

Manual Pixel checks:

- Created `Markdown elements` as a target note.
- Created `PROT-01 Cybernetic Neural Vault` with tags and `[[Markdown elements]]` in its body.
- Confirmed the rendered pill displays only `Markdown elements`, with no square brackets.
- Opened the priority note and tapped the pill.
- Confirmed navigation opened the `Markdown elements` note.

Result: **PASS** for note creation, note rendering, wiki-link pill styling, and title-based
wiki-link navigation. The source text remains stored with wiki-link syntax; only the UI
presentation removes the markers.

Additional checks in the same session:

- Searching for `Markdown` returned both the target note and the linked priority note.
- The `GRAPH`, `ASK`, and `MODELS` bottom-navigation tabs opened successfully.
- The Models screen displayed the Hugging Face search field and the Installed section.

## 2026-11-11 — Search, Models, Chat and Graph

### Semantic search

- Models reported `Online · Semantic search on`.
- A `neural` query returned both test notes, including `PROT-01 Cybernetic Neural Vault`.
- The installed EmbeddingGemma model was visible in the Installed list.

Result: **PASS** for the integrated semantic-search path with the installed embedding model.

### Model browser

- Searching Hugging Face for `gemma` returned model repositories.
- Opening `PeppX/gemma-4-e2b-uncensored-litertlm` listed its `.litertlm` files and sizes.
- The download confirmation was not accepted; no unverified third-party model was downloaded.

Result: **PASS** for search and file listing. Download and SHA-256 acceptance remain intentionally
untested because they require selecting and trusting a specific external model.

### Local chat

- `Gemma 4 E4B abliterated` loaded on device using the GPU backend.
- The question `What covers the cybernetic vault?` returned:
  `The cybernetic vault is covered by the PROT-01 Cybernetic Neural Vault [1].`
- `Save as note` completed and showed `✓ Saved as a note`.

Result: **PASS** for model loading, cited answer generation and saving an answer as a note.

### Graph

- The graph displayed `2 notes · 1 links`, with both coral nodes and the link relationship.
- Tapping a node opened its note detail.
- A drag gesture moved the graph contents, confirming pan handling.

Result: **PASS** for rendering, node navigation and pan. Pinch-zoom was not independently
verified because the available ADB input interface cannot generate a two-pointer gesture; the
Compose gesture handler remains present in `GraphScreen.kt`.

## 2026-11-11 — Markdown interoperability implementation

The Obsidian-compatible Markdown bridge was implemented and covered by unit tests:

- `IMPORT` opens the Android document picker and imports one `.md` or text document.
- The filename becomes the note subject; the document text remains unchanged.
- Existing `#tags` and `[[links]]` parsing is reused.
- `EXPORT` writes the selected note to a user-chosen unencrypted `.md` file outside the vault.
- Duplicate text is rejected by the existing vault rule.

Validation:

```text
./gradlew :cognition:testDebugUnitTest :app:compileDebugKotlin
```

Result: **PASS** for implementation, unit tests and compilation. Pixel end-to-end import/export
interaction remains pending; the device was locked and dozing during the attempted continuation.

## 2026-11-11 — Graph preview correction

The Notes graph preview was corrected after screenshot review:

- Removed the incorrect `03.` prefix.
- Replaced the fabricated center-and-spokes drawing with one dot per actual `NoteGraph` node.
- Draws preview edges only for actual stored links.
- Removed the sample link pill from the graph preview.
- Keeps the empty-vault message when there are no notes.

The app compiled and the debug APK was installed on the Pixel. A new screenshot is pending
because the device was locked and dozing during this pass.

## 2026-11-11 — Notification integration end-to-end

The Pixel was awake and unlocked for this verification:

1. Enabled `com.sebrain.app/com.sebrain.ingest.NotificationCaptureService`.
2. Used Termux as the controlled source package already selected in the encrypted allowlist.
3. Started Termux and observed its foreground notification.
4. Confirmed encrypted inbox segments were created under `no_backup/inbox`.
5. Restarted SeBrain to import the segments.
6. Confirmed the Notes screen showed captures with source `notification:com.termux`.
7. Opened Graph and confirmed the source filter `NOTIFICATION:COM.TERMUX` was present.

Result: **PASS** for listener registration, allowlist enforcement path, encrypted inbox
ingestion, note import and source-filtered graph visibility. This test did not inspect plaintext
notification content on disk.

## 2026-11-11 — Saved mini-brain verification

On the same Pixel installation:

1. Opened **INTEGRATE** and confirmed the `MINI-BRAINS` controls.
2. Created the `Terminal` brain using the `notification:com.termux` source.
3. Confirmed the saved entry displayed `Saved: Terminal · 1 source(s)`.
4. Opened **GRAPH** and confirmed the `TERMINAL` brain filter was present.

Result: **PASS** for creating, encrypting, loading and displaying a saved mini-brain.
The definition stores only its name and source IDs; note text remains in the encrypted vault.

## 2026-11-11 — Backup controls verification

On Pixel `57301FDCR001DP`, after installing the current debug build:

1. Opened **INTEGRATE** and confirmed `EXPORT ENCRYPTED BACKUP` and `VALIDATE BACKUP`.
2. Opened the export dialog and confirmed the passphrase field and `CHOOSE DESTINATION`
   action.
3. Opened the validation dialog and confirmed the passphrase field and `CHOOSE BACKUP`
   action.

Result: **PASS** for the backup controls and dialogs. SAF file selection and a
full user-driven export/restore were not completed in this session; the underlying
codec, logical snapshot, export path and temporary-vault validation are covered
by local tests and compilation.

## 2026-11-11 — derived-index rebuild validation

The temporary restore path now calls `NoteIndexer.rebuildAll` after restoring the
logical snapshot. It rebuilds derived chunks and FTS/markup data in bounded
batches, and rebuilds vectors when an embedding provider is available. The
temporary restore also checks that the restored note count matches the number of
indexed notes before deleting the temporary database and wrapped key.

Local validation command:

```text
./gradlew :core:crypto:test :core:vault:test :cognition:testDebugUnitTest :app:compileDebugKotlin
```

Result: **PASS**. This validates compilation and local tests only; the later
Pixel SAF export/restore result is recorded below.

## 2026-11-11 — release privacy validation

The release artifact was rebuilt and checked with:

```text
./gradlew :app:assembleRelease
scripts/check-release-privacy.sh
```

Result: **PASS**. The scripted release privacy checks passed. This is an
artifact/static check and does not replace device verification.

## 2026-11-11 — full local test suite

Command:

```text
./gradlew test
```

Result: **PASS** across all configured JVM unit-test tasks. Instrumented Pixel
tests and SAF file-picker interaction remain separate verification gates.

## 2026-11-11 — vault static security checks

Command:

```text
core/vault/scripts/check-static.sh
```

Result: **PASS**. The check confirmed 16 KB ELF `LOAD` alignment, `zipalign`,
absence of `INTERNET` from the vault manifests, no telemetry dependency in the
checked vault classpaths, and a release AAR native library.

## 2026-11-11 — encrypted backup SAF end-to-end

After manually unlocking Pixel `57301FDCR001DP`, the complete user-driven flow
was executed from **INTEGRATE**:

1. Entered a test passphrase and exported `sebrain-backup.sbbk` through the
   Android document picker to `Downloads`.
2. Confirmed the app reported **Backup exported**.
3. Selected the saved file through **VALIDATE BACKUP** and entered the same
   passphrase.
4. Confirmed the app reported: **Backup validated and restored to a temporary
   vault (2 note(s)); active vault unchanged**.
5. Confirmed the artifact began with the `SBBK` magic bytes and then deleted
   the temporary backup file from `Downloads`.

Result: **PASS** for SAF export, encrypted artifact creation, passphrase
validation, temporary-vault restore, active-vault preservation and cleanup.
This does not activate or replace the active vault.
