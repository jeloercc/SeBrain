# SeBrain — context for Claude Code

Native Android app (Kotlin + C/JNI) that acts as a 100% local second brain on a Google Pixel 10 (Tensor G5).
It ingests data from other apps, processes it with on-device AI and stores it encrypted.
Full research document (the reason behind each decision): https://claude.ai/artifact/VzkdPNuHAbtrMDV83R2HSJ

## Hard rules (non-negotiable)

- The app does NOT declare `android.permission.INTERNET`. If a dependency merges it, remove it with `tools:node="remove"`.
- Zero analytics, telemetry or crash reporting SDKs (Firebase, Crashlytics, etc.).
  - **Exception pending confirmation:** ML Kit GenAI (`com.google.mlkit:genai-prompt`, optional behind a setting) pulls in `transport-backend-cct`, `transport-runtime` and `firebase-*` libraries. Result in `docs/benchmarks.md`.
- No plaintext data leaves the process. Nothing is written to disk unencrypted, except model binaries.
- `allowBackup="false"`, `dataExtractionRules` that exclude everything, `FLAG_SECURE` on screens with vault content.
- Models are imported locally (SAF / `adb push`) and verified with a SHA-256 pinned in code. The app downloads nothing.
- Every native library aligned to 16 KB pages (`-Wl,-z,max-page-size=16384`, NDK r27+).
- Language: code, comments, identifiers, repo docs, commit messages, system prompts and JSON keys are in English. Extracted values keep the source language of the captured text (do not translate user data). Conversation with the user stays in Spanish.

## Decided stack

| Layer | Use | Do NOT use (discarded) |
| --- | --- | --- |
| Background LLM | LiteRT-LM (Kotlin) **pinned to `litertlm-android:0.18.1`** + `litert-npu-runtime-google-tensor:2.3.2`, backend NPU → GPU → CPU. 0.19.1 fails with that runtime (`undefined symbol: LiteRtDispatchQueryInterface`). Do not upgrade without retesting the NPU on the Pixel 10 | MLC LLM / TVM (does not use the Tensor G5 TPU) |
| NPU acceleration | LiteRT CompiledModel + Google Tensor ML SDK (Beta) | NNAPI delegate (deprecated in Android 15) |
| Interactive LLM | LiteRT-LM by default. Optional behind a setting, outside the core: ML Kit GenAI Prompt API (Gemini Nano), only with an Activity in the foreground; the app does not depend on the `nano-*` version | Gemini Nano from a Worker or foreground service (`BACKGROUND_USE_BLOCKED`) |
| Embeddings | EmbeddingGemma 2 Text 270M, Matryoshka at 256 dims, L2 normalized | — |
| Extraction model | Gemma 3 1B int4 (move up to Gemma 4 E2B if the TPU sustains it) | — |
| Database | `sqlcipher-android` (own build) with raw key `x'…'` | `android.database.sqlite`, Room without SQLCipher |
| Vectors | `sqlite-vec` compiled statically inside SQLCipher (`sqlite3_auto_extension`) | `sqlite-vss` (abandoned) |
| Text | FTS5, hybrid search with RRF (k = 60) | — |
| Deferred work | WorkManager (charging, battery not low, idle for large batches) | Resident services with the LLM loaded |

## Architecture (three processes, one vault)

1. **Ingestion** (always on, no AI): Share intent, `NotificationListenerService` with a per-package allowlist, tile + OCR (optional). Normalizes, deduplicates (SHA-256 of the canonical content) and encrypts with ECIES using the `K_inbox` public key into AES-GCM segments (`K_seg` ephemeral per segment). Works with the phone locked.
2. **Cognition** (`CognitionWorker`): decrypts segments → chunking → JSON extraction with constrained decoding → embeddings → inserts into `capture`, `chunk`, `chunk_fts`, `chunk_vec` in one transaction. Deletes the segment only after `COMMIT`. Loads the LLM per batch and releases it when done.
3. **Query** (app open): query embedding → RRF FTS5 + vec0 → top 8 → LiteRT-LM with `[n]` citations. With the Gemini Nano setting on, Nano is used and it falls back to LiteRT-LM if AICore returns `BUSY`.

### Keys (Android Keystore)

- `K_inbox`: EC P-256 pair, `PURPOSE_AGREE_KEY`, StrongBox (TEE fallback if StrongBox rejects it).
- `K_wrap`: AES-256-GCM, StrongBox, `setUnlockedDeviceRequired(true)`; wraps the 32-byte DEK stored in `noBackupFilesDir`.
- `K_secret` (optional): AES-256-GCM with `AUTH_BIOMETRIC_STRONG` for fields marked as secret.
- Never pass the DEK as a passphrase (avoids PBKDF2). Zero buffers after use.

### AccessibilityService

