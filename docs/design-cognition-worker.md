# Design: `CognitionWorker` in chunked batches

**Status: Approved (not implemented), 2026-11-07.** None of this is in the app yet. Approved: the schema (`capture.segment_id UNIQUE`, `extraction_status`, `extraction_attempts`), `captured_at` inside the encrypted text, the 8-min budget and the §4 adjustment. The key hierarchy does not change: one ECDH with `K_inbox` per segment.

**Empirical basis:** `docs/benchmarks.md`, section "`CognitionWorker`: ECDH versus total time per segment". Pixel 10, Gemma-4-E2B on NPU, LLM between 6 and 40 s per segment.

## 1. Batches of 25 segments without FGS

**Why no FGS:** Android does not allow starting an FGS from the background. The second Worker of a chain failed with `ForegroundServiceStartNotAllowedException`. `CognitionWorker` runs with the phone idle, so it cannot count on an FGS.

**Constraint:** a regular Worker has an execution window of about 10 min. When it runs out, the system stops it with `STOP_REASON_TIMEOUT`.

**Design:**

- Each run processes at most **25 segments** and also has a **time budget of 8 min**. It ends with whichever runs out first.
  - With the mean of the batch of 10 (11.8 s per segment), 25 segments take about 5 min.
  - With the mean of the batch of 100 (20.3 s), 25 segments take about 8.5 min.
  - With the p95 (32.5 s), 25 segments take about 13.5 min and do not fit.
  - **Measured cap in real background: about 15 segments per run.** With the 8-min budget, run A gave 29.3 s per segment, plus 5.1 s of load and 22.3 s of SHA-256, that is, about 15. The 2026-11-08 control gave about 18 (23.5 s per segment, 14.0 s and 23.7 s). The limit of 25 is only reached with the screen on.
  - **Cause:** in the background the process stays in `cpuset=/background` (CPUs 0–2) and `createConversation` takes 14.9 s versus 2.68 s with the screen on.
  - That is why the time budget is mandatory and counting segments is not enough.
- Before starting a segment, check:
  - `elapsed + estimated_p95 < 8 min`, where `estimated_p95` is the moving p95 of the latest segments (initial value 35 s).
  - If it does not fit, save the checkpoint and return `Result.success()`.
- **Doze:** with forced idle the Worker did not start in 15 min (`DEVICE_NOT_DOZING`) and started 6 s after `unforce`. The inbox must tolerate long waits.
- **WorkManager constraints:** charging, battery not low and `requiresDeviceIdle` for large batches, as CLAUDE.md says.
- **Chaining:** if segments remain in the inbox at the end, the Worker enqueues the next one with `enqueueUniqueWork("cognition", KEEP, …)`. `KEEP` prevents two Workers from running at once.
  - A unique Worker does not prevent a blocking loop from continuing after a `REPLACE`; that already happened in the spike.
  - That is why `REPLACE` is never used and `isStopped` is checked (section 2).
- **Engine:** loaded once per run (3–5.7 s measured) and released in `finally`. If loading costs 5 s and the batch lasts 5–8 min, loading is around 1–2% of the batch.

## 1b. Explicit NPU → GPU → CPU backend chain (approved)

`Backend.NPU` does not fall back to another backend on its own. If the NPU fails, `Engine.initialize()` throws an exception, as happened with 0.19.1 and `undefined symbol: LiteRtDispatchQueryInterface`. The final app implements the chain itself:

```text
for backend in [NPU(nativeLibraryDir), GPU(), CPU()]:
    try Engine(EngineConfig(model_for_backend, backend, cacheDir)).initialize()
    success → use; record backend and load time
    failure → record error class and message; try the next one
none → Result.retry() and record "no backend"
```

**Requirements:**

- **Model per backend.** The `.litertlm` for the TPU (`*_Google_Tensor_G5.litertlm`) only works for the NPU. GPU and CPU need the generic model (`gemma-4-E2B-it.litertlm`). Each is imported via SAF or `adb push` with its own pinned SHA-256.
  - If only the NPU model is imported, the chain reduces to NPU, and the failure is recorded instead of being masked.
- **Per-batch record, no content:** backend used, reason for discarding each previous backend (exception class and message), load time, and mean and p95 per segment.
- **Backend cache.** An NPU failure is not retried on every batch: "NPU failed with version X of the app and LiteRT-LM" is stored and retried when the version changes or after 24 h.
- **Mid-batch error:** `LiteRtLmJniException` during generation follows §4. The same backend is restarted once and, if it fails again, it moves down to the next one in the chain on the next run, never mid-batch.

## 2. Per-segment checkpoint and `isStopped`

**Unit of work:** one segment. Its result is committed in its own transaction:

```text
per segment:
  1. ECDH(K_inbox, E_seg) → HKDF → K_seg → decrypt           (≈ 0.1 s)
  2. chunking + LLM extraction (with 1 retry, §4)            (≈ 6–40 s)
  3. chunk embeddings
  4. BEGIN; INSERT capture, chunk, chunk_fts, chunk_vec; COMMIT
  5. delete the segment file (only after COMMIT)
  6. zero K_seg, Z and the plaintext
```

