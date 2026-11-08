# Benchmarks — Phase 0

## Diagnostic spike (`spikes/diag/`), 2026-11-07

**Device:** Pixel 10 (`frankel`), Android 17, SDK 37 (`sdk_full` 37.1), patch 2026-10-05, build `CP3A.261005.005`, AICore 26.10.15.7. USB connected, battery 74%, Wi-Fi on.

**Build:** AGP 9.5.1, Gradle 9.6.0, KGP 2.4.0, JDK 21, `genai-prompt:1.0.0-beta5`, `minSdk` 36, `targetSdk` 37, `compileSdk` 37.1. Debug APK without `android.permission.INTERNET`.

**Runs:** two complete foreground runs (12:21:11 and 12:21:40), with the device unlocked and the app in the foreground for the whole test. Values in the format `run 1 / run 2`.

| Test | Result | Value | Note |
| --- | --- | --- | --- |
| `ro.boot.verifiedbootstate` / `ro.boot.flash.locked` | PASS | `green` / `1` | Bootloader locked |
| `FEATURE_STRONGBOX_KEYSTORE` | PASS | `true` | Via adb: `strongbox_keystore=300`, `hardware_keystore=500` |
| EC P-256 `PURPOSE_AGREE_KEY` keygen in StrongBox | PASS | 121.2 / 123.0 ms | **StrongBox accepts `AGREE_KEY`**; the TEE retry was not needed and did not run |
| EC `KeyInfo.securityLevel` | PASS | `STRONGBOX` (2) | |
| ECDH Keystore side (StrongBox private + ephemeral public) | PASS | 44.80 / 44.05 ms | Mean of 20; range 40.02–52.88 ms; equal secrets on every iteration |
| ECDH software side (ephemeral private + Keystore public) | PASS | 0.58 / 0.57 ms | Mean of 20; provider `AndroidOpenSSL` |
| Ephemeral keygen in software | PASS | 0.23 / 0.22 ms | Mean of 20 |
| Full ECDH (ephemeral + both sides) | PASS | 45.68 / 44.90 ms | Mean of 20 |
| AES-256-GCM keygen in StrongBox with `setUnlockedDeviceRequired(true)` | PASS | 63.6 / 25.8 ms | One sample per run |
| AES `KeyInfo.securityLevel` | PASS | `STRONGBOX` (2), 256 bits | |
| AES-GCM wrap of 32 bytes | PASS | 33.07 / 33.92 ms | Mean of 20; `Cipher.ENCRYPT_MODE` with `PURPOSE_ENCRYPT`, not `PURPOSE_WRAP_KEY`; includes `init` and `doFinal` |
| AES-GCM unwrap of 32 bytes | PASS | 32.74 / 32.44 ms | Mean of 20; `Cipher.DECRYPT_MODE`; matches the original on every iteration |
| `setUnlockedDeviceRequired` with the device locked | PASS (rejected) | `KeyStoreException: Device locked` | See section "`K_wrap` with the Pixel locked" |
| Gemini Nano `checkStatus()` | INFO | `DOWNLOADABLE` / `AVAILABLE` | After `download()` it became available |
| Gemini Nano `download()` | PASS | 2 s | Suspicious duration for a model; see observation 2 |
| `getBaseModelName()` | PASS | `nano-v4-full` | Reproduced 3 times; meets the updated criterion (`nano-*`, generates text, bootloader locked, no `INTERNET`). The documentation lists Pixel 10 as `nano-v3` |
| Prompt, time to first token, first call of the process | PASS | 587 / 422 ms | Streaming, no `warmup()`; not a guaranteed cold start (observation 3) |
| Prompt, total time, first call of the process | PASS | 946 / 740 ms | 3 chunks, 77 characters |
| Prompt, time to first token, second call | PASS | 358 / 360 ms | |
| Prompt, total time, second call | PASS | 693 / 710 ms | 3 chunks, 77 characters |
| Background Worker, `generateContent` | PASS (blocked) | `BACKGROUND_USE_BLOCKED` (30) | Process with `importance=400`, `foreground=false`; run once |
| Nano with `INTERNET` removed from the manifest | PASS | All Nano tests work | `tools:node="remove"`; verified with `aapt2 dump permissions` |

**Prompt used:** «Responde en una sola frase: ¿para qué sirve una llave?»

**Response (identical in all 4 calls):** «Una llave sirve para abrir o cerrar cerraduras y acceder a objetos o lugares.»

**Exact Worker error:**

```text
com.google.mlkit.genai.common.GenAiException: [ErrorCode 30] Background usage is blocked. Please use the API when your app is in the foreground instead.
Caused by: AICore failed with error type 2-INFERENCE_ERROR and error code 30-BACKGROUND_USE_BLOCKED: Inference failed.
```

### Observations

1. **Model differs from the documentation.** The device reports `nano-v4-full`. The ML Kit documentation (consulted on 2026-11-07) lists Pixel 10 as `nano-v3` and `nano-v4` only on Pixel 11 and Galaxy Z Flip8/Fold8. No official documentation explaining this value was found. The CLAUDE.md criterion was changed so the app does not depend on the version.
2. **The download could not be measured.** `DOWNLOADABLE` became complete in 2 s, with no progress events recorded. The weights were probably already on the device, but this is not confirmed: the model size remains unmeasured. A previous run of the app (12:16:59) may have influenced this, and its logs were lost.
3. **"First call" does not mean cold.** The app process was new, but AICore may have kept the model loaded from previous calls. A real cold start would have to be measured after rebooting the device.
4. **Transitive telemetry.** `genai-prompt` pulls in `com.google.android.datatransport:transport-backend-cct`, `transport-runtime`, `firebase-encoders`, `firebase-encoders-json` and `firebase-components`. That is where `INTERNET` comes from. See the analysis below.
5. **Kotlin version.** `genai-prompt:1.0.0-beta5` is compiled with Kotlin 2.4.0, and the Kotlin built into AGP 9.5.1 (2.2.0) cannot read it. KGP 2.4.0 must be pinned on the `buildscript` classpath.
6. **Limited sample.** These are two runs, on a device connected via USB and without thermal control. The figures serve as an order of magnitude.

