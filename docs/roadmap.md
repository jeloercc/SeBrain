# SeBrain roadmap

**Status: current plan, set by the user on 2026-11-10.** It replaces the phase order of the original research document where they differ.

## Product

SeBrain is a unifying personal app on the Pixel 10: an Obsidian-style second brain (notes, tags, links between notes) stored encrypted on the device, connected to the user's other apps, with a local uncensored AI that answers concrete questions and acts on the notes. Nothing goes to the cloud. Security comes first.

## Decisions (2026-11-10)

- **Local AI:** an uncensored model running on the device is a core feature. Current choice: Gemma 4 E4B abliterated (`olekk/gemma-4-E4B-it-abliterated-litert-lm`), GPU or CPU, because third-party models cannot use the Tensor NPU. It is a community build, pinned by SHA-256.
- **AI actions:** the AI acts on notes only: create, tag, link, search and summarize. It does not launch other apps and does not run code.
- **Trackers:** SeBrain has no internet permission and no telemetry, and it removes tracking parameters from links that enter it. It does not filter the traffic of other apps.
- **Integration:** share into and out of SeBrain, capture notifications from chosen apps, and import or export Markdown compatible with Obsidian. Exported files are outside the vault and unencrypted; that is the user's explicit choice per export.
- **Cryptography:** widely used modern algorithms now (AES-256-GCM, ECDH P-256 in StrongBox, HKDF-SHA256, HMAC-SHA256). Post-quantum protection is added when data leaves the device or is stored for the long term; see below.

## Order of work

1. **Notes like Obsidian.** Notes written in the app, `#tags`, `[[links]]`, backlinks, tag filter. Captures are notes too.
2. **Local AI chat.** Questions answered from the notes with citations, using the uncensored model, and actions on notes.
3. **Share out and link cleaning.** Send a note to another app; strip tracking parameters on the way in.
4. **Notification capture.** Listener with a per-app allowlist.
5. **Obsidian import and export.**
6. **Hardening.** Encrypted backup, post-quantum layer, privacy checks, threat model review.

Open criteria inherited from the research document, to be measured when their feature is stable: search over 10,000 notes in under 100 ms, and the right chunk in the top 8 for at least 80% of 50 real questions.

## Post-quantum cryptography and `pqc-sdk`

The user's `github.com/jeloercc/pqc-sdk` implements ML-KEM-768 and ML-DSA-65 (FIPS 203/204) in TypeScript, with some Go. A native Android app cannot run it directly, so the integration options are:

- Use it on the desktop side, for example to open encrypted backups or exports made by SeBrain.
- Implement the same wire format in Kotlin on a JVM implementation of ML-KEM, and check both against each other with shared test vectors.

Where it applies: anything that leaves the device or must stay secret for years, that is, encrypted backups and any future sync. The proposal is hybrid encryption, classical ECDH plus ML-KEM, so the result is at least as strong as either. StrongBox does not support ML-KEM, so the post-quantum private key would be software, wrapped by a StrongBox key.

This changes the key hierarchy, so it is proposed and approved before it is implemented.