**The checkpoint is the inbox itself.** A segment whose `COMMIT` completed no longer exists in the inbox, and one that did not reach `COMMIT` is still there. On resuming, the Worker takes the remaining segments in capture order. No separate state file is needed, and any interruption leaves the vault in a consistent state.

**Idempotency** if the process dies between steps 4 and 5:

- Proposed: add a `segment_id` column to `capture` (segment UUID, stored in its authenticated header) with a `UNIQUE` constraint. Requires approval because it changes the schema.
- On resuming, if `segment_id` already exists, extraction is skipped and the file is deleted.

**`isStopped`:**

- Checked before each segment and between steps 2 and 3. Step 2 is the longest.
- If `isStopped` is true:
  - The generation in progress, if any, is cancelled with `Conversation.cancelProcess()`.
  - The half-done segment is not `COMMIT`ted. At most the LLM work of one segment is lost.
  - `stopReason` is recorded (for example `STOP_REASON_TIMEOUT`, `CONSTRAINT_CHARGING` or `DEVICE_STATE`).
  - The engine is released and the Worker exits.
- There is no partial checkpoint within a segment. Saving half-done LLM output would mean writing derived data to disk outside the vault.

## 3. Prompt with the capture date

- Ingestion stores `captured_at` (UTC instant plus device time zone) **inside the encrypted plaintext** of the segment, not in the header, so as not to expose metadata on disk.
- The extraction prompt receives it as a reference:

```text
Capture date and time: 2026-11-07T21:24:00-06:00 (Saturday).
Resolve relative dates ("tomorrow", "on Thursday", "the 14th") against that date.
If the text does not give the year, use the capture date's year or the next one if the date has already passed.
If it cannot be resolved, leave the date as literal text instead of inventing it.
```

- **Reason:** in the spike, segment 0 («el jueves 14») came out as `2024-05-15T10:30:00`, an invented date.

## 4. Schema validation and retry

**A result is valid if all of this holds:**

- Parseable JSON.
- All `required` keys are present.
- Each list is a list of strings.
- `title` is not empty after stripping spaces, quotes and `:`. In the spike it came out as `": "`.

**Flow:**

```text
attempt 1 → valid → continue
          → invalid → attempt 2 (single retry)
                      → valid → continue
                      → invalid → mark "extraction failed"
```

**The retry cannot repeat the same configuration.** Run 2 in `docs/benchmarks.md` confirms it:

- Generation is deterministic: the same 5 segments failed with 256 and with 512 tokens.
- The failure is a `\n\t` loop after `{ "titulo":": "`, not a truncation.
- Doubling `maxOutputToken` does not help.

The retry uses:

- `RepetitionPenaltyConfig` and/or `NoRepeatNgramConfig`. Both classes exist in `litertlm-android:0.18.1`; the values have to be measured.
- The additional message: "The previous response was not valid JSON or had an empty title. Return only the JSON object, with a title of 3 to 8 words".
- The same `maxOutputToken` (512) and `ThinkingConfig(false)`.

**Early cutoff.** During streaming (`sendMessageAsync`), if more than 32 consecutive whitespace characters appear outside a string, it is cancelled with `cancelProcess()` and goes straight to the retry. Each loop costs about 32 s, versus a median of 10 s per segment.

**Empty title.** It affected 19 of the 95 valid ones, always with the value `": "`, which points to the prompt or the schema. Before relying on the retry, test:

- putting `titulo` in another position of the schema;
- adding `minLength: 3` to the schema, if `ResponseFormat.json` respects it (unverified);
- including an example in the system prompt.

Left for the extraction model benchmark.

**"Extraction failed" does not block the batch:**

- The segment is decrypted, chunked and its chunks are still inserted with embeddings and FTS5, but without extracted fields. That way it can be found by search even if extraction failed.
- `capture.extraction_status` takes one of these values: `ok`, `ok_retry` or `failed`. It is a schema proposal.
- `extraction_attempts` and the failure code (`json_invalid`, `title_empty`, `truncated`, `engine_error`) are stored, never the plaintext output outside the vault.
- The segment is deleted from the inbox after `COMMIT`, like any other.
- A future setting would allow retrying the `failed` ones with another model. Out of scope.
- An engine error (`LiteRtLmJniException`) does not count as an extraction failure:
  - The segment in progress is discarded without `COMMIT`.
  - The engine is restarted once.
  - If it fails again, the Worker ends with `Result.retry()`.

## 5. Metrics the real Worker must record

Per run, in memory and without writing content data:

- Number of segments and the reason for ending: budget, limit of 25, `isStopped` or empty inbox.
- LLM mean and p95.
- Number of `ok`, `ok_retry` and `failed`.
- Maximum `thermalStatus`.

They are used to tune the 8-min budget and the limit of 25.

