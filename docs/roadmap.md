# SeBrain roadmap

**Status: current plan, set by the user on 2026-11-10.** It replaces the phase order of the original research document where they differ.

## Product

SeBrain is a unifying personal app on the Pixel 10: an Obsidian-style second brain (notes, tags, links between notes) stored encrypted on the device, connected to the user's other apps, with a local uncensored AI that answers concrete questions and acts on the notes. Nothing goes to the cloud. Security comes first.

## Decisions (2026-11-10)

- **Local AI:** an uncensored model running on the device is a core feature. Current choice: Gemma 4 E4B abliterated (`olekk/gemma-4-E4B-it-abliterated-litert-lm`), GPU or CPU, because third-party models cannot use the Tensor NPU. It is a community build, pinned by SHA-256.
- **AI actions:** the AI acts on notes only: create, tag, link, search and summarize. It does not launch other apps and does not run code.
- **Network:** SeBrain may use the internet, but only through the user's Tailscale tunnel. `TailnetGateway` is the single exit: it binds connections to the Tailscale VPN network, allows HTTPS only and has no fallback to Wi-Fi or mobile data. Tailnet devices are always reachable; public sites need a Tailscale exit node. Note content never leaves in plaintext; that still holds.
- **Models from Hugging Face:** the app searches and downloads LiteRT-LM models through the tunnel, checks each file against the SHA-256 Hugging Face publishes, and the user confirms each model before it is trusted.
- **Trackers:** SeBrain has no telemetry and no third-party network code, and it removes tracking parameters from links that enter it. It does not filter the traffic of other apps.
- **Integration:** share into and out of SeBrain, capture notifications from chosen apps, and import or export Markdown compatible with Obsidian. Exported files are outside the vault and unencrypted; that is the user's explicit choice per export.
- **Production:** the gateway is the one place to change if a release must support users without Tailscale.
- **Cryptography:** widely used modern algorithms now (AES-256-GCM, ECDH P-256 in StrongBox, HKDF-SHA256, HMAC-SHA256). Post-quantum protection is added when data leaves the device or is stored for the long term; see below.

## Order of work

1. **Notes like Obsidian.** Notes written in the app, `#tags`, `[[links]]`, backlinks, tag filter. Captures are notes too.
2. **Local AI chat.** Questions answered from the notes with citations, using the uncensored model, and actions on notes.
3. **Share out and link cleaning.** Send a note to another app; strip tracking parameters on the way in.
4. **Notification capture.** Listener with a per-app allowlist.
5. **Obsidian import and export.**
6. **Hardening.** Encrypted backup, post-quantum layer, privacy checks, threat model review.

Open criteria inherited from the research document, to be measured when their feature is stable: search over 10,000 notes in under 100 ms, and the right chunk in the top 8 for at least 80% of 50 real questions.

## Post-quantum cryptography

Decided 2026-11-10: SeBrain uses native post-quantum cryptography instead of the user's TypeScript `pqc-sdk`, which a Kotlin app cannot run.

- **Signatures:** ML-DSA-65 or ML-DSA-87 from Android Keystore, which supports them natively since Android 17.
- **Encryption:** hybrid, the current ECDH plus ML-KEM, so the result is at least as strong as either. Android Keystore has no ML-KEM yet, so it would come from Bouncy Castle, with the private key wrapped by a StrongBox key.
- **Where:** encrypted backups and any data sent over the network. Tailscale's WireGuard is not post-quantum, so data that must stay secret for years gets this layer on top.

This changes the key hierarchy, so it is proposed and approved before it is implemented.
