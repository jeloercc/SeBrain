# Design: search index and semantic search

**Status: Implemented.** It compiles and its unit tests pass; the embedding path has not run on the phone inside the app yet.

## Flow

```text
import:  capture text -> Chunker -> chunks
         -> chunk + chunk_fts (always) + chunk_vec (if the embedding model is installed), one transaction
search:  query -> FTS5 (bm25) top 50  +  query vector -> vec0 top 50
         -> Reciprocal Rank Fusion (k = 60) -> top results
```

## Vault schema, version 2

- `capture.index_status`: `pending` (no chunks), `text` (chunks and full text), `indexed` (vectors too).
- `chunk(id, capture_id, position, start_offset, end_offset, text)`.
- `chunk_fts`: FTS5 over `chunk.text`, `unicode61 remove_diacritics 2`.
- `chunk_vec`: `vec0`, `float[256]`, row id equal to `chunk.id`.

A version 1 vault is upgraded on open. Captures stored before the index existed are indexed on the next import.

## Decisions

- **Chunks of at most 800 characters,** about 200 tokens, split at paragraph, then sentence, then word boundaries. No overlap.
- **Embeddings: EmbeddingGemma 2 Text 270M, 256 dimensions, L2 normalized.** Documents use the prefix `title: none | text: …` and queries `task: search result | query: …`, as the model card specifies.
- **Backend chain: NPU, then CPU.** The GPU is skipped: on the Pixel 10 it was slower than the CPU and lost two thirds of its speed when warm. This narrows the "NPU → GPU → CPU" chain in CLAUDE.md for embeddings only.
- **Text search never depends on the model.** Without a model, or if it fails, captures are stored with full text only and vectors are added on a later import.
- **Query text is quoted word by word** before it reaches FTS5, so it cannot be read as FTS5 syntax.
- **Model files** are imported with the system file picker or pushed with adb, and accepted only if their SHA-256 is pinned in `ModelFiles`. The model is released when the app leaves the foreground.

## Not done

- Embedding from a background Worker.
- Removing a capture and its index from the UI.