## `K_wrap` with the Pixel locked, 2026-11-07

**Method:** AES-256-GCM key in StrongBox with `setUnlockedDeviceRequired(true)`. With the phone unlocked, a random 32-byte DEK is encrypted; the IV, the ciphertext and the DEK's SHA-256 are stored in `noBackupFilesDir`. Then `LockProbeReceiver` is triggered via `am broadcast` in each state; `isDeviceLocked` is read inside the app and confirmed with `dumpsys trust`.

| Test | State | Result | Exception | Note |
| --- | --- | --- | --- | --- |
| Decrypt, control before (14:08) | Unlocked | PASS | — | SHA-256 matches |
| Encrypt, control before (14:08) | Unlocked | PASS | — | |
| Decrypt, probe 1 | Locked | PASS (rejected) | `InvalidKeyException` ← `KeyStoreException` | |
| Encrypt, probe 1 | Locked | PASS (rejected) | `InvalidKeyException` ← `KeyStoreException` | |
| Decrypt, probe 2 (18:49) | Locked, screen idle | PASS (rejected) | `InvalidKeyException` ← `KeyStoreException` | |
| Encrypt, probe 2 (18:49) | Locked, screen idle | PASS (rejected) | `InvalidKeyException` ← `KeyStoreException` | |
| Use a newly created key | Locked | Rejected | `InvalidKeyException` ← `KeyStoreException` | Observed by accident while trying to prepare the test |
| Decrypt after unlocking again (18:50) | Unlocked | PASS | — | SHA-256 matches; the key survives the lock |
| Encrypt after unlocking again (18:50) | Unlocked | PASS | — | |

```text
java.security.InvalidKeyException: Keystore operation failed
  at android.security.keystore2.AndroidKeyStoreCipherSpiBase.engineInit
  at javax.crypto.Cipher.init
Caused by: android.security.KeyStoreException: Device locked (internal Keystore code: -72)
    0: system/security/keystore2/src/enforcements.rs:590: device is locked.
    1: Error::Km(r#DEVICE_LOCKED)) (public error code: 2 internal Keystore code: -72)
```

**Notes:**

- The rejection arrives at `Cipher.init`, not at `doFinal`.
- The outer exception is `InvalidKeyException`, with `android.security.KeyStoreException` as the cause (`getNumericErrorCode()=2`). `UserNotAuthenticatedException` does not appear. App code must walk the cause chain to recognize the lock.
- After unlocking, the same key decrypts the stored blob again: the lock does not invalidate it.

## ML Kit telemetry without `INTERNET`, 2026-11-07

**Conclusion:** without `INTERNET`, no traffic from the app was observed and the upload attempt fails at DNS. But the SDK **does attempt to upload** to Firebase Logging and **stores events unencrypted** on disk. The exception remains pending confirmation.

**Method:** debug APK without R8 and without `INTERNET`, app data cleared with `pm clear`. With `log.tag.TRuntime.*` temporarily enabled, logcat was captured, the database was inspected with `run-as` and the job was forced with `cmd jobscheduler run -f`.

| Test | Result | Value | Note |
| --- | --- | --- | --- |
| App started without calling ML Kit (40 s) | No activity | 0 events, no database, no job | `MlKitInitProvider` and the services remain in the manifest |
| After using Nano (2 prompts) | Telemetry queued | 12 `FIREBASE_ML_SDK` events | `databases/com.google.android.datatransport.events` is created |
| Upload job scheduled | Yes | `JobInfoSchedulerService` | Requires a minimum wait of 1 day, `IDLE` and connectivity |
| Forced upload (1 attempt) | Blocked | `EPERM` | See the log below |
| Events after the attempt | Still on disk | 12, `num_attempts=0` | How long they are retained was not measured |
| UID traffic in `dumpsys netstats detail` | No traffic | 0 rows for the UID, out of 747 rows | |
| Process groups | No `inet` | `9997 20230 50230` | Group 3003 is missing |
| Prompt and response text in the payloads | Not found | 0 matches | Text search; the protobuf was not fully decoded |
| Prompt and response text in the app data | Not found | 0 matches | Recursive `grep` of the private directory |

```text
TransportRuntime.CctTransportBackend: Making request to: https://firebaselogging.googleapis.com/v0cc/log/batch?format=json_proto3
TransportRuntime.Executor: java.lang.SecurityException: Permission denied (missing INTERNET permission?)
  Caused by: android.system.ErrnoException: android_getaddrinfo failed: EPERM (Operation not permitted)
```

**Endpoints in the dex** (obfuscated in the code and decoded here):

- `https://firebaselogging.googleapis.com/v0cc/log/batch?format=json_proto3`
- `https://firebaselogging-pa.googleapis.com/v1/firelog/legacy/batchlog`

**What the events contain, unencrypted on disk:**

- **Metadata:** `device`, `model`, `manufacturer`, `hardware`, `product`, `fingerprint`, `os-uild`, `sdk-version`, `country`, `locale`, `mcc_mnc`, `tz-offset`, `net-type`, `mobile-subtype`, `application_build`.
- **Payload (110–142 bytes):** app package, SDK version, locale and an instance UUID.
- **`shared_prefs/com.google.mlkit.internal.xml`:** `ml_sdk_instance_id`, a persistent UUID.

**Conflicts with CLAUDE.md:**

