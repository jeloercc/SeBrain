# Encrypted backup proposal

**Status:** encrypted export, snapshot, temporary restore validation and derived-index rebuild implemented; SAF E2E verified on Pixel; active-vault activation remains pending  
**Date:** 2026-11-11

## Goal

Export a vault backup without writing plaintext note content to the backup
location or exposing the vault DEK. The backup must be recoverable only through a
user-confirmed destination and a separately protected recovery secret.

## Proposed container

The file is a versioned binary container:

1. Magic and format version
2. KDF cost and cipher identifiers
3. Random backup salt and nonce
4. Encrypted vault export payload
5. Authenticated manifest with schema version, creation time and
   record counts

The logical vault snapshot is restored into a temporary SQLCipher database for
validation. Plaintext temporary files are not allowed.

The v1 codec is `EncryptedBackupCodec` in `:core:crypto`. It uses
PBKDF2-HMAC-SHA256 with 600,000 iterations to derive an AES-256-GCM key from a
user passphrase. The complete header is authenticated as GCM AAD, including
the KDF cost and manifest fields. It intentionally does not access, replace or
export the vault DEK.

## Key handling

- The current v1 passphrase mode derives the AES key directly from the
  user-confirmed recovery passphrase.
- A future device-bound mode may generate a random 256-bit backup DEK and wrap
  it with Android Keystore `K_backup`; that mode is not implemented.
- Never use the vault DEK as a password or export it directly.
- Zero transient buffers after each encryption step.
- Refuse export while the device is locked or the vault session is closing.

The app now exposes encrypted export through Android SAF. The passphrase is
entered by the user, the snapshot is created and encrypted on the background
executor, and only the encrypted bytes are written to the selected destination.

`VaultBackupSnapshot` in `:core:vault` creates a deterministic logical
snapshot of captures, note metadata, tags and links. Derived chunks, FTS rows
and vectors are intentionally excluded and are rebuilt after restore.
The Integrate screen can now validate a backup and restore it into a uniquely
named temporary SQLCipher vault. The temporary database and wrapped key are
deleted after validation; the active vault is never opened for writing and is
never replaced. Derived text indexes are rebuilt during validation; vector
rebuild occurs when the embedding model is available. An explicit activation
workflow remains pending and requires a separately approved design.

## Restore

Restore is an explicit operation into a new temporary vault. Authenticate and
validate the manifest before replacing anything. Existing vault data is never
overwritten implicitly. The restore must pass schema migration and content-hash
checks before becoming the active vault.

## Open decisions

- Device-bound recovery, user passphrase recovery, or both
- Whether backups may be written to user-selected cloud providers through SAF
- Whether the backup includes models (default: no)
- Whether a backup is full-only or supports encrypted incremental records