Disabled and out of scope until Phase 7. Android 17 revokes it with Advanced Protection on. Do not declare `isAccessibilityTool`.

## Verified environment (2026-11-07)

- Device: Pixel 10 (frankel), Android 17, SDK 37 (sdk_full 37.1), patch 2026-10-05.
- Bootloader locked (`verifiedbootstate=green`, `flash.locked=1`): ML Kit GenAI available. Do not unlock.
- `strongbox_keystore=300`, `hardware_keystore=500`. AICore 26.10.15.7.
- Development Mac: Intel x86_64, macOS 15.8.2, JDK 21 (Temurin 21.0.13 x64), Android command line tools installed by hand, no Homebrew (`ANDROID_HOME=~/Library/Android/sdk`; adb from `$ANDROID_HOME/platform-tools`).
- Google Tensor SDK Beta (Pixel 10 and 11): access granted. Not used yet; not used in the diagnostic spike.
- ML Kit Prompt API: `com.google.mlkit:genai-prompt:1.0.0-beta5`. `getBaseModelName()` observed on 2026-11-07: `nano-v4-full`.
- Tensor ML SDK: compiling models for the TPU requires a Linux x86_64 workstation. On the Mac, use already compiled TPU models (`.litertlm` from LiteRT Community / model garden) or a Linux container/VM. Do not try to compile for TPU on macOS.
- Spike model: `gemma-4-E2B-it_Google_Tensor_G5.litertlm` copied from the Edge Gallery app (SHA-256 `62faebcf…705f27`). **Spikes only.** In the final app the model must be obtained from an official source (`litert-community` on Hugging Face), with the Gemma terms accepted, and with the SHA-256 pinned in code. It is still imported via SAF / `adb push`: the app downloads nothing.

## Gradle modules

`:app` (Compose) · `:ingest` · `:cognition` · `:core:crypto` · `:core:vault` (includes the SQLCipher + sqlite-vec `.so`) · `:core:ml`

`minSdk` and `targetSdk`: Android 16/17 (the only device is the Pixel 10). Do not add compatibility with older versions.

## Phase 0 — "LLM + Keystore": closed (2026-11-08)

Each spike delivered minimal code + numbers measured on the Pixel 10. Details in `docs/benchmarks.md`.

- [x] ML Kit: `getBaseModelName()` returns a `nano-*` model and generates text with a locked bootloader and without `INTERNET`. Observed on 2026-11-07: `nano-v4-full`.
- [x] Keystore: does StrongBox accept `PURPOSE_AGREE_KEY` on EC P-256? Yes (`KeyInfo.securityLevel=STRONGBOX`, ECDH ≈ 44 ms).

**Decisions from Phase 0:**

- One ECDH per segment: under 1-2% of the time per segment; the per-window key proposal is rejected.
- The NPU works from a background Worker, but about 3x slower (`cpuset=/background`, CPUs 0-2).
- About 15 segments per background run under the 8-minute budget.
- One conversation per segment: `Conversation.clone()` is unavailable in 0.18.1 and a shared conversation contaminates later segments.
- LiteRT-LM pinned to 0.18.1. Constrained decoding uses `whitespace_flexible=false`, which removes the `\n\t` loop.
- `nano-v4-full` observed; ML Kit stays optional pending the telemetry analysis.
- The Worker does not run under forced Doze (blocked for 15 min, started 6 s after `unforce`); the design requires charging.

## Next phases

- **Phase 1, first task:** build `sqlcipher-android` with `sqlite-vec.c` (`-DSQLITE_CORE`) and FTS5, aligned to 16 KB. Criterion: `SELECT vec_version()` and FTS5 in `PRAGMA compile_options` on the device.
- **Phase 2, first task:** LiteRT-LM benchmark of EmbeddingGemma 2 (270M) on NPU, GPU and CPU. Measure latency, tokens/s, peak RAM and temperature after 10 min.
- **Phase 4, extraction-model benchmark:** compare Gemma 3 1B with Gemma 4 E2B on NPU, GPU and CPU, with the same measurements.

## Open decisions (do not assume; ask)

- Inbox processing mode: Strict (unlocked only) or Balanced (after first unlock).
- Confirm the ML Kit exception: LiteRT-LM is the default engine and ML Kit stays optional behind a setting, pending closure with the telemetry analysis in `docs/benchmarks.md`.
- Distribution: sideload only or private Play.
- Initial allowlist of apps for notifications.

## Way of working

- Role: Android systems architect. Technical and direct answers, no flattery or filler.
- Before using a LiteRT-LM, Tensor SDK or ML Kit GenAI API, verify the signature in the current documentation: they change quickly and the model's memory is out of date.
- Explicitly point out any HAL/OS limitation instead of silently working around it.
- Changes to the key hierarchy or the DB schema: propose first, do not apply directly.