- "Zero analytics, telemetry SDKs": violated if ML Kit is in the APK, even if it never sends anything.
- "Nothing is written to disk unencrypted": violated only with device metadata. No user content was found.

**About the setting:** a setting turned off avoided the events in the 40-s test, but the libraries, the init provider and the services remain inside the APK. Only a build flavor without ML Kit would remove them.

**Limits:**

- Only the app process was analyzed. AICore and Google Play Services are separate processes, with their own permissions, and were not analyzed.
- It is one run with a single forced upload attempt and no packet capture.
- In the phase without ML Kit, whether `ml_sdk_instance_id` is created was not checked.
- Removing the `datatransport` services from the manifest was not tested.

### Dependencies in the APK

Debug APK without R8. 83 entries of `debugRuntimeClasspath`, with versions already resolved (includes BOM and empty artifacts such as `listenablefuture`).

**Related to telemetry, Google and ML Kit:**

```text
com.google.android.datatransport:transport-api:2.2.1
com.google.android.datatransport:transport-backend-cct:2.3.3
com.google.android.datatransport:transport-runtime:2.2.6
com.google.android.gms:play-services-base:18.5.0
com.google.android.gms:play-services-basement:18.9.0
com.google.android.gms:play-services-tasks:18.2.0
com.google.firebase:firebase-annotations:16.0.0
com.google.firebase:firebase-components:16.1.0
com.google.firebase:firebase-encoders-json:17.1.0
com.google.firebase:firebase-encoders:16.1.0
com.google.mlkit:common:18.11.0
com.google.mlkit:genai-common:1.0.0-beta5
com.google.mlkit:genai-prompt:1.0.0-beta5
com.google.mlkit:genai-schema:1.0.0-alpha2
```

**Methods in the dex:** `com.google.android.gms` 11164, `com.google.android.datatransport` 1158, `com.google.mlkit` 1161, `com.google.firebase` 362.

**Third-party components merged into the manifest:** `TransportBackendDiscovery`, `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver`, `MlKitComponentDiscoveryService`, `MlKitInitProvider`, plus those of WorkManager and `androidx.startup`.

**Resulting permissions:** `aicore.service.BIND_SERVICE`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` and the app's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` permission. None is `INTERNET`.

<details>
<summary>Full list (83)</summary>

```text
androidx.activity:activity:1.6.0
androidx.annotation:annotation-experimental:1.4.1
androidx.annotation:annotation-jvm:1.9.1
androidx.annotation:annotation:1.9.1
androidx.appcompat:appcompat-resources:1.6.1
androidx.appcompat:appcompat:1.6.1
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.collection:collection-jvm:1.4.2
androidx.collection:collection:1.4.2
androidx.concurrent:concurrent-futures-ktx:1.1.0
androidx.concurrent:concurrent-futures:1.1.0
androidx.core:core-ktx:1.12.0
androidx.core:core:1.12.0
androidx.cursoradapter:cursoradapter:1.0.0
androidx.customview:customview:1.0.0
androidx.drawerlayout:drawerlayout:1.0.0
androidx.emoji2:emoji2-views-helper:1.2.0
androidx.emoji2:emoji2:1.2.0
androidx.fragment:fragment:1.3.6
androidx.interpolator:interpolator:1.0.0
androidx.lifecycle:lifecycle-common:2.6.2
androidx.lifecycle:lifecycle-livedata-core:2.6.2
androidx.lifecycle:lifecycle-livedata:2.6.2
androidx.lifecycle:lifecycle-process:2.6.2
androidx.lifecycle:lifecycle-runtime:2.6.2
androidx.lifecycle:lifecycle-service:2.6.2
androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2
androidx.lifecycle:lifecycle-viewmodel:2.6.2
androidx.loader:loader:1.0.0
androidx.profileinstaller:profileinstaller:1.3.0
androidx.resourceinspection:resourceinspection-annotation:1.0.1
androidx.room:room-common-jvm:2.7.0
androidx.room:room-common:2.7.0
androidx.room:room-runtime-android:2.7.0
androidx.room:room-runtime:2.7.0
androidx.savedstate:savedstate:1.2.1
androidx.sqlite:sqlite-android:2.5.0
androidx.sqlite:sqlite-framework-android:2.5.0
androidx.sqlite:sqlite-framework:2.5.0
androidx.sqlite:sqlite:2.5.0
androidx.startup:startup-runtime:1.1.1
androidx.tracing:tracing-ktx:1.2.0
androidx.tracing:tracing:1.2.0
androidx.vectordrawable:vectordrawable-animated:1.1.0
androidx.vectordrawable:vectordrawable:1.1.0
androidx.versionedparcelable:versionedparcelable:1.1.1
androidx.viewpager:viewpager:1.0.0
androidx.work:work-runtime-ktx:2.12.0
androidx.work:work-runtime:2.12.0
com.google.android.datatransport:transport-api:2.2.1
com.google.android.datatransport:transport-backend-cct:2.3.3
com.google.android.datatransport:transport-runtime:2.2.6
com.google.android.gms:play-services-base:18.5.0
com.google.android.gms:play-services-basement:18.9.0
com.google.android.gms:play-services-tasks:18.2.0
com.google.code.findbugs:jsr305:3.0.2
com.google.errorprone:error_prone_annotations:2.18.0
com.google.firebase:firebase-annotations:16.0.0
com.google.firebase:firebase-components:16.1.0
com.google.firebase:firebase-encoders-json:17.1.0
com.google.firebase:firebase-encoders:16.1.0
com.google.guava:failureaccess:1.0.1
com.google.guava:guava:32.0.0-jre
com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava
com.google.j2objc:j2objc-annotations:2.8
com.google.mlkit:common:18.11.0
com.google.mlkit:genai-common:1.0.0-beta5
com.google.mlkit:genai-prompt:1.0.0-beta5
com.google.mlkit:genai-schema:1.0.0-alpha2
javax.inject:javax.inject:1
org.checkerframework:checker-qual:3.33.0
org.jetbrains:annotations:23.0.0
org.jetbrains.kotlin:kotlin-stdlib -> 2.4.0
org.jetbrains.kotlin:kotlin-stdlib:2.4.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.11.0
org.jspecify:jspecify:1.0.0
org.reactivestreams:reactive-streams:1.0.3
```

