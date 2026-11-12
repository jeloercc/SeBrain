# SeBrain

SeBrain is a private, local-first second brain for the Google Pixel 10. It combines an
encrypted Obsidian-style vault with notes, tags, links, semantic search and an on-device
uncensored AI assistant.

Note content never leaves the device in plaintext and is encrypted at rest. Internet access
is limited to HTTPS through the app's `TailnetGateway`, used for the Hugging Face model
browser and model downloads.

## Current status

The project is under active development and is not a release build.

| Area | Status |
| --- | --- |
| Encrypted vault, SQLCipher, FTS5 and sqlite-vec | Verified on the Pixel 10 |
| StrongBox keys, encrypted inbox and deduplication | Verified on the Pixel 10 |
| Share text into the encrypted vault | Verified on the Pixel 10 |
| Notes, tags, links, backlinks, editing and deletion | Implemented and verified on the Pixel 10 |
| Text and semantic search | Implemented and verified on the Pixel 10 |
| Hugging Face model browser | Implemented and verified on the Pixel 10 |
| Local AI chat with citations | Implemented and verified on the Pixel 10 |
| Simple post-punk visual identity | Implemented in the app shell |
| Link tracking-parameter removal | Built and unit-tested |
| Notification capture, encrypted allowlist and source-filtered graph | Implemented; end-to-end Termux test passed on the Pixel 10 |
| Saved mini-brains and source-aware graph relations | Implemented and verified on the Pixel 10 with a Termux source |
| Obsidian Markdown import/export | Built; Pixel verification pending |
| Encrypted backup container | Encrypted SAF export, temporary-vault restore validation and derived-index rebuild verified on the Pixel; activation UI pending |

Verification results are recorded in [`docs/verification-phase1.md`](docs/verification-phase1.md).
The product plan and its priorities are in [`docs/roadmap.md`](docs/roadmap.md).

## Features

- Encrypted local vault backed by SQLCipher.
- FTS5 full-text search and `sqlite-vec` hybrid semantic search.
- Obsidian-style `#tags`, `[[links]]`, backlinks and graph visualization.
- Share text from other Android apps into an encrypted inbox.
- Local LiteRT-LM chat with streaming answers and note citations.
- Save an answer as a Markdown-compatible note.
- Import one Markdown document through SAF and export a selected note to an unencrypted `.md` file.
- Import, verify, download and remove approved `.litertlm` model files.
- SHA-256 verification for model files.
- Capture notifications only from user-selected packages, with an encrypted allowlist.
- Filter the graph by capture source, including `notification:<package>` sources.
- Save encrypted mini-brains as named filters over one or more sources.
- `FLAG_SECURE`, disabled Android backup and encrypted vault data.
- Passphrase-encrypted backup container with authenticated metadata; export and temporary-vault validation controls are exposed in **Integrate**. SAF E2E is verified on the Pixel; active-vault activation is not complete.
- No analytics, telemetry or crash-reporting SDKs.

## Architecture

The repository is split into focused Android modules:

| Module | Responsibility |
| --- | --- |
| `:app` | Compose UI, navigation, chat orchestration and app wiring |
| `:ingest` | Share intent, notification listener, normalization, encryption and deduplication |
| `:cognition` | Inbox import, chunking, note markup and indexing |
| `:core:crypto` | Keystore-backed keys, encrypted segments and payloads |
| `:core:vault` | SQLCipher database, FTS5, sqlite-vec and vault schema |
| `:core:ml` | LiteRT-LM chat, embeddings and model verification |
| `:core:net` | The single HTTPS network gateway and Hugging Face client |

The current UI has five sections:

- **Notes** — browse, search, edit, tag and delete notes.
- **Graph** — inspect links and navigate between notes.
- **Ask** — query the local model with vault context and citations.
- **Models** — search Hugging Face, import/download models and manage installed files.
- **Integrate** — review installed packages, choose notification sources and open Android's
  notification-listener settings.

## Security boundaries

- Vault data is encrypted with SQLCipher.
- `K_wrap` protects the vault DEK with an unlock-required StrongBox key.
- `K_inbox` uses EC P-256 ECDH and ephemeral AES-GCM segment keys.
- `K_dedup` uses StrongBox HMAC-SHA256 tags.
- Integration package policies are encrypted with an Android Keystore AES-GCM key.
- Model files are accepted only after SHA-256 verification.
- Notification capture is deny-all until the user selects exact package names.
- The initial notification allowlist is empty; no package is enabled by default.
- `QUERY_ALL_PACKAGES` is used for the current sideload/private development build so the
  integration screen can enumerate installed packages. Google Play distribution requires
  policy review for this permission.
