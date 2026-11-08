# Proposal: per-time-window ephemeral key + per-segment HKDF

**Status: Rejected (2026-11-07).** One ECDH per segment is kept. Nothing is implemented and CLAUDE.md does not change.

**Reason:** the criterion was to adopt it only if ECDH exceeds 5% of the total time per segment. In `CognitionWorker` measured on the Pixel 10 (details in `docs/benchmarks.md`), ECDH stayed far below that:

| Batch | ECDH mean | Total/seg mean | ECDH % of total |
| --- | --- | --- | --- |
| 10 | 91.8 ms | 11,817 ms | 0.777% |
| 100 | 100.2 ms | 20,311 ms | 0.493% |

The maximum for an individual segment was 1.45%. The LLM takes more than 99% of the time. The proposal would save at most that < 1%, and in exchange would worsen forward secrecy within the window (risk 1).

**Reopen only if** the cost split changes, for example if many segments stop going through the LLM or the LLM drops below 2 s per segment.

The rest of the document is kept as a record of the analysis.

## Problem

Today each segment carries its own ephemeral key, and decrypting it requires an ECDH with `K_inbox` in StrongBox.

| Operation | Measured on Pixel 10 (2026-11-07) |
| --- | --- |
| ECDH with private key in StrongBox | ≈ 44 ms (range 40–53 ms) |
| ECDH and keygen in software | < 1 ms |

Done serially, 1000 segments spend about 44 s of StrongBox time (estimate from the measurement).

## Design

`K_inbox` and `K_wrap` do not change. Only how `K_seg` is obtained changes.

**Ingestion, when window `w` opens:**

1. Generate the ephemeral pair in software `(e_w, E_w)`.
2. `Z_w = ECDH(e_w, K_inbox_pub)`.
3. `PRK_w = HKDF-Extract(salt = window_id, IKM = Z_w)`.
4. Zero `e_w` and `Z_w`. Store `E_w` in the header of each segment of the window.

**Ingestion, for each segment `i`:**

```text
K_seg_i = HKDF-Expand(PRK_w, info = "sebrain/seg/v1" || window_id || i, L = 32)
header  = version || window_id || E_w || i
ct      = AES-256-GCM(K_seg_i, random 96-bit nonce, plaintext, aad = header)
```

**Cognition, per batch:**

1. Group segments by `window_id`.
2. Do one StrongBox ECDH per window to obtain `Z_w` and derive `PRK_w`.
3. Derive each `K_seg_i` in software.
4. Zero `PRK_w` when the window is finished.

**The window closes** on whichever comes first: maximum time (initial proposal: 15 min), maximum number of segments, screen lock or process death.

**Cost:** 1 StrongBox ECDH per window instead of 1 per segment.

## Risks

| # | Risk | Severity | Mitigation | Residual risk |
| --- | --- | --- | --- | --- |
| 1 | **Forward secrecy within the window.** `PRK_w` lives in the RAM of an always-on process, even with the phone locked. Whoever reads that memory can decrypt the segments already written in the window; today they would only obtain the current segment. | **High** | Ratchet: `PRK_(i+1) = HKDF(PRK_i, "ratchet")`, erasing the previous one after each segment. | Future segments of the window remain exposed. Closing on lock limits exposure, but ingestion with the phone locked opens new windows anyway. |
| 2 | **Blast radius in cognition.** Leaking one `Z_w` exposes the whole window, not one segment. | Medium | Short windows and a maximum number of segments. | Inherent to the design. |
| 3 | **Key and nonce reuse.** Repeating `i` after a restart or an error would reuse `K_seg_i`. With a random nonce the failure is less severe, but the unique-key guarantee is lost. | High if it occurs | Window state lives only in RAM and is never persisted. When the process restarts a new window opens. | Low. |
| 4 | **Memory erasure not guaranteed.** On the JVM, `fill(0)` does not reach copies made by the GC or those left inside libraries. | Medium | Keep the state in a single `ByteArray` or in native code with `mlock` and explicit erasure. | Also affects the current design, but here the key lives for minutes instead of milliseconds. |
| 5 | **Larger implementation surface.** HKDF, ratchet and window management are custom cryptographic code that has to be tested. | Medium | RFC 5869 test vectors, and tests for lost and out-of-order segments. | Low. |
| 6 | **Ratchet and lost segments.** The receiver must be able to advance the chain without all the segments. | Low | Advance to `i` from the last known state. The cost is negligible. | Low. |

Risk 1 is the main one: it worsens a real security property of the current design in exchange for saving time.

## Why not adopt it yet

The per-segment cost of cognition is dominated by the LLM, not by ECDH. With the Edge Gallery data (Gemma-4-E2B TPU, JSON):

| Step | Approximate time |
| --- | --- |
| First token | 1206–1396 ms |
| Generating 100 tokens at 19 tok/s (sample output) | ≈ 5.2 s |
| ECDH with StrongBox | ≈ 0.044 s |

ECDH is less than 1% of a segment that goes through the LLM, and this proposal would save at most that percentage. This is an estimate: there is no `CognitionWorker` benchmark yet and the output size is assumed.

The proposal only pays off if there are many segments that do not go through the LLM (for example, discarded by deduplication before extraction). Today that does not happen, because deduplication is done before encrypting, at ingestion.

## Alternatives

| Option | ECDH cost | Security effect | Assessment |
| --- | --- | --- | --- |
| **A. Keep the current design** | 44 ms per segment | No change | **Recommended for now** |
| **B. Group several captures into one segment** | 44 ms per group | One exposure window per group; keeps data in RAM until it closes | Only if A proves insufficient |
| **C. This proposal with ratchet** | 44 ms per window | Risks 1 to 6 | If the measurement justifies it |
| **D. This proposal without ratchet** | 44 ms per window | No forward secrecy within the window | Not recommended |

## Decision

1. `CognitionWorker` was measured with the 5% threshold as the criterion: ECDH came in at 0.49–0.78%. **Rejected.**
2. The questions about the 15-min window and closing on lock no longer apply.