</details>

## Gemma-4-E2B-it in Edge Gallery, 2026-11-07

Data measured by the user in Edge Gallery, on the Pixel 10. It does not come from the spike.

| Test | Backend | Run | TTFT | Prefill | Decode | RAM | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| JSON | TPU | 1 | 1206 ms | 142.6 tok/s | 19.2 tok/s | 1.03 GB | Valid JSON; Monterrey omitted in «lugares» |
| JSON | TPU | 2 | 1396 ms | 114.6 tok/s | 19.4 tok/s | 659 MB | Valid JSON; Monterrey omitted in «lugares» |
| RAG, 730 tokens | TPU | 1 | 2254 ms | 350.1 tok/s | 19.8 tok/s | 1.05 GB | Correct |
| Short sentence | TPU | 1 | 492 ms | 66.8 tok/s | 17.6 tok/s | 793 MB | — |
| Short sentence | CPU (2.6 GB) | 1 | 1914 ms | 18.5 tok/s | 14.3 tok/s | 2.29 GB | — |

**Conditions:** max tokens 4096 (not 1024); no temperature control in the UI; phone without noticeable heating.

## `CognitionWorker`: ECDH versus total time per segment, 2026-11-07

**Goal:** decide on `docs/proposal-ecdh-window.md`. Criterion set by the user: if ECDH is < 5% of the total time per segment, one ECDH per segment is kept.

**Code:** `spikes/diag/.../CognitionBenchWorker.kt`. It is a `CoroutineWorker` with `setForeground` (`dataSync`) and is launched with `am start ... --es mode cognition --es batches <n>`.

**Setup:**
- **Simulated ingestion** (outside the stopwatch): N synthetic texts of 150–450 characters, encrypted with ECIES against `K_inbox`, with the current hierarchy (software ephemeral per segment, HKDF-SHA256, AES-256-GCM). Each segment is written to `noBackupFilesDir`.
- **`K_inbox`:** EC P-256 `AGREE_KEY` in StrongBox (alias `diag_k_inbox_bench`).
- **Per segment, timed:**
  - file read;
  - ECDH in StrongBox (`KeyAgreement("ECDH", "AndroidKeyStore")`);
  - HKDF;
  - AES-GCM decryption;
  - LLM extraction: new conversation, `ResponseFormat.json(schema)`, `maxOutputToken = 256`, thinking disabled.
- **Total per segment:** all of the above. Engine load (one per batch) is measured separately.

**Engine:**
- `litertlm-android:0.18.1` + `litert-npu-runtime-google-tensor:2.3.2`, `Backend.NPU(nativeLibraryDir)`.
- Model: `gemma-4-E2B-it_Google_Tensor_G5.litertlm`, copied from Edge Gallery. The SHA-256 `62faebcfd101acb841c33249530430397e031eb17d4dd3d2a71193d135705f27` is verified before each batch.
- The `.so` files have `LOAD p_align = 16384`.

**Conditions:**
- USB connected, battery at 80%.
- `thermalStatus = 1` (LIGHT) at the start and end of each batch.
- Screen off or app in the background for almost the whole batch; the process stayed at `importance = 125` thanks to the FGS.
- Edge Gallery had an active download FGS during the batch of 100.

| Batch | Engine load | ECDH mean (p50 / p95) | HKDF mean | AES-GCM mean | LLM mean (p50 / p95) | Total/seg mean (p50 / p95) | **ECDH % of total** | Valid JSON |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 10 (21:24) | 3457 ms | 91.8 ms (88.9 / 93.3) | 0.40 ms | 0.81 ms | 11,720 ms (10,463 / 16,827) | 11,817 ms (10,559 / 16,913) | **0.777%** (0.754% with load) | 10/10 |
| 100 (21:38–22:12) | 3146 ms | 100.2 ms (99.0 / 128.7) | 0.78 ms | 1.06 ms | 20,204 ms (18,654 / 32,439) | 20,311 ms (18,774 / 32,548) | **0.493%** (0.492% with load) | 95/100 |

**Individual segment:** ECDH ranges from 0.23% to 1.45% of its segment's total (maximum with the shortest segment, 6.3 s).

**Result:** ECDH is less than 1% of the total in both batches, far from the 5% threshold. **One ECDH per segment is kept** and the proposal is rejected. The per-segment cost is dominated by the LLM: more than 99% of the time.

### Observations

1. **ECDH is slower with the LLM active.**
   - It measured 86–119 ms per operation in the batch of 10 (peak of 128 ms on the first segment) and a mean of 100 ms (49.7–161.6 ms) in the batch of 100.
   - In the isolated spike it was 44 ms (40–53 ms).
   - **The cause was not measured.** Candidate hypotheses, unverified, are contention in StrongBox or on the bus, CPU frequency and thermal pressure.
   - Even at 160 ms, ECDH would be < 1% of a 20-s segment.
2. **The LLM slows down within the batch of 100.** Mean per block of 10 segments: 12.8 s → 22.7 → 27.1 → 29.0 → 23.9 → 16.8 → 17.8 → 18.1 → 18.2 → 16.7 s. `thermalStatus` stayed at 1 at the start and end, but was not sampled during the batch. The cause (thermal, competition with the Edge Gallery FGS or other) was not measured.
3. **Invalid JSON in 5 of 100.** These are segments 29, 33, 43, 68 and 83. All gave 264–265 characters and took 31 to 40 s, which suggests the 256 output tokens ran out without closing the JSON. Three of the five correspond to the same template (medical appointment). The outputs were not recorded, so the cause is unconfirmed.
4. **Extraction quality**, seen in segment 0:
   - `titulo` came out as `": "`.
   - The date was invented as `2024-05-15` because the text gives no year.
   - It is one sample; quality was not evaluated and is left for the extraction model benchmark.
