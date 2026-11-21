# Plan: Next steps for the local agent's long-term memory

**Complexity**: Medium overall; each phase is small to medium and ships on its own.
**State**: approved 2026-11-18. Phases 1–3 done (74d3cc3, c8962c9, dd721a9). Phase 4 not started: it needs its own design (see the final report). Phase 5 is the user's.

## Summary

The agent now remembers by search, links, tags, aliases, time, properties and templates
(Obsidian's model), all on the phone. What is left is for it to **write** memory the way
Obsidian users do (daily notes), to **ask finer questions** of its memory (comparisons), and to
**trust memory less when it came from outside**. Each phase is deterministic: the model keeps no
tools.

## Phases, in recommended order

### 1. Daily notes the agent writes (M)
- "Save this in today's note" / "apúntalo en la nota de hoy" appends the answer to today's
  daily note (`Daily/2026-11-18`, title = the date), creating it from `Templates/Daily` if that
  template exists.
- Appending is shown as a diff to approve, like the other note edits.
- **Why:** time recall finds daily notes; this makes them grow without leaving the chat.
- **Check:** unit tests for the request detection; phone test: two requests on one day give
  one daily note with both parts.

### 2. Comparisons on properties (M)
- "priority above 2", "due before Friday", "vence antes del viernes": numbers and dates in the
  front matter (`property.number`, dates as `YYYY-MM-DD`), with the exact total, as today.
- Only `>`, `<`, `>=`, `<=` and "before/after" on a key the message names.
- **Why:** tasks and deadlines are the most common properties; equality alone is not enough.
- **Check:** unit tests for the phrases (EN+ES); phone test on the memory scenario's vault.

### 3. Memory from outside is marked (S)
- Notes that came from the share sheet (`source: share`) are labelled "shared from another
  app" in the prompt, so the agent weighs them as outside text.
- Five more cases in `InjectionEvalTest` (hidden in a list, in a quote, in Spanish, two-step,
  in a property).
- **Why:** the 3 current cases pass; better-disguised instructions are the next risk.

### 4. `ChatSession` out of `AppState` (L)
- The chat, its providers and its edit proposals move into one class that owns the
  conversation; `AppState` drops under 800 lines.
- **Why:** every memory feature passes through the chat code, which is the hardest to change.
- **Check:** build and the 195 unit tests after each step; a visual check of the chat screen.

### 5. Measure with real memory (you)
- Fill the vault to 50+ notes, then `scripts/run-synthetic-retrieval.sh` and about 20
  questions written from memory.
- **Why:** with 8 notes no measurement can tell good recall from luck.

## Not proposed

- Running Dataview or Templater syntax (they stay text, as decided in Phase 7).
- Giving the model tools to query its memory itself: the injection test showed why.
- An index on note dates: a schema change, only if a vault over 10,000 notes needs it.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| "Today's note" requests misread | Medium | Narrow phrases; the append is a diff the user approves |
| Comparison phrases too varied | Medium | Small fixed set first; unknown phrasing falls back to search |
| Labels on shared notes make answers worse | Low | Measured with the scenario before and after |
| `ChatSession` changes chat behaviour | Medium | Mechanical moves, one per commit, chat screen checked by hand |

## Acceptance

- [ ] Each phase: unit tests pass, its phone check passes, one commit.
- [ ] No schema change and no tools for the model.
- [ ] The PR description and `docs/roadmap.md` record what shipped.