## Approved on 2026-11-07

1. Columns `capture.segment_id` (`UNIQUE`), `capture.extraction_status` and `capture.extraction_attempts`.
2. `captured_at` inside the encrypted plaintext of the segment (segment format v1).
3. Budget of 8 min per run and initial moving p95 of 35 s.
4. §4: repetition penalty, cutoff at more than 32 consecutive whitespace characters and retry with a corrective message.

## 6. Empty title: schema-constrained decoding (decided on 2026-11-08)

**Verification result** (`docs/benchmarks.md`): LiteRT-LM 0.18.1 respects `x-guidance.whitespace_flexible=false`, `minLength`, `maxLength`, `pattern`, `maxItems` and `additionalProperties:false`, and `ResponseFormat.regex` works.

- `whitespace_flexible=false` eliminates the `\n\t` loop (5/5, from 32 s to about 6 s).
- **The full hardened schema does not fix the title and worsens the content** (titles like `", personas:[{"`, empty fields). It is not adopted as is until a decision is made.
- `x-guidance.whitespace_pattern=\x20` also eliminates the loop (15/15 valid JSON, 0 loops), but **does not fix the title**: it is broken in 15 of 15, with the English prompt as well (`docs/benchmarks.md`). The title was fixed by moving it (see below).
- **Moving `title` to the last property fixes the title:** 14 of 15 useful, 0 loops, every output matching the schema. The remaining empty title is handled by the §4 retry.
- Without constrained decoding, `createConversation` drops from 2660 ms to 3 ms in the foreground, but no output is directly parseable and none uses the schema keys. The background saving was not measured.

### What the spike used until 2026-11-08

`CognitionBenchWorker.kt`, with no JSON example or few-shot. Replaced by the English prompt and keys (`title`, `people`, `dates`, `topics`, `tasks`), recorded in `docs/benchmarks.md`. The Spanish text below is kept as the measured baseline.

System prompt:

```text
Extrae información estructurada del texto del usuario. Responde solo con JSON válido que siga el esquema: titulo (frase corta), personas, fechas (ISO 8601 si es posible), temas y tareas pendientes. Listas vacías si no hay datos.
```

Schema, passed as `ResponseFormat.json(SCHEMA)` with `enableResponseFormat = true`:

```json
{"type":"object","properties":{
  "titulo":{"type":"string"},
  "personas":{"type":"array","items":{"type":"string"}},
  "fechas":{"type":"array","items":{"type":"string"}},
  "temas":{"type":"array","items":{"type":"string"}},
  "tareas":{"type":"array","items":{"type":"string"}}},
 "required":["titulo","personas","fechas","temas","tareas"]}
```

The user message is just the segment text.

### What LiteRT-LM 0.18.1 offers (verified on 2026-11-07)

- The v0.18.1 C++ documentation (`docs/api/cpp/constrained-decoding.md`) describes constrained decoding with LLGuidance through regex, JSON Schema and Lark grammars.
- In Kotlin, `classes.jar` of `litertlm-android:0.18.1` exposes `ResponseFormat.json(schema)` and `ResponseFormat.regex(pattern)`, with `ResponseFormat.Type = {REGEX, JSON_OBJECT}`. Lark is not exposed.
- The v0.18.1 Kotlin guide does not document `ResponseFormat`. The API exists in the binary and was added by PR #2859.
- **The spike already uses it.** That is why the invalid outputs are prefixes of valid JSON: `{ "titulo":": "` plus whitespace.
  - The `\n\t` loop is whitespace that the JSON grammar allows between tokens.
  - `": "` satisfies `{"type":"string"}`.
  - The current schema is too lax.
- LLGuidance documents these options in its `json_schema.md`:
  - `x-guidance.whitespace_flexible` (`true` by default; `false` forces compact JSON);
  - `x-guidance.whitespace_pattern` (for example `[\x20\x0A]{0,2}`).
- **It is not verified that LiteRT-LM passes `x-guidance` to LLGuidance.**

### Minimal schema (decided)

The five original fields in English, with `whitespace_flexible=false` and `title` last. The prompt's field list uses the same order.

```json
{"type":"object",
 "x-guidance":{"whitespace_flexible":false},
 "properties":{
  "people":{"type":"array","items":{"type":"string"}},
  "dates":{"type":"array","items":{"type":"string"}},
  "topics":{"type":"array","items":{"type":"string"}},
  "tasks":{"type":"array","items":{"type":"string"}},
  "title":{"type":"string"}},
 "required":["people","dates","topics","tasks","title"]}
```

### Discarded: fully hardened schema

The schema with `minLength`, `maxLength`, `pattern`, `maxItems` and `additionalProperties:false` is discarded. LiteRT-LM respects those constraints, but with the title first the model satisfied them with garbage: titles such as `", personas:[{"` and lost fields in 3 of 5 segments. The constraints did not address the cause, which was the position of the title. Full results are in `docs/benchmarks.md`.