5. **OS limitation: an FGS cannot be started from the background.**
   - The second Worker of a `10 → 100` chain started with the app in the background.
   - `setForeground` failed with `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed`.
   - A batch of 100 lasts about 34 min, more than the roughly 10-min window of a regular Worker.
   - Implication for `CognitionWorker`: either batches fit within that window (about 25–50 segments at these times), or they are split across several Workers, or the FGS is started with the app in the foreground. Design pending.
6. **`ExistingWorkPolicy.REPLACE` does not stop a blocking loop.** A run of 100 was contaminated when a second one was launched in the same process: both ran at once and that data was discarded. The real Worker must check `isStopped` between segments.
7. **LiteRT-LM version.** With `0.19.1`, `Backend.NPU` fails to load `libLiteRtDispatch_GoogleTensor.so` from `litert-npu-runtime-google-tensor:2.3.2`: `Could not load symbol LiteRtDispatchQueryInterface: undefined symbol`. 0.19.x expects the new dispatch ABI and the published runtime exports `LiteRtDispatchGetApi`. Version `0.18.1` works.
8. **Limited sample.** One batch of each size, with synthetic texts, on a single device and without thermal control.

### Repeat of the batch of 100 (run 2), 2026-11-07 22:17–22:37

**Changes from run 1:**
- `maxOutputToken` goes from 256 to 512.
- `thermalStatus` and `getThermalHeadroom(10)` are sampled on each segment.
- Invalid outputs are recorded in full.
- `isStopped` is checked between segments.
- Edge Gallery closed with `force-stop` and no downloads (no active service).
- Same prompt, same texts and same model. Start with battery at 35.1 °C, 80%, USB.

| Metric | Run 1 (21:38) | Run 2 (22:17) |
| --- | --- | --- |
| Batch duration | 33.9 min | 19.5 min |
| Engine load | 3146 ms | 4786 ms |
| ECDH mean (p95) | 100.2 ms (128.7) | 91.0 ms (101.2) |
| LLM mean (p50 / p95) | 20,204 ms (18,654 / 32,439) | 11,563 ms (10,262 / 16,048) |
| Total/seg mean (p95) | 20,311 ms (32,548) | 11,658 ms (16,156) |
| **ECDH % of total** | 0.493% | **0.781%** |
| Valid JSON | 95/100 | **95/100** |
| Empty title among the valid ones | not measured | **19/95** |
| `thermalStatus` | 1 at start and end | 0 on 48 segments, 1 on 52; never ≥ 2 |

**Mean total/seg per block of 10 segments (ms):**

| Block | 0–9 | 10–19 | 20–29 | 30–39 | 40–49 | 50–59 | 60–69 | 70–79 | 80–89 | 90–99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Run 1 | 12,789 | 22,705 | 27,134 | 28,968 | 23,925 | 16,844 | 17,761 | 18,062 | 18,241 | 16,681 |
| Run 2 | 12,130 | 10,704 | 11,803 | 12,628 | 12,151 | 9,407 | 12,241 | 11,658 | 12,272 | 11,586 |
| Mean `headroom10s`, run 2 | 0.686 | 0.726 | 0.769 | 0.780 | 0.796 | 0.802 | 0.807 | 0.808 | 0.810 | 0.816 |

**Conclusions:**

1. **The slowdown of run 1 does not reproduce.** With Edge Gallery closed, the time per segment stays stable at 9.4–12.6 s throughout the batch. `thermalStatus` did not exceed 1, although headroom rose from 0.69 to 0.82. The correlation points to the Edge Gallery load (download FGS active in run 1) and not to temperature, but **the cause is not proven**: CPU, I/O and NPU load were not measured.
2. **ECDH stays < 1%.** It is 0.781% with a faster LLM; the maximum per segment was 1.45%. The decision to keep one ECDH per segment does not change.
3. **ECDH with the LLM active:** 83–127 ms (mean 91 ms), versus 44 ms in the isolated spike. It reproduces without Edge Gallery, so Edge Gallery was not the cause. The cause remains unmeasured.
4. **The invalid JSON is not due to the token limit.**
   - **The same 5 segments** fail as in run 1 (29, 33, 43, 68, 83), with identical text. Generation is deterministic.
   - The 5 complete outputs are `{ "titulo":": "` followed by repeated `\n\t` (corrected on 2026-11-08; the log showed newlines as spaces) until the limit runs out: 520–521 characters with 512 tokens, 264 with 256.
   - It is a degenerate whitespace loop under constrained decoding, not a long truncated JSON.
   - Doubling the tokens only doubles the time wasted: 32 s per failed segment, versus a median of 10 s.
5. **The empty title is systematic.** 19 of the 95 valid ones have `titulo` equal to `": "`. The 5 invalid ones start the same way. The model emits `": "` as the value of the first field, which points to the prompt or the schema and not to chance. Not investigated.
6. **Implication for the design:** a retry with the same input and more tokens fixes nothing. §4 of `docs/design-cognition-worker.md` was adjusted accordingly.

## `CognitionWorker` in real background (no FGS), 2026-11-07

**Setup:**
- `--es mode cognition-bg`: Worker of 10 segments **without `setForeground`**, with `requiresCharging` and a delay of 120 s.
- After scheduling it, the activity is closed with BACK and the process is killed with `kill -9`. `force-stop` is not used, because it cancels the scheduled work, nor `am kill`, which does not kill it while WorkManager keeps it at `oom adj` 0.
- The screen is turned off with `KEYCODE_SLEEP`.
- USB connected and charging. Same engine and model as run 2, `maxOutputToken` 512 and same prompt.

