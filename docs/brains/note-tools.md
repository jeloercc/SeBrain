# Tools on existing notes

**Status: built 2026-11-13 and installed on the Pixel.** Asked for by the user on that day:
the local assistant should be able to edit, improve, correct and link notes. Decision 2 was
confirmed with it: **the assistant proposes, the user reviews the note before and after, and
nothing is written until APPLY.**

## How a tool starts

The local models report no native function calling, and letting the model decide when to act
through a text protocol was removed on 2026-11-11 because it acted on every message. So the
app starts a tool, in two ways:

- **From a note:** the buttons FIX SPELLING, IMPROVE, SUGGEST TAGS, LINK MENTIONS and EDIT….
- **From the chat:** a message that starts with an edit verb, says "nota" or "note", and names
  a note that exists ("corrige la nota Protocolo SSH"). The longest existing title or alias
  found in the message is the note. "Crea una nota sobre cómo mejorar TCP" starts with
  "crea", so it creates a note as before.

## The five tools

| Tool | What the model is asked | What the app does | Cost on the phone |
| --- | --- | --- | --- |
| Fix spelling | A list of corrections, `wrong => right`, with an example in the prompt | Applies each one where the note says it, only if it changes spelling word by word. Never inside a link, code or the title line. If the model answers with text instead of a list, the words are lined up and only near-identical ones are taken | A few lines written |
| Suggest tags | One line: `Tags: a, b` , with the tags already in use | Adds the ones the note lacks as a last line | One line written |
| Edit, adding | Only the new sentence | Keeps the sentences the note did not have and places them above the closing tags | A sentence written |
| Edit, changing | The whole note with the instruction applied; the instruction comes last in the prompt | Puts back the front matter, the title line and dropped tags; reports a removed link or a changed code block | The note written again |
| Improve | The whole note, reordered into the note format | As above, and also puts back any link (under "Related") and any code block the model dropped | The note written again |
| Link mentions | Nothing: no language model | Links the other notes this one names by title or alias. Then adds, under "Related", up to 3 notes that retrieval finds close in meaning (the hybrid search the assistant answers with, kept only when the vector search ranks them in its top 5) | Retrieval only |

The model never sees the front matter. A rewrite is limited to notes of 4,000 characters, so
it fits the 4096-token context twice.

## Review

`ProposalScreen` shows the warnings, then the note line by line with what is removed and
added. APPLY writes the change only if the note is still as it was when the proposal was
made. A proposal lives in memory and is cleared when the app leaves the screen.

## A limit on every reply

`ChatEngine.reply` now takes the most tokens a reply may have: 1000 for an answer, 1400 for a
request for notes, 300 for a summary or a fix, 60 for tags, and the note's size plus 300 for a
rewrite. Before this a reply had no limit. Checked on the phone: at the limit the reply ends
quietly, without an error.

## Measured (2026-11-13)

`AssistantPhoneTest#noteTools`, one run per version, on a 430-character note in Spanish with
four spelling mistakes, a link, a code block and two tags. **The model was
`gemma-4-E2B-it-Uncensored-MAX.litertlm`, the one selected in the app that day, on GPU, with
the phone in use by its owner; it wrote about 2 tokens/s.** Earlier figures in
`benchmarks.md` are for Gemma 4 E4B abliterated at about 7.5 tokens/s.

| Tool | Time | Result |
| --- | --- | --- |
| Fix spelling | 21 s | 2 of the 4 mistakes fixed ("intercambio", "después … simétrico"). The model missed "maquina" and "archibos". One suggestion that changed the meaning ("cifrado asimetrico" to "criptográfico") was refused, and "Protocolo" to "Protocol" was kept out of the title |
| Suggest tags | 5 s | Added `#protocolos`, from the tags offered |
| Edit, adding ("añade … que el puerto por defecto es el 22") | 34 s | The model returned the whole note; only "El puerto por defecto es el 22." was added |
| Edit, changing ("cambia el ejemplo para que use el puerto 2222") | 42 s | The code block changed as asked. The model also dropped a link and a tag: the tag was put back, the link is reported |
| Improve | 61 s | Title, lead, "Puntos clave", "Detalles". The dropped link and code block were put back. The model wrote a filler sentence under "Detalles" |

Before these guards, the first version of this run proposed a duplicated title, a lost code
block, a whole note appended to itself and two corrections that changed the meaning.

The time shown before a tool runs is an estimate from the speed of the model's last replies.

## Not verified

