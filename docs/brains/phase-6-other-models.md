# Phase 6 — Other models

**Status: not started, and blocked on decision 3.** Size M. No schema change (the `egress`
column is added in Phase 3).

## This phase changes a hard rule

`CLAUDE.md` says: "Note content never leaves the device in plaintext." Sending a prompt to a
model on another machine sends the text of the selected notes there. HTTPS protects it on the
way; the machine that runs the model reads it. This phase therefore starts only after the
user chooses, in words, one of:

| Option | What leaves the phone | Who can read it |
| --- | --- | --- |
| (a) Device only | Nothing | Nobody else. This phase is not built |
| (b) Own machine over Tailscale | The prompt, to a computer the user owns | The user's machine |
| (c) External API, per brain | The prompt, to a provider | The provider, under its terms |

Recommended: (b) first; (c) later, per brain, off by default. If (b) or (c) is chosen,
`CLAUDE.md` and `docs/threat-model.md` are changed in the same commit as the first code.

## Goal

A brain can use a bigger model than the phone can run, with the user always seeing what is
sent and able to keep any brain on the device.

## What the user sees

- In Models: **Add a server**. An address on the tailnet (for example
  `https://atlas.tailnet-name.ts.net:11434`), a model name, a test button.
- In a brain's settings: **Model** (a local model or a server) and **Data may go to**:
  "This phone only", "My machines over Tailscale", and, if enabled in settings, "External
  API".
- In Ask, with a remote model: a line under the model name, "Sends to atlas over Tailscale".
  The first message of each conversation shows a sheet: which notes and how many characters
  will be sent, with Send and Cancel. A setting can reduce this to the line only.
- If Tailscale is off or the server does not answer: the message is not sent; the user is
  offered the local model for that message.

## Design

- **One interface, `ChatBackend`,** in `:core:ml`:
  `reply(system, history, prompt, onText): String`, `cancel()`, `limits` (context tokens,
  whether it streams). `ChatEngine` (LiteRT-LM) becomes the first implementation.
- **`RemoteChat` in `:core:net`** speaks the OpenAI-compatible chat API
  (`/v1/chat/completions`, streamed), which Ollama, llama.cpp's server and LM Studio expose.
  It opens connections only through `TailnetGateway`.
- **`TailnetGateway(requireTailscale = true)`** already exists for exactly this: a
  connection to the user's machine fails closed when Tailscale is off. An address that is
  not in the tailnet range is refused for egress `tailnet`.
- **HTTPS only**, as today. Tailscale's HTTPS certificates (`tailscale cert`, served by
  `tailscale serve`) give a valid certificate for a `*.ts.net` name; plain HTTP to a tailnet
  IP is not allowed. To verify against the current Tailscale documentation before coding.
- **The egress check is in one place,** `EgressPolicy`: given the brain's `egress` and the
  backend, it allows or refuses. Every send calls it; it is unit-tested as a table. A brain
  set to `device` cannot use a remote backend even if one is selected by mistake.
- **The context scales with the model.** `Assistant.INPUT_CHARS` becomes a parameter from
  `ChatBackend.limits`. A 32k-token model gets more notes, whole instead of shortened, and
  the brain index. The selection logic of Phase 4 is the same.
- **The memory pack** is the context as a document: the brain's instructions, pinned notes,
  the index, then the chosen notes in full, as Markdown. It is what the sheet shows, and it
  can be exported to a file for use in another tool. An exported file is plaintext outside
  the vault, the user's choice per export, as Markdown export already is.
- **API keys** for option (c) are stored encrypted with a Keystore key, never in
  preferences or in the vault backup.
- **No provider SDK.** The request is a small JSON over `TailnetGateway`; adding an SDK
  would add network code the project does not control.

## Steps

1. The user's decision, recorded; `CLAUDE.md` and the threat model updated.
2. `ChatBackend`; `ChatEngine` adapted; `AppState`/`ChatState` use the interface. No
   behaviour change; the 20 conversations pass.
3. `RemoteChat`: request building, streamed reply parsing, cancel; unit tests against
   recorded responses.
4. `EgressPolicy` and its table test; refusal messages.
5. Server list in Models (encrypted), test button, per-brain model and egress.
6. The pre-send sheet and the memory pack; budget from `limits`.
7. Fallback to the local model when the server is unreachable.
8. Phase 5's ingest can target a remote backend when the brain allows it.
9. Option (c), if chosen: provider presets, key storage, an extra confirmation per brain.

## Tests

- Unit: `EgressPolicy` for every pair of brain setting and backend; address checks (tailnet
  range, HTTPS, no redirects off the host); streamed reply parser with cut and malformed
  chunks.
- Phone: a real request to a server on the tailnet; Tailscale off gives the refusal and the
  local fallback; a `device` brain never produces a connection (checked in the gateway log).
- `scripts/check-release-privacy.sh` gains a check that no code outside `:core:net` opens a
  connection.
- `AssistantEvalTest` can run against a remote backend; results for the bigger model are
  recorded separately from the phone model's.

## Acceptance

- [ ] A brain set to "This phone only" cannot send anything, proven by test.
- [ ] With Tailscale off, nothing is sent and the user is told why.
- [ ] The sheet's list of notes and character count equals what is sent, byte for byte
      (tested by comparing the sheet's pack with the request body).
- [ ] Stopping a remote reply closes the connection.
- [ ] No new dependency that opens sockets.

## Risks and limits

- The user's machine becomes part of the trust boundary: its disk, its logs, whoever uses
  it. Ollama and similar servers may log prompts. The setup screen says so.
- A bigger model changes answers; prompts tuned for the phone model may need a variant. The
  evaluation shows it.
- Option (c) sends personal notes to a company. Even per brain and off by default, it is
  the largest privacy change in the project's history; it gets its own review before it is
  built.

## Out of scope

Running a server on the phone for other tools (MCP). Fine-tuning. Sync of the vault itself.