- Only `:core:net` opens network connections, and only over HTTPS.
- No note content is sent to the network in plaintext.
- Native libraries are built for 16 KB page alignment.

Locked-phone cognition and the encrypted outbox are designed but not implemented yet.
See [`docs/design-vault-session.md`](docs/design-vault-session.md) and
[`docs/design-cognition-worker.md`](docs/design-cognition-worker.md).

## Requirements

- macOS development environment currently used by the project
- JDK 21
- Android SDK with API 37
- Android NDK `30.0.16248370`
- Gradle wrapper included in the repository
- Google Pixel 10 running Android 17 for device verification
- USB debugging enabled for phone tests

The project targets Android API 36+ and does not provide compatibility with older Android
versions.

## Build

Build the application:

```sh
./gradlew :app:assembleDebug
```

Build and install on the connected Pixel:

```sh
ANDROID_SERIAL=<serial> ./gradlew :app:assembleDebug :app:installDebug
```

The serial can be found with:

```sh
adb devices
```

## Tests

Run local unit tests:

```sh
./gradlew test
```

Run the complete Pixel verification script:

```sh
scripts/run-phone-tests.sh <serial>
```

Run release privacy checks after assembling the release APK:

```sh
./gradlew :app:assembleRelease
scripts/check-release-privacy.sh
```

The phone script runs unlocked instrumented tests, share-to-vault checks, vault reopen
checks, lock/unlock phases and encrypted inbox import checks. The device must start
unlocked, with the screen on and connected over USB. Follow the prompts in the script when
it locks the device.

## Models

The app does not bundle multi-gigabyte model weights. Models can be imported with Android's
file picker, pushed with `adb`, or downloaded from the in-app Hugging Face browser.

Approved model definitions and hashes live in `core/ml`. The current model roles are:

- Embeddings: EmbeddingGemma 2 Text 270M, 256 dimensions.
- Interactive chat: Gemma 4 E4B abliterated, with a smaller E2B alternative.

Model licenses and source revisions must be checked before distribution. Community models
are not treated as trusted until their published SHA-256 matches the imported file.

## Documentation

- [`docs/roadmap.md`](docs/roadmap.md) — current product plan and status.
- [`docs/verification-phase1.md`](docs/verification-phase1.md) — device verification log.
- [`docs/design-postpunk.md`](docs/design-postpunk.md) — visual direction.
- [`docs/design-integrations-and-mini-brains.md`](docs/design-integrations-and-mini-brains.md) —
  integration boundaries, source provenance and mini-brain design.
- [`docs/threat-model.md`](docs/threat-model.md) — hardening audit, threats and remaining security work.
- [`docs/design-encrypted-backup.md`](docs/design-encrypted-backup.md) — encrypted backup format and implementation status.
- [`docs/design-post-quantum.md`](docs/design-post-quantum.md) — hybrid post-quantum envelope design and implementation gate.
- [`docs/design-phase1-mvp.md`](docs/design-phase1-mvp.md) — encrypted Share MVP.
- [`docs/design-search.md`](docs/design-search.md) — indexing and hybrid search design.
- [`docs/design-vault-session.md`](docs/design-vault-session.md) — vault session and lock behavior.
- [`docs/design-cognition-worker.md`](docs/design-cognition-worker.md) — deferred cognition worker.
- [`docs/benchmarks.md`](docs/benchmarks.md) — Pixel measurements and diagnostic results.
- [`docs/retrieval-evaluation-template.csv`](docs/retrieval-evaluation-template.csv) — input format for the 50-question retrieval evaluation.

## Development rules

The repository's security and architecture rules are documented in
[`CLAUDE.md`](CLAUDE.md). In particular:

- Do not add a second network client outside `TailnetGateway`.
- Do not write plaintext vault data to disk.
- Do not add analytics, telemetry or crash-reporting SDKs.
- Do not change the key hierarchy or vault schema without documenting and approving the
  design first.
- Do not enable notification capture without an explicit package allowlist.
- Keep code, comments, identifiers, documentation and system prompts in English.