- The buttons, the review screen and APPLY used by hand in the app.
- Starting a tool from a chat message on the phone (its rules are unit-tested).
- Link mentions with related notes on the real vault (unit-tested; it needs the embedding
  model and several related notes).
- The 20 conversations were not rerun after Phase 2 or after this: a full run was started,
  the phone was in use, and one answer took 14 minutes; it was stopped.

## Added on 2026-11-14, after a screenshot from the user

The user asked in the chat "revisa conceptos no enlazados en todas nuestras notas" with
`gemma-4-E2B-it-Uncensored-MAX` loaded. The model wrote its system instruction back and then
repeated one line until it was stopped. Two separate causes, two fixes. **Neither has run on
the phone: it was not connected.** Both pass their unit tests.

**1. The request had no tool.** It asks about the whole vault, and the tools worked on one
named note.

- `AppState.reviewVaultLinks`, started by the REVIEW LINKS button in Notes or by a chat
  message that speaks of links and of all the notes (`NoteEdits.wantsVaultReview`).
- It walks every note in pages of 20 and proposes links for the other notes each one names.
  A name index (`NoteEdits.Names`) offers only the notes whose name starts with a word of
  the text, so it does not test every name against every note.
- No language model is used. Related notes by meaning are left to the per-note tool, since
  they need a search for each note.
- The result is a queue of proposals, one per note, reviewed one by one: APPLY, SKIP, or
  STOP REVIEW. At most 40 notes are queued per run.
- The report also lists the links that point to no note (`NoteStore.unresolvedLinks`): the
  concepts the vault names but has not written.

**2. A model that writes its instructions back, or loops.**

- `LoopGuard` (in `:core:ml`): a reply is stopped and cut when its last three lines start
  alike, or when its last 50 characters were already written twice. The reply ends with a
  notice. Lists and code are not taken for loops (unit tests).
- Echo: the first 40 characters of a reply are held back. If they are the start of the
  system instruction, the reply is cancelled and tried once more with
  `Assistant.SYSTEM_COMPACT`, four plain sentences. The model's file name is remembered, so
  later messages use the compact instruction from the start. If it still echoes, the user is
  told the model is probably too small.
- The library's repetition penalty was not used: it penalizes repeating words of the
  context, which is what a rewrite of a note must do.

Checked on the phone on 2026-11-14 with the same model (`AssistantPhoneTest#echo`, one run,
no vault notes in the prompt): the app is installed and starts; neither the long nor the
compact instruction made the model write its instructions back, on the message of the
screenshot or on another one, so **the echo was not reproduced and the two guards are still
verified by unit tests only**. The screenshot's prompt carried notes from the real vault,
which this test does not have. In both runs the model only restated the request, which is why
that message now goes to the review tool instead of the model. It wrote about 2 tokens/s.

The compact instruction drops rules the long one has (say how notes connect, never repeat an
earlier answer, code in a fenced block). How a small model answers with it has not been
evaluated.

## Three chat defects from the user's screenshots (2026-11-14)

The vault review worked on the phone: "Reviewed 8 notes: 7 mention(s) without a link in 5
note(s)", in seconds. The same screenshots, with `gemma-4-E2B-it-Uncensored-MAX`, showed three
defects in the ordinary chat. Each is fixed in the app, not left to the model. **Unit-tested;
not run on the phone, which was disconnected again.**

| Seen | Cause | Fix |
| --- | --- | --- |
| "haz una nota en Ingles de tu sujerencia" gave a note in Spanish | The prompt ended with "write in Spanish, the language of the message" | `Assistant.requestedLanguage`: a language asked for by name wins, and the note's section names follow it |
| That note was about the first message of the chat, not about the suggestion | The request points at the previous answer, and the model picked another topic. The review's report was also stored as something the assistant had said | `Assistant.refersToPrevious`: for a note request that points at the answer just given, the prompt carries that answer and says the note is about it. Tool reports are now action lines, kept out of the model's history |
| An answer opened with `El usuario pregunta: "…"` and `**Respuesta:**` | The small model describes the message before answering | `Assistant.dropPreamble` removes that opening; the compact instruction also says not to restate the message |

A phone test for the second case exists (`AssistantPhoneTest#noteOfPreviousAnswer`) and has
not run.

## Where the code is

`app/.../chat/NoteEdits.kt` (pure: prompts, parsing, guards, diff; 16 unit tests),
`AppState.proposeEdit`, `applyProposal`, `ui/ProposalScreen.kt`, the tool buttons in
`ui/NotesScreen.kt`.