### Run A: screen off, no forced idle (22:48–22:59) — valid

| Metric | Value |
| --- | --- |
| Worker start | 22:54:22. Scheduled for 22:50:06; JobScheduler deferred it about 4 min |
| Process state | `importance=230`, `interactive=false`, `deviceIdle=false` at the start, on segments 0 and 9 and at the end |
| Backend | **NPU.** `libLiteRtDispatch_GoogleTensor.so` loaded with no dispatch errors |
| Engine load | 5073 ms |
| Model SHA-256 (3.9 GB) | 22,343 ms, versus 4–10 s in the foreground |
| ECDH mean (p95) | 101.0 ms (105.7) |
| LLM mean (p50 / p95) | **29,183 ms** (29,414 / 32,350) |
| Total/seg mean | 29,287 ms |
| ECDH % of total | 0.345% |
| Valid JSON / empty title | 10/10 / 2 |
| `thermalStatus` | 0 on all 10 segments |
| Batch duration | 298 s, within the window of about 10 min |

**Comparison with the same test with the screen on.** It is a run discarded as a background test: someone touched the screen at 22:45:21 (`WAKE_REASON_TAP`) and the activity was restored. It serves as a reference with the screen on and the app in the foreground (`importance=100`). The outputs are identical, character for character, because generation is deterministic.

| Segment | Characters | Screen on | Screen off | Factor |
| --- | --- | --- | --- | --- |
| 0 | 331 | 9,805 ms | 28,644 ms | ×2.92 |
| 1 | 119 | 5,909 ms | 24,709 ms | ×4.18 |
| 2 | 276 | 9,756 ms | 29,414 ms | ×3.01 |
| 3 | 158 | 6,550 ms | 25,595 ms | ×3.91 |
| 4 | 360 | 10,507 ms | 30,223 ms | ×2.88 |
| 5 | 466 | 12,840 ms | 33,277 ms | ×2.59 |
| 6 | 320 | 11,203 ms | 31,016 ms | ×2.77 |
| 7 | 174 | 7,326 ms | 26,849 ms | ×3.66 |
| 8 | 290 | 9,328 ms | 29,753 ms | ×3.19 |
| 9 | 391 | 11,244 ms | 32,350 ms | ×2.88 |

**Observations:**

1. **The NPU works from a real background Worker.** It works with the app closed, the process started by JobScheduler, no FGS and the screen off. No fallback to GPU or CPU was needed, and there were no errors.
2. **The LLM is about 3 times slower with the screen off.** A linear fit over the shortest (119 characters) and longest (466) segments gives the following:

   | State | Cost per character | Fixed cost per segment |
   | --- | --- | --- |
   | Screen on | ≈ 20 ms | ≈ 3.5 s |
   | Screen off | ≈ 25 ms | ≈ 21.8 s |

   - Almost all of the penalty is in the **fixed part**: creating the conversation, the prefill or compiling the LLGuidance grammar. Decoding only gets 23% more expensive per character.
   - The SHA-256 was also about 3 times slower, which suggests the background process gets less CPU (cpuset or frequency).
   - **The cause was not measured.** Affinity, frequency and NPU usage were not recorded.
3. **Implication for the design (§1):**
   - At about 29 s per segment, 25 segments take about 12 min and do not fit in 8 min. The time budget will limit each run to about 15 segments.
   - The initial moving p95 of 35 s remains reasonable (measured p95: 32.4 s).
   - The design was not changed. If the fixed cost turns out to be the grammar compilation or the system prompt prefill, reusing a preloaded conversation (`Conversation.clone()`) could reduce it. Untested.
4. **JobScheduler deferred the start by about 4 min** with the screen off (scheduled at 22:50:06, started at 22:54:22).

### Run B: forced idle (`dumpsys deviceidle force-idle`) — incomplete

- **23:00:19:** `force-idle` activated: `mForceIdle=true`, `mState=IDLE`, Worker pending the delay.
- **23:00:41:** the phone was lifted (`WAKE_REASON_LIFT`).
- **23:00:50:** the app opened in the foreground.
- **Shortly after:** the phone disconnected from USB. From 23:01:02 there are no logs, and at 23:16 `adb devices` did not see it.
- **No result:** no segment was processed and it is unknown whether the Worker ran with forced idle.
- **Forced idle may still be active on the phone:** `dumpsys deviceidle unforce` could not be run. It must be run on reconnecting and run B repeated.

## Background: forced idle, `cpuset`, single conversation and schema constraints, 2026-11-08

Session from 15:30 to 16:18. Pixel plugged into power (`AC powered`), battery from 34% to 56%. Same model and engine (`litertlm-android:0.18.1`, NPU), `maxOutputToken` 512, prompt unchanged. Breakdown with `ExperimentalFlags.enableBenchmark` (it must be enabled **before** creating the `Engine`; otherwise `getBenchmarkInfo` throws `Benchmark is not enabled`).

**Session start:** `dumpsys deviceidle unforce` confirmed (`mForceIdle=false`, `mState=ACTIVE`). It was confirmed again after run B and at the end of the session.

### Run B: forced idle

| Attempt | What happened | Valid? |
| --- | --- | --- |
| 15:30:43 | 8.4 min in `IDLE` without starting (`DEVICE_NOT_DOZING` unmet). At 15:39:06 the phone was lifted (`WAKE_REASON_LIFT`) and at 15:39:13 the power button was pressed; the activity was restored and rescheduled the Worker. With `deviceIdle=true` and `importance=325`, the Worker started, the NPU loaded in 6346 ms and generated segment 0. It then crashed from an error of mine (order of `enableBenchmark`) | No |
| **15:43:11** | **15 min in `IDLE` without starting.** The only unmet constraint at the end was `DEVICE_NOT_DOZING`; 84 of 84 samples with the process dead | **Yes** |

