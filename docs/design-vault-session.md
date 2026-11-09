# Design: vault session and key lifetime

**Status: Implemented in `:core:crypto` and `:core:vault`.** The default mode and the Share integration are not decided yet.

## Flow

1. **First open:** `VaultSession` generates a random 32-byte DEK, wraps it with `K_wrap` and stores the blob in `noBackupFilesDir`. The blob is written before the database is created.
2. **Later opens:** it unwraps the DEK, encodes it as `x'<hex>'` in a byte array and opens the database with that raw key. The DEK array is zeroed right after encoding.
3. **Close:** the raw key array is zeroed after `SQLiteDatabase.close()`.

`K_wrap` is AES-256-GCM in StrongBox, with a TEE fallback if StrongBox is unavailable, and `setUnlockedDeviceRequired(true)`.

**Wrapped blob:** magic `SBDK` (4 bytes), version (1), IV (12), ciphertext and GCM tag (48). Magic and version are authenticated.

## Session lifetime

| Mode | Closes on |
| --- | --- |
| `STRICT` | Idle timeout, and the screen turning off |
| `BALANCED` | Idle timeout only |

The mode is a required constructor parameter. The default is not decided.

**Idle timeout: 5 minutes proposed** (`DEFAULT_IDLE_TIMEOUT_MS`). It is long enough for gaps between short operations and limits how long the key stays in memory. It is a constructor parameter.

Work in progress is never interrupted. If a close is requested during `withDatabase`, the session closes when the work ends, and new work is refused with `VaultSessionClosingException` until then.

## The key stays in memory while the database is open

Zeroing the key early is not possible:

- `sqlcipher-android` keeps a reference to the key `byte[]` in `SQLiteDatabaseConfiguration.password` and reuses it for every new connection. Zeroing it after opening would break later connections.
- SQLCipher keeps the derived key in native memory for as long as the connection is open.

**This exposure is mitigated by limiting the session lifetime, not by early zeroization.** The controls are the idle timeout, closing on screen off in `STRICT`, and zeroing the Kotlin key array on close. SQLCipher clears its own native key material; this project does not control it.

## Limitations

- **The idle timer does not run while the device is in deep sleep.** The timer uses the main `Handler`, which pauses during suspend, so a session can outlive its timeout by the time the device slept. The next use checks elapsed real time and reopens the session if it has expired. A wake-up alarm would remove this gap at a battery cost; it is not implemented.
- **`STRICT` closes on `ACTION_SCREEN_OFF`.** The keyguard can lock later than the screen turns off, so this is earlier than the actual lock, which is the safe direction.
- **The DEK cannot be unwrapped while the device is locked, in either mode.** `setUnlockedDeviceRequired(true)` applies to `K_wrap`, so `BALANCED` can only keep an already open session while locked and cannot open a new one.
  - **Consequence for `CognitionWorker`:** a Worker running with the phone locked can decrypt inbox segments with `K_inbox`, but cannot open the vault unless a session is already open. This affects the open decision on the inbox processing mode.

## Error handling

A problem with the key never modifies or deletes the database.

| Condition | Error |
| --- | --- |
| Database exists, wrapped blob missing | `WrappedDekMissing`. No new DEK is created |
| Blob malformed or fails authentication | `WrappedDekCorrupted` |
| Blob exists, `K_wrap` missing | `WrapKeyMissing` |
| Device locked | `DeviceLocked` |
| DEK does not open the database | `KeyMismatch` |
