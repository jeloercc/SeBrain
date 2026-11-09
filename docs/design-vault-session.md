# Design: vault session and key lifetime

**Status: Session implemented in `:core:crypto` and `:core:vault`.** The outbox is designed but not implemented. The default mode and the Share integration are not decided yet.

## Flow

1. **First open:** `VaultSession` generates a random 32-byte DEK, wraps it with `K_wrap` and stores the blob in `noBackupFilesDir`. The blob is written before the database is created.
2. **Later opens:** it unwraps the DEK, encodes it as `x'<hex>'` in a byte array and opens the database with that raw key. The DEK array is zeroed right after encoding.
3. **Close:** the raw key array is zeroed after `SQLiteDatabase.close()`.

`K_wrap` is AES-256-GCM in StrongBox, with a TEE fallback if StrongBox is unavailable, and `setUnlockedDeviceRequired(true)`.

**Wrapped blob:** magic `SBDK` (4 bytes), version (1), IV (12), ciphertext and GCM tag (48). Magic and version are authenticated.

## Session lifetime

The session closes on the idle timeout and when the screen turns off, in both modes. It is refused while the device is locked (`DeviceLocked`) or the screen is off (`ScreenOff`).

**Why the screen state is the boundary:** apps cannot subscribe to the lock event, and the keyguard can lock some seconds after the screen turns off. A session opened in that gap would never receive a screen-off broadcast and would stay open on a locked device. Refusing to open with the screen off closes that gap. Every use also rechecks both states, which covers a missed broadcast.

**Idle timeout: 5 minutes, approved** (`DEFAULT_IDLE_TIMEOUT_MS`). It is long enough for gaps between short operations and limits how long the key stays in memory. It is a constructor parameter.

Work in progress is never interrupted. If a close is requested during `withDatabase`, the session closes when the work ends, and new work is refused with `VaultSessionClosingException` until then.

## The key stays in memory while the database is open

Zeroing the key early is not possible:

- `sqlcipher-android` keeps a reference to the key `byte[]` in `SQLiteDatabaseConfiguration.password` and reuses it for every new connection. Zeroing it after opening would break later connections.
- SQLCipher keeps the derived key in native memory for as long as the connection is open.

**This exposure is mitigated by limiting the session lifetime, not by early zeroization.** The controls are the idle timeout, closing on screen off, and zeroing the Kotlin key array on close. SQLCipher clears its own native key material; this project does not control it.

## Limitations

- **The idle timer does not run while the device is in deep sleep.** The timer uses the main `Handler`, which pauses during suspend, so a session can outlive its timeout by the time the device slept. The next use checks elapsed real time and reopens the session if it has expired. A wake-up alarm would remove this gap at a battery cost; it is not implemented.
- **The session closes on `ACTION_SCREEN_OFF`.** The keyguard can lock later than the screen turns off, so this is earlier than the actual lock, which is the safe direction.
- **The DEK cannot be unwrapped while the device is locked, in either mode,** because `setUnlockedDeviceRequired(true)` applies to `K_wrap`.

## Error handling

A problem with the key never modifies or deletes the database.

| Condition | Error |
| --- | --- |
| Database exists, wrapped blob missing | `WrappedDekMissing`. No new DEK is created |
| Blob malformed or fails authentication | `WrappedDekCorrupted` |
| Blob exists, `K_wrap` missing | `WrapKeyMissing` |
| Device locked | `DeviceLocked` |
| Screen off | `ScreenOff` |
| DEK does not open the database | `KeyMismatch` |

## Locked-phone processing (decided 2026-11-08, not implemented)

The idle timeout is 5 minutes while unlocked. The vault closes on lock in both modes, and its DEK is never unwrapped while locked. Keeping the session open while charging is rejected, because it exposes the whole vault while locked.

- **`STRICT`:** cognition runs only while unlocked; the vault closes on lock.
- **`BALANCED`:** encrypted outbox. While locked, `CognitionWorker` decrypts inbox segments with `K_inbox`, runs the LLM and embeddings, and writes the results encrypted to `K_outbox`. After unlock, a merge step inserts them into the vault.

