# Phase 4 — Retrieval that uses the graph

**Status: not started.** Size M. No schema change. No model call is added.

## Goal

The notes that reach the model are chosen with the user's own graph, not only with word and
meaning matches, and the two retrieval criteria left open in the roadmap are measured.

## Why

Today `VaultContext` takes the top 4 notes of the hybrid search and adds up to 4 neighbours
one step away. Three known weaknesses:

- A chunk is embedded and indexed without its title or headings, so a chunk that says "It
  uses port 22" is not found by "SSH".
- Neighbours are taken in a fixed order (links, backlinks, shared tags), not by how central
  they are to the question.
- A broad question ("what do I know about networks?") matches many chunks weakly and gets
  four arbitrary notes.

## What changes

### 1. Chunk headers

Each chunk is indexed (FTS5 and vector) with a header that is not shown to the user:

```
TCP (alias: Transmission Control Protocol) › Detalles · #redes #protocolos
<chunk text>
```

Title, aliases, the heading path of the chunk, tags. This is contextual retrieval without the
model call. It needs one re-index of every note (`NoteIndexer.rebuildAll`), in the
background.

### 2. Ranking over the graph

Personalized PageRank over the notes of the current brain:

- **Nodes:** notes. **Edges:** `[[links]]` (weight 1.0, both directions), embeds (1.0),
  shared tags (weight 1 / number of notes with that tag, so a tag used everywhere counts
  little), same folder (0.2).
- **Seeds:** the notes of the hybrid search hits, weighted by their fused score.
- **Result:** every note gets a score; the final order mixes it with the search score
  (proposed 0.6 search, 0.4 graph, to be tuned on the evaluation set).

Twenty iterations over a few thousand notes is arithmetic on arrays; it runs on the vault
thread in well under the 100 ms target for 10,000 notes. To be measured, not assumed.

The fixed "one step" rule is removed. A note reached only through the graph is still labelled
in the prompt ("linked from [1]"), using its strongest edge to a seed.

### 3. The index note

Each brain has an index: one line per note, `Title — first sentence`, grouped by folder. It
is built by the app from the notes, without the model, and kept in memory (not stored as a
note, so it cannot go stale or be edited by mistake).

- For a **broad question** (few strong hits, many weak ones), the prompt carries the part of
  the index that matches instead of four arbitrary chunks, so the model can say what the
  brain holds and point to notes.
- The index is also what Phase 6 sends first to a bigger model.

### 4. Measurements

- **Speed:** search over 10,000 notes under 100 ms (`SearchScaleBenchmarkTest` exists; it
  gains the graph step).
- **Quality:** the right note in the top 8 for at least 80% of 50 real questions. The user
  provides the questions and the note each one should find, in
  `docs/retrieval-evaluation-template.csv`. `scripts/evaluate-retrieval.py` exists.
- Both are measured **before** this phase's changes and after, so the gain is known.

## Steps

1. Baseline: fill the 50-question file with the user; measure top-8 and speed as they are.
2. `Chunker`: return the heading path of each chunk. `NoteIndexer`: build the header; index
   header + text; store only the text for display.
3. Re-index path: a one-time background rebuild with a progress line; search stays usable.
4. `GraphRank` in `:core:vault` (pure Kotlin): build the weighted adjacency for a set of
   note ids, run Personalized PageRank; unit tests on small graphs with known answers.
5. `VaultContext`: seeds from `HybridSearch`, rank, pick within the character budget; label
   graph-only notes; drop the one-step rule.
6. `BrainIndex`: build and cache per brain, invalidated when a note changes; the broad
   question rule in `VaultContext`.
7. Measure again; tune the mix weight on the 50 questions; record both runs.
8. Evaluation cases: a fact reachable only two links away; a broad question; a chunk found
   only through its title.

## Tests

- Unit: header building; PageRank on a chain, a star and two clusters; tag weighting; the
  broad-question rule.
- Phone: `SearchScaleBenchmarkTest` with 10,000 notes; `AssistantEvalTest` with the new
  cases; the retrieval evaluation script.

## Acceptance

- [ ] Top-8 on the 50 questions is at least 80%, or the document records the number reached
      and why.
- [ ] Search over 10,000 notes, graph step included, under 100 ms on the Pixel.
- [ ] "What do I know about X?" lists notes of the brain instead of quoting four chunks.
- [ ] A note two links away from the matching note reaches the prompt when it is the one
      that holds the answer (evaluation case).
- [ ] The 20 conversations, plus the new cases, pass.

## Risks and limits

- The 50 questions must be real. Without them the quality criterion cannot be measured and
  this phase can only claim speed.
- More context is not better: the budget is unchanged (`Assistant.INPUT_CHARS`). The gain
  has to come from choosing better, not from sending more.
- A reranker model would add accuracy and was left out: it is another model to load on the
  phone. Reconsider only if the 80% is not reached.

## Out of scope

Entity extraction with the model, community summaries (GraphRAG), a reranker.
