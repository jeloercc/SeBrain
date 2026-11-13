# Plan: every note is memory, several brains, full Obsidian linking

**Status: proposed 2026-11-12, nothing built.** It follows the user's request of that day: one
kind of memory (the notes), several "brains" that combine data, the way Obsidian combines
concepts, and memory that can be handed to the local model or to an external model. The
decisions at the end must be made before the phase that needs them.

## What the research found (2026-11-12)

### 1. The community pattern is "the notes are the memory"

The pattern most used with Obsidian since April 2026 is the **LLM wiki** (Andrej Karpathy's
gist, 4 April 2026). The model does not keep a hidden memory store; it writes and maintains
linked Markdown pages, and those pages are what it reads later.

- Three layers: **raw sources** the model never edits, the **wiki** of linked pages (source
  summaries, entity pages, concept pages, kept answers), and a **schema** file with the rules.
- Three operations: **ingest** (one source can touch 10 to 15 pages), **query** (read an index
  first, then the pages; a good answer is filed back as a page), and **lint** (contradictions,
  stale claims, orphan pages, concepts mentioned without a page, missing links).
- Two helper pages: `index.md`, a catalog with one line per page, and `log.md`, an append-only
  history. The gist says the index alone works up to a few hundred pages, without embeddings.
- The Obsidian plugin that implements it retrieves with **Personalized PageRank over the
  `[[links]]`**, so the links the user writes are what make multi-hop retrieval work.

Sources: gist.github.com/karpathy/442a6bf555914893e9891c11519de94f;
community.obsidian.md/plugins/karpathywiki; sentra.app/articles/karpathy-llm-wiki.

### 2. Dedicated memory layers are costly to write and hard to verify

Mem0 (facts extracted from each message, then ADD/UPDATE/DELETE), Letta (memory blocks pinned
in the context, edited by the agent, plus an archive) and Zep/Graphiti (a graph of facts with
validity dates) all pay for memory with **a model call on every write**. Their published
scores are run by the vendors and disagree: Mem0 reports 94.4 on LongMemEval, an independent
run measured 49.0.

What this means here: at about 7.5 tokens/s on the phone, extracting facts on every message
is not affordable, and a store nobody can read is the opposite of what the user asked for.
Two ideas are worth taking: Letta's **small block that is always in context**, and Zep's
**a changed fact is marked superseded, not silently overwritten**.

Sources: digitalapplied.com/blog/open-source-agent-memory-mem0-letta-zep-compared;
infranodus.com/docs/llm-memory-tools-comparison; coworker.ai/blog/mem0-vs-zep-vs-letta.

### 3. Graph retrieval pays off on multi-hop questions, and extraction is the expensive part

In an AWS comparison of GraphRAG and LightRAG, the dominant cost was extracting entities and
relations from every document with a model. The cheapest graph strategy reached about 82% of
the best accuracy at an eighth of the cost, and the advice for single-fact questions was to
try plain vector search before any graph.

What this means here: SeBrain already has a graph the user built by hand (links, tags), so it
can skip the extraction. Ranking over that graph (Personalized PageRank seeded by the search
hits) costs no model call.

Source: aws.amazon.com/blogs/opensource/unified-knowledge-graph-rag-on-aws-graphrag-and-lightrag-on-one-stack.

### 4. Cheap retrieval gains that are still missing

Anthropic's contextual retrieval: prepend a short context to each chunk before embedding and
before the full-text index; hybrid search beats embeddings alone; a reranker adds more. The
free version of this for notes is to prepend the note title, the heading path, the tags and
the aliases to each chunk, with no model call.

Source: anthropic.com/research/contextual-retrieval.

### 5. What "combining concepts" means in Obsidian today

Beyond `[[links]]`, tags and backlinks, which SeBrain has: **properties** (YAML front matter),
**aliases**, links to a **heading** or a **block** (`[[Note#Heading]]`, `[[Note^id]]`),
**embeds** (`![[Note]]`), **unlinked mentions**, the **local graph** with depth and groups,
**Bases** (table, cards, list and kanban views over properties, a core plugin), and
**Canvas**.

Sources: obsidian.md/help/plugins/graph; obsidian.md/help/bases.

## The answer to "what is the difference between a memory and a note?"

Today there are two kinds, and that is the confusing part:

- A normal note reaches the model **only when it matches the question** (search plus graph).
- A `#memory` note reaches the model **in every prompt**. "Suggest memories" and "recuerda
  que…" only create that second kind.

Proposed: one kind. Every note is memory. A note can be **pinned to a brain**, which means
"always in context for that brain"; that replaces `#memory`, "Suggest memories" and the
"remember" command. It is Letta's core block, as a normal note the user can read and edit.

## Target architecture

```
Sources (share, import, chats)      never edited by the model
        │ ingest
        ▼
Notes (the vault, encrypted)        every note is memory: links, tags, properties, aliases
        │ scope
        ▼
Brain = scope + pinned notes + instructions + model + its own chats and graph
        │ build context
        ▼
Local model  │  model on the user's own machine  │  external API (opt-in per brain)
```

- **A brain is a saved view, not a copy.** Its scope is a rule over the vault: folders, tags,
  properties, capture sources, "everything linked from this hub note up to N steps", or a
  combination with and/or. One note can be in several brains. This replaces mini-brains.