- **With forced idle, the Worker does not run.** After `unforce` (15:58:36) it started in 6 s (15:58:42).
- A real maintenance window was not measured: with `force-idle` there was none in those 15 min.
- In the first attempt, an already scheduled Worker started in idle after the app opened. That single segment indicates the NPU is not blocked by `deviceIdle=true`, but it is not a clean run.

### Same batch in three states

10 segments, `importance` and `cpuset` measured during the run.

| Metric | Screen on, top-app | Background, control | Background, single conversation |
| --- | --- | --- | --- |
| Time | 16:03 | 15:58–16:03 | 16:07–16:09 |
| `importance` | 100 | 230 | 230 |
| `cpuset` (samples) | `/top-app` (9 of 10) | `/background` (26 of 26) | `/background` (10 of 11) |
| Allowed CPUs | 0–7 | **0–2** | **0–2** |
| Engine load | 3657 ms | 14,007 ms | 6106 ms |
| `createConversation` | **2.68 s** (2.63–2.77) | **14.9 s** (12.2–16.9) | 19.85 s, once |
| Prefill | 124–171 tokens; 141–789 tok/s | 124–171 tokens; 41–603 tok/s | 63–138 tokens; 115–442 tok/s |
| Decode | 17.0–18.1 tok/s | 14.9–16.6 tok/s | 14.2–15.6 tok/s |
| LLM mean | 9.59 s | 23.35 s | 6.11 s |
| Total per segment (p95) | 9.68 s (11.5) | **23.46 s** (26.0) | **6.21 s** (9.1) |
| Full batch with load | 100.7 s | 249.1 s | 88.5 s |
| ECDH mean / % of total | 92.3 ms / 0.95% | 101.6 ms / 0.43% | 98.4 ms / 1.58% |
| Valid JSON | 10/10 | 10/10 | 10/10 |
| Empty title | 2/10 | 2/10 | **10/10** |

**CPU mask of each group** (`/dev/cpuset/*/cpus`): `top-app` 0–7, `foreground` 0–6, `system-background` 0–4, `background` 0–2.

**Frequencies per CPU group**, sampled every 10 s, MHz:

| Run | policy0 (CPU 0–1) | policy2 (2–4) | policy5 (5–6) | policy7 (7) |
| --- | --- | --- | --- | --- |
| Screen on: mean / max | 1051 / 1881 | 801 / 1785 | 736 / 1785 | **1732 / 3782** |
| Background control, with process alive: mean / max | 1124 / 2246 | 916 / 3052 | 848 / 3052 | 813 / 3782 |
| Single conversation: mean / max | 1156 / 2246 | 856 / 3052 | 510 / 3052 | 745 / 3168 |

The sampling is coarse and the frequencies are system-wide, not per process, so they cannot be attributed to the Worker.

**Conclusions:**

1. **The fixed cost of run A is `createConversation`.** In the background it takes 14.9 s on average, versus 2.68 s with the screen on (×5.6). Decoding barely changes (about 15 versus 17.6 tok/s).
2. **The most direct measured cause is the `cpuset`.** In `/background` the process only has CPUs 0–2, versus 0–7 in `/top-app`. That fits with a CPU operation like `createConversation` being the most affected, and with the SHA-256 going from 5 to 23–24 s. **The causal relationship was not tested** by changing only the `cpuset`, and it is unknown what `createConversation` does during those seconds. LLGuidance grammar compilation is an unverified candidate.
3. **The preloaded conversation cannot be measured as proposed.** `Conversation.clone()` does not exist in `litertlm-android:0.18.1` (it does in 0.19.1, which breaks the NPU), and there is no API to pin the prompt prefix either. The only option in 0.18.1 is **a single `Conversation` for the whole batch**, and that is what was measured:
   - It lowers the total to 6.21 s per segment (−74%) and the batch from 249 s to 88.5 s.
   - **It is not usable as is:** history accumulates between segments. Outputs change from segment 1 on (125 characters versus 119) and the title comes out empty on 10 of 10, versus 2 of 10.
   - It serves as a bound on what would be gained by avoiding `createConversation`.
4. **Comparison with run A:** the estimated fixed cost there was 21.8 s (linear fit); today, measured directly, `createConversation` is 14.9 s. The control total is 23.46 s per segment versus 29.29 s, with no breakdown in run A and in a different session.
5. **Measured background cap.** With 8 min per run, run A (29.3 s per segment, 5.1 s of load and 22.3 s of SHA-256) gives about 15 segments. Today's control (23.5 s, 14.0 s and 23.7 s) gives about 18.

**Physical contamination:** the phone was moved or touched during the session despite being reserved.

| Time | Event | Effect |
| --- | --- | --- |
| 15:39:06 and 15:39:13 | `WAKE_REASON_LIFT` and `power_button` | Invalidated the first attempt of B |
| 16:01:54 | `WAKE_REASON_LIFT` | Control: segments 6–9 with `interactive=true` at the end; `createConversation` drops from about 16.5 s to 12–14 s. Without those segments, the first 6 give 15.9 s |
| 16:09:47 | `WAKE_REASON_TAP` | Single conversation: during segment 9 |

**Unexplained:** at 15:43:00, right after reinstalling the APK and before the run, the app started in `all` mode (Keystore and Nano) without me launching it. `am start -S` killed it 3 s later. It does not affect the data and the origin was not found in the buffer.

### Schema constraints with LiteRT-LM 0.18.1 (§6 probe)

