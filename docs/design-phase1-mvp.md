# Design: Phase 1 MVP — Share to vault

**Status: Implemented.** Phone test results are in `docs/benchmarks.md`.

## Flow

```text
another app --ACTION_SEND text/plain--> ShareActivity (:ingest)
  normalize -> SHA-256 -> K_dedup tag -> duplicate? stop
  encrypt to the K_inbox public key -> noBackupFilesDir/inbox/<segment_id>.seg
  remember the tag -> noBackupFilesDir/inbox/dedup.idx
        |
        v  (hook set by :app)
InboxImporter (:cognition), only with the device unlocked and the screen on
  decrypt with K_inbox -> insert into capture (vault) -> delete the segment
```

There is no AI in Phase 1. Captures are stored with `extraction_status = 'pending'`; extraction, chunks, FTS5 and vectors come with `CognitionWorker`.

## Modules

| Module | Contents |
| --- | --- |
| `:core:crypto` | `WrapKey`, `WrappedDekStore`, `InboxKey`, `DedupKey`, `InboxSegmentCodec`, `CapturePayload` |
| `:core:vault` | SQLCipher build, `VaultSession`, `VaultSchema`, `CaptureStore` |
| `:ingest` | `ShareActivity`, `CaptureIngestor`, `DedupIndex`, normalization |
| `:cognition` | `InboxImporter` (the future `CognitionWorker` lives here) |
| `:app` | `SeBrainApp` (wiring), `MainActivity` (counts only) |

`InboxSegmentCodec` and `CapturePayload` live in `:core:crypto` because both `:ingest` (writer) and `:cognition` (reader) need them, and `:ingest` must not depend on the vault.

## Keys

| Key | Type | Unlock required | Used for |
| --- | --- | --- | --- |
| `K_wrap` | AES-256-GCM, StrongBox | Yes | Wrapping the vault DEK |
| `K_inbox` Strict (key id 1) | EC P-256 `AGREE_KEY`, StrongBox | Yes | Decrypting segments |
| `K_inbox` Balanced (key id 2), default | EC P-256 `AGREE_KEY`, StrongBox | No | Decrypting segments |
| `K_dedup` | HMAC-SHA256, StrongBox | No | Deduplication tags |

All fall back to the TEE if StrongBox is unavailable. `K_outbox` is designed in `docs/design-vault-session.md` and not implemented.

## Segment format

```text
magic "SBSG" (4) | version (1) | K_inbox key id (1) | segment id (16)
| ephemeral public key, X.509 (91) | nonce (12) | AES-256-GCM ciphertext + tag
AAD = all bytes before the ciphertext
K_seg = HKDF-SHA256(ECDH(ephemeral, K_inbox), salt = 32 zero bytes,
                    info = "sebrain/seg/v1" || key id || segment id, L = 32)
```

The plaintext is JSON: `schema`, `captured_at` (UTC instant), `time_zone`, `source`, `content_sha256`, `subject`, `text`. Nothing about the capture is outside the ciphertext except the segment id, the file size and the file time.

## Deduplication

- The tag is `HMAC-SHA256(K_dedup, SHA-256(normalized text))`. The secure element only sees a 32-byte input.
- `dedup.idx` is a list of 32-byte tags. Without `K_dedup`, a tag cannot confirm that a known text was captured. No plain content hash is on disk.
- `K_dedup` has no unlock requirement, because ingestion runs while locked. An attacker who can run code as the app can therefore test guesses against the index; that is the same position from which Balanced segments can be decrypted.
- The tag is stored after the segment is durable. A crash in between lets one duplicate through; the vault rejects it by `content_sha256`.
- The index is never pruned: 32 bytes per capture.
- The plain SHA-256 exists only inside the encrypted segment and inside the vault.

## Vault schema, version 1

```sql
CREATE TABLE capture(
    id INTEGER PRIMARY KEY,
    segment_id TEXT NOT NULL UNIQUE,
    content_sha256 TEXT NOT NULL UNIQUE,
    captured_at TEXT NOT NULL,
    time_zone TEXT NOT NULL,
    source TEXT NOT NULL,
    subject TEXT,
    text TEXT NOT NULL,
    extraction_status TEXT NOT NULL DEFAULT 'pending',
    extraction_attempts INTEGER NOT NULL DEFAULT 0
);
```

The version is in `PRAGMA user_version`. `segment_id`, `extraction_status` and `extraction_attempts` were approved in `docs/design-cognition-worker.md`; `content_sha256 UNIQUE` is new and backs the deduplication above. A database with a newer version than the app is refused.

## Import

- Runs after a capture is stored and when `MainActivity` resumes, on one background thread.
- It first checks that the vault opens, so nothing is decrypted while the vault is unavailable.
- Each capture is one `INSERT OR IGNORE`; the segment is deleted after it. Repeating an import inserts nothing twice.
- A segment that can never be read (failed authentication, malformed, unknown or missing key) is moved to `inbox/quarantine/`, still encrypted, and the rest continues.
- A locked device, a screen that is off or a missing wrapped DEK stops the import and leaves the inbox untouched.

## Decisions made while implementing

- **No WorkManager yet.** The import is short and runs while the user is present. WorkManager comes with `CognitionWorker`.
- **No Compose yet.** `MainActivity` is a plain view with counts and `FLAG_SECURE`. Compose comes with the real UI.
- **`ShareActivity` logs only the result kind,** never content.
- **Text limit: 1 MiB** after normalization.

## Not done in Phase 1

- No import trigger on unlock: apps cannot receive `ACTION_USER_PRESENT` from a manifest receiver. A capture stored while the vault is unavailable waits for the next share or app open.
- Changing the mode at runtime, and deleting the old `K_inbox` once its segments are drained.
- Notifications, OCR and other sources.
- The outbox for locked-phone cognition.