- **Each brain has**: pinned notes (always in context), instructions (its system prompt), a
  model choice, its own conversations, its own graph view, and an index note the app keeps
  up to date (the catalog of the LLM wiki pattern).
- **Context is built in a fixed budget** (4096 tokens on the phone; more for a bigger model):
  pinned notes, then the index entries that match, then search hits re-ranked over the link
  graph, then graph neighbours, then the conversation layers that already exist.
- **Memory for another model** is the same context, exported: a "memory pack" (index, pinned
  notes, the notes that matter, as Markdown) sent to the chosen model. What leaves the phone
  is shown before it is sent.

## Phases

Each phase ends with something to try on the Pixel and with the 20-conversation evaluation
rerun. Size is relative: S about a day of work, M several days, L more than a week.

### Phase 1 — One memory (S)

- Remove "Suggest memories", the "remember" command and the special handling of `#memory`.
- Add "Pin to brain" on any note; pinned notes go into every prompt of that brain, within a
  shown budget. Existing `#memory` notes become pinned notes of the default brain.
- No schema change if the pin is stored as a property/tag; otherwise it rides on Phase 2.

### Phase 2 — Obsidian's data model (L, schema v6, needs approval)

- Properties (front matter) with types, aliases, nested tags, folders (a note has a path).
- Links to headings and blocks, embeds, unlinked mentions ("this note names X but does not
  link it"; one tap to link).
- Import and export of a whole Obsidian vault folder, keeping paths, properties and links.
  Today only one file at a time.
- Exit: an existing Obsidian vault imports and its graph matches Obsidian's.

### Phase 3 — Brains (M, same schema migration as Phase 2)

- Brain table: name, scope rule, instructions, model, pinned notes. Brain switcher at the top
  of Notes, Graph and Ask; conversations belong to a brain.
- Scope builder: folders, tags, properties, sources, hub note with depth, and/or.
- A default brain "Everything" keeps today's behaviour.
- Exit: two brains over overlapping notes answer the same question differently, each from
  its own scope.

### Phase 4 — Retrieval that uses the graph (M, no model cost)

- Chunk headers: title, heading path, tags and aliases prepended before embedding and
  full-text indexing; one re-index.
- Personalized PageRank over links and shared tags, seeded by the hybrid search hits,
  replacing the fixed "one step" neighbour rule.
- An index note per brain, kept by the app (title and first sentence of each note), used as
  a map when the question is broad.
- Measure the two open criteria of the roadmap: search over 10,000 notes under 100 ms, and
  the right note in the top 8 for at least 80% of 50 real questions.

### Phase 5 — The wiki operations (L)

- **Ingest:** a captured source becomes a summary note plus proposed edits to the concept
  notes it touches. Each change is shown and approved before it is written. On the phone
  model this is slow (one source can mean ten notes), so it runs as deferred work while
  charging, or on the bigger model of Phase 6.
- **File an answer:** exists ("Save as note"); gains the source and the brain as properties.
- **Lint:** without a model: orphans, dead links, duplicate titles, concepts mentioned in
  several notes without a note of their own. With a model, on demand: contradictions and
  superseded claims, marked in the note instead of overwritten.
- **Log:** an append-only note per brain of what the assistant did.

### Phase 6 — Other models (M, changes a hard rule, needs approval)

- One interface for a chat model, with three implementations: the local LiteRT-LM model, an
  OpenAI-compatible endpoint on the user's own machine reached over Tailscale (Ollama,
  llama.cpp, LM Studio), and an external API.
- Model chosen per brain. A brain can be marked "never leaves the phone".
- Before anything is sent, the screen shows which notes and how many characters leave.
- Every connection still goes through `TailnetGateway`.

### Phase 7 — Views (L, later)

- Local graph with depth and colour groups; table and card views over properties (Bases);
  Canvas in the open JSON Canvas format.

## Decisions for the user

| # | Decision | Recommendation | Needed for |
| --- | --- | --- | --- |
| 1 | Replace `#memory`, "Suggest memories" and "remember" by "pin a note to a brain" | Yes | Phase 1 |
| 2 | May the assistant edit existing notes, or only propose changes you approve | Only propose, with approval | Phase 5 |
| 3 | Where note content may be sent: (a) nowhere, (b) only your own machine over Tailscale, (c) also an external API, per brain | (b) first; (c) only per brain, off by default | Phase 6 |
| 4 | Storage: keep the encrypted vault as the source of truth with import/export of Obsidian folders, or store plain Markdown files like Obsidian | Keep the encrypted vault; plain files break "nothing unencrypted on disk" | Phase 2 |
| 5 | Schema v6: paths, properties, aliases, link anchors, brains, pins | Approve after its design is written | Phases 2 and 3 |
| 6 | Order: this plan before the work-profile firewall of the Privacy plan, or after | This plan first | Scheduling |

Decision 3 changes a hard rule of `CLAUDE.md` ("Note content never leaves the device in
plaintext"). Options (b) and (c) send the text of the selected notes to another machine over
HTTPS; with (c) the provider can read it.

## What this plan does not include

A model-built knowledge graph (entity extraction over every note, as GraphRAG does): the
research shows extraction is the dominant cost, and the user's own links already are a graph.
A separate memory store such as Mem0 or Zep: the notes are the store.