`SchemaProbe.kt`, in the foreground with the screen on (16:10–16:18). Texts: the 5 segments that failed (29, 33, 43, 68, 83) and 3 other controls per probe. The probe's automatic verdict marks "NO RESPETA" when there is invalid JSON, even if the tested constraint is met, so the "Verdict" column was reviewed by hand against the outputs.

| Constraint | Automatic probe | Actual verdict | Evidence |
| --- | --- | --- | --- |
| Control: current schema | 0/5 | Reproduces the failure | All 5 segments fall into the whitespace loop |
| `x-guidance.whitespace_flexible=false` | 5/5 | **Respected** | Compact JSON with no whitespace outside strings, 5.7–6.9 s versus 32 s; LiteRT-LM passes `x-guidance` to LLGuidance |
| `minLength=40` on `titulo` | 2/3 | **Respected** | All 3 titles have ≥ 40 characters. The failure of segment 3 is the whitespace loop, unrelated to `minLength`. The model pads with garbage to reach it |
| `maxLength=10` | 3/3 | **Respected** | Truncated titles: `": Cita con"`, `"Estado del"` |
| `pattern ^ZZ` | 3/3 | **Respected** | `"ZZoom reunión de diseño"`, `"ZZA pedido enviado"` |
| `maxItems=1` on `personas` | 3/3 | **Respected** | A single person. In segment 0 it puts two names in one item: `"Laura Méndez, Diego"` |
| `additionalProperties=false` | 2/3 | **Respected** | With the instruction to add `lugar`, it does not appear (segments 0 and 4); the control without the constraint does emit it. The failure of segment 3 is the loop |
| `ResponseFormat.regex` (compact JSON) | 5/5 | **Works** | Available alternative; not needed, because `x-guidance` passes through |
| Full hardened schema | 5/5 | Rules respected, **worse quality** | No loop, but garbage titles and lost fields (below) |

**Correction:** the loop is repeated `\n\t`. Earlier I described it as "tabs and spaces", because the log replaced newlines with spaces.

**The constraints are respected, but they do not solve the title.** Outputs of the hardened schema:

```text
{"titulo":", personas:[{","personas":["Mamá","abuela Carmen","tía Rosa"],"fechas":[],"temas":[],"tareas":[]}
{"titulo":", \"personas\": [\"Dra. Patricia Ruiz\"], \"fechas\": [\"2024-05-17\"], \"temas\": [\"Cita\"", ...
{"titulo":">Resumen de llamada con cliente", ...
```

With `whitespace_flexible=false` alone, the title is not fixed either: `": "`, `": Cita dentista"`, `": Resumen de llamada con cliente"`.

**Observed pattern, not proven:** correct titles appear when the model puts a newline between `:` and the quote (`"titulo":⏎"Plan de escalada"`). Titles with `": "` appear when `"titulo":"` is stuck together. This is compatible with the grammar forcing `"titulo":"` and the model carrying on as if it still had to write `": "`. It fits with compact JSON making it worse. Untested: `x-guidance.whitespace_pattern` with an optional space.

## English prompt and `whitespace_pattern` probe, 2026-11-08

`WhitespaceProbe.kt`, foreground with the screen on (16:31–16:36), NPU, `maxOutputToken` 512, a new conversation per segment. Each variant ran the same 15 segments: the 10 control segments (0–9) and the 5 that looped (29, 33, 43, 68, 83).

English prompt:

```text
Extract structured information from the user's text. Respond only with valid JSON that follows the schema: title (short phrase), people, dates (ISO 8601 if possible), topics and pending tasks. Use empty lists if there is no data. Keep values in the original language of the text.
```

Schema keys: `title`, `people`, `dates`, `topics`, `tasks`. `whitespace_pattern` was set to `\x20`, which LLGuidance documents as matched at most once per whitespace location. The middle variant was added to separate the effect of the language from the effect of the whitespace setting.

| Metric (15 segments) | Spanish + `whitespace_flexible=false` | English + `whitespace_flexible=false` | English + `whitespace_pattern=\x20` |
| --- | --- | --- | --- |
| Loops | 0 | 0 | 0 |
| Valid JSON | 15/15 | 15/15 | 15/15 |
| Empty title (only `:`, quotes or spaces) | 6 | **15** | **15** |
| Title with leading junk and some text | 9 | 0 | 0 |
| Usable title | **0** | **0** | **0** |
| Prompt tokens per segment, mean | 134.3 | 141.3 | 141.3 |
| Output tokens per segment, mean | 66.5 | 64.6 | 69.3 |
| `createConversation`, mean | 2678 ms | 2667 ms | 2665 ms |
| Total per segment, mean / max | 6705 / 10,048 ms | 6458 / 9869 ms | 6734 / 9998 ms |

**Conclusions:**

1. **`whitespace_pattern=\x20` also removes the loop:** 0 loops and 15/15 valid JSON, including the 5 segments that looped.
2. **The title is broken in all 45 outputs.** The hypothesis that allowing a space would fix it is not supported: with the pattern, the model still emits `{"title":": ",...` and uses no space. The title problem remains unexplained.
3. **English does not fix the title and leaves it emptier.** Spanish produced text after the junk in 9 of 15 cases (`": Cita dentista"`); English produced only `": "` in 15 of 15. One run per variant, deterministic generation, one prompt wording per language.
4. **Cost of English:** +7 prompt tokens per segment, with no measurable time difference (within ±4%).
5. **Values keep the source language.** On review of the English + `whitespace_pattern` outputs, `people`, `topics` and `tasks` stayed in Spanish in all 15 (for example `"Cita con la dentista"`, `"encargar la tarta"`). This was a manual review, not an automatic check.
6. **Dates are still invented** when the text gives no year (`2024-05-10`, `2023-11-28`). The approved design adds `captured_at` to the prompt to address this; that is not tested here.
