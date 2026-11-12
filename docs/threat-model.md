# SeBrain threat model and hardening audit

**Status:** initial audit complete; encrypted export, temporary restore validation and post-quantum envelope design complete; PQC provider implementation and backup activation remain pending  
**Date:** 2026-11-11

## Protected assets

- Note text, titles, tags, links and embeddings
- Notification captures and integration provenance
- Vault DEK and StrongBox-backed key material
- Local model files and their trust hashes
- Mini-brain definitions and integration allowlists

## Main threats

| Threat | Existing control | Remaining work |
| --- | --- | --- |
| Lost or stolen unlocked phone | SQLCipher, wrapped DEK, lock/session close, `FLAG_SECURE` in release | Verify all release screens and timeout paths |
| Malicious or over-broad integration | Deny-all notification policy, exact package names, encrypted policy, user pause path | Add provider-specific permission review |
| Plaintext capture on disk | Encrypted inbox segments and encrypted mini-brain/policy files | Audit every new integration payload |
| Network exfiltration | Single `TailnetGateway`, HTTPS-only, no plaintext note upload | Add network privacy tests |
| Untrusted model file | SHA-256 verification and user confirmation | Pin final release hashes |
| Backup or extraction leak | `allowBackup=false`, excluded extraction rules, passphrase-encrypted backup codec, temporary-vault restore validation | Separately review active-vault activation |
| Dependency telemetry | No telemetry matches in the app runtime dependency audit | Re-run after every dependency change; ML Kit exception remains unresolved |

## Verified checks on 2026-11-11

- `core/vault/scripts/check-static.sh`: **PASS**
- `scripts/check-release-privacy.sh`: **PASS** against the unsigned release APK.
- App runtime dependency search found no Firebase, Crashlytics, analytics, Sentry,
  Bugsnag or telemetry dependency.
- Release manifest controls remain present: disabled backup, excluded extraction
  rules and cleartext traffic disabled.
- Notification integration was tested with Termux; captures entered encrypted
  inbox segments and were imported with source provenance.
- Saved mini-brain definitions were created and loaded on the Pixel; only names
  and source IDs are stored.

## Explicitly rejected designs

- VPN interception of other apps' traffic
- TLS certificate interception
- Root-dependent access
- Accessibility scraping
- Silent unrestricted notification capture

These approaches either violate Android security boundaries, expose more data than
the feature requires, or conflict with SeBrain's single-network-exit rule.

## Remaining hardening phases

1. Keep release artifact checks for permissions, backup rules, cleartext policy
   and `FLAG_SECURE` in the build verification path.
2. Separately review active-vault activation; the verified SAF flow only restores
   into a temporary vault and never replaces the active vault.
3. Select, benchmark and implement the hybrid post-quantum provider for backups
   and network payloads; do not alter the current key hierarchy without an
   approved migration.
4. Run scale measurements: 10,000-note search under 100 ms and top-8 retrieval
   above 80% on 50 real questions.
