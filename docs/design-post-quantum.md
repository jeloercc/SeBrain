# Post-quantum protection proposal

**Status:** design complete; implementation is gated on provider selection and Pixel benchmarks  
**Date:** 2026-11-11

## Scope

Post-quantum protection applies to long-lived encrypted backups and data sent
through `TailnetGateway`. The current local vault key hierarchy remains
unchanged until a migration is separately approved.

## Hybrid construction

- Keep ECDH P-256 for current compatibility.
- Add ML-KEM-768 encapsulation for each post-quantum-required backup or network
  envelope.
- Derive the content-encryption key with
  `HKDF(0x00 || ECDH_secret || ML-KEM_secret, salt, context)`.
- Use AES-256-GCM for the payload and authenticate the complete binary header as
  AAD.
- Use ML-DSA-65 for backup manifests and trusted model metadata. It does not
  sign note content individually.

The payload is accepted only if both derivations complete and AES-GCM
authenticates successfully. If either component is unavailable,
post-quantum-required data fails closed; ordinary local vault operation
continues with the existing key hierarchy.

## Envelope v1

The binary envelope is `SBPQ` magic, version, algorithm identifiers, key id,
salt, nonce, ephemeral P-256 public key, ML-KEM ciphertext, authenticated
manifest and AES-GCM ciphertext. Algorithm identifiers are explicit integers;
unknown identifiers fail closed. The manifest contains schema version, creation
time, payload type and SHA-256 of the encrypted export stream.

For backups, the ML-KEM private key is never stored beside the backup in
plaintext. The first implementation will use a user recovery passphrase to
protect the device-bound ML-KEM private key, or reject the backup if a
post-quantum recipient key has not been provisioned. For network envelopes,
the recipient ML-KEM public key is provisioned with the configured peer and
identified by a non-secret key id.

## Constraints

- Android Keystore does not currently provide ML-KEM in this project environment.
- A reviewed, pinned Bouncy Castle implementation would be required for ML-KEM.
  The provider must be used explicitly, not installed globally, and private
  material must be wrapped by StrongBox.
- ML-DSA provider and Keystore support must be verified on the target Android
  build rather than assumed from platform documentation.
- The implementation must be benchmarked on the Pixel 10 for key generation,
  encapsulation, decapsulation, signing, verification, memory and thermal impact.
- Tailscale WireGuard remains outside SeBrain's control; the hybrid layer must
  be applied above it for data requiring long-term secrecy.

## Implementation gate

Before adding a provider or changing the backup format:

1. Select and pin the provider version and license.
2. Verify ML-KEM and ML-DSA known-answer vectors on the JVM and Pixel 10.
3. Measure key generation, encapsulation, decapsulation, signing, verification,
   peak memory and thermal impact.
4. Add corrupted-header, wrong-key, downgrade and algorithm-mismatch tests.
5. Review the result before enabling post-quantum-required backups or network
   destinations.

## Approval boundary

Do not change `K_inbox`, `K_wrap`, `K_dedup`, the SQLCipher key or existing
segments. Implement the hybrid layer only for new backup/network envelopes
first, then define a separately reviewed migration if the vault itself needs it.