### Key properties

| Key | `STRICT` | `BALANCED` |
| --- | --- | --- |
| `K_inbox` (EC P-256, `AGREE_KEY`, StrongBox), one key per mode | Private part `setUnlockedDeviceRequired(true)` | Private part usable while locked |
| `K_outbox` (EC P-256, `AGREE_KEY`, StrongBox) | Not needed | Private part `setUnlockedDeviceRequired(true)`; public part used while locked |
| `K_wrap` | Unlocked only | Unlocked only |

Keystore properties cannot be changed after creation, so there are two `K_inbox` keys, one per mode, and every segment stores a key id. On a mode change, new segments use the new key, and the old key is deleted once no pending segment references it.

**Switching `BALANCED` to `STRICT`:** pending segments under the old key remain decryptable while locked until they are processed and the old key is deleted.

The spike in `docs/benchmarks.md` verifies these key properties on the Pixel.

### Outbox entry format (proposal)

One file per segment, `noBackupFilesDir/outbox/<segment_id>.obx`, written atomically (temporary file, fsync, rename).

```text
magic "SBOX" (4) | format version (1) | K_outbox key id (16) | segment_id (16)
| ephemeral public key, SEC1 uncompressed (65) | nonce (12) | AES-256-GCM ciphertext + tag
AAD = all bytes before the ciphertext
K_entry = HKDF-SHA256(ECDH(ephemeral, K_outbox_pub), info = "sebrain/outbox/v1" || key id || segment_id, L = 32)
```

The plaintext is a 4-byte JSON length, UTF-8 JSON, then `chunk count × 256` little-endian `float32` values, padded to a multiple of 4 KiB to reduce size leakage.

```json
{"schema":1,"segment_id":"…","captured_at":"…","source":"…","text":"…",
 "extractor_model":"…","embedding_model":"…","embedding_dims":256,
 "extraction":{"status":"ok","attempts":1,"failure_code":null,
   "people":[],"dates":[],"topics":[],"tasks":[],"title":"…"},
 "chunks":[{"index":0,"start":0,"end":120}]}
```

No new vault tables are needed: the merge reuses the approved `capture.segment_id UNIQUE`, `extraction_status` and `extraction_attempts`.

### Merge step

Runs only while unlocked, triggered by an unlock seen by the always-on ingestion process and when the app opens. For each entry: ECDH with `K_outbox`, decrypt, then insert in one transaction; delete the entry only after `COMMIT`. It checks the lock state between entries.

### Failure cases

| Case | Behavior |
| --- | --- |
| Death after the outbox write, before deleting the segment | Reprocessing finds the existing entry and only deletes the segment |
| Death after `COMMIT`, before deleting the entry | `segment_id` already exists: skip the insert and delete the entry |
| Writing an entry | Re-read and decrypt with `K_entry` before deleting the segment |
| Corrupted entry at merge | Move to a quarantine directory, record it and continue. The source segment is gone, so that capture is lost |
| Device locks during merge | Stop at the next entry; each entry is its own transaction |
| `K_outbox` missing | Stop the merge and keep the entries; the vault is unaffected |
| Unknown schema or embedding model or dimensions | Keep the entry and report it; do not insert |
| Outbox too large (proposal: 500 entries or 200 MB) | Stop cognition; segments stay encrypted in the inbox |

### What is exposed while locked (code running in the app after first unlock)

| Asset | `STRICT` | `BALANCED` |
| --- | --- | --- |
| Vault and DEK | Not reachable | Not reachable |
| Pending inbox segments | Not decryptable | **Decryptable** with `K_inbox` |
| Segment being processed | None | **Plaintext in RAM** |
| Outbox entries already written | None | Not decryptable |
| File count, sizes, timestamps | Inbox | Inbox and outbox |

Before first unlock, app storage is not available, so the Worker cannot run.

**`CognitionWorker` note:** in `STRICT`, `requiresDeviceIdle` must not be used, because the device is rarely idle while unlocked.
