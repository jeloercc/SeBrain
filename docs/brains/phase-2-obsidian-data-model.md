# Phase 2 — Obsidian's data model

**Status: built, and installed on the Pixel on 2026-11-13.** Schema v6 approved by the user
("quiero dejarlo así, implementa"). On the phone: `NoteModelTest` 9 of 9 on a test vault (the
v5 to v6 migration keeps the links), the other vault tests pass with v6, and the real vault
opened with 8 notes and no error. One bug was found by that test and fixed: a link by file
name showed as an unresolved node in the graph.

Still not verified: the note view, folders, and vault import and export used by hand; the
import fixture; the 20 conversations after this phase. The rows marked "Not run" below ran
on 2026-11-13 and pass.

## What was built

| Part | Where | Verified |
| --- | --- | --- |
| Front matter parser, no library | `cognition/FrontMatter.kt` | 6 unit tests |
| Links with anchor and embed, nested tags, tags and aliases from properties, title and chunks that skip the front matter | `cognition/NoteMarkup.kt`, `Chunker.kt` | 8 unit tests |
| Sections and blocks a link points to; turning a mention into a link; vault file paths | `cognition/NoteSections.kt`, `MarkdownInterop.kt` | 7 unit tests |
| Schema v6: `path`, `property`, `alias`, rebuilt `link`, and the brain tables of Phase 3 | `core/vault/VaultSchema.kt` | **Not run** (`NoteModelTest`, phone locked) |
| Notes read and written with path, aliases, properties; a link resolved by title, alias, then end of path; backlinks and graph through aliases; unlinked mentions; folders | `core/vault/NoteStore.kt` | **Not run** (`NoteModelTest`) |
| Hash of a note with a path covers path and text, applied inside `updateText` | `NoteStore.contentHash` | **Not run** (`NoteModelTest`) |
| Backup format v3 with the path; v1 and v2 still read | `VaultBackupSnapshot.kt` | 3 unit tests; restore **not run** |
| Note view: path, aliases, properties table, embedded notes, links to a heading or block, "Mentioned without a link" with a LINK button | `ui/NotesScreen.kt`, `ui/Markdown.kt` | Compiles; **not used on the phone** |
| Folder chips in Notes | `ui/NotesScreen.kt`, `AppState.selectFolder` | Compiles; not used |
| Import and export of a vault folder, in batches of 20 with a progress line and a report | `app/VaultFolder.kt`, `AppState.importVault`, `exportVault` | Compiles; **not run with a real folder** |
| Notes written by the assistant start with `source: assistant` and `created` | `NoteTools.createNote` | Compiles |

A bug found on the way: the backup export read the note's row id from the wrong column (the
segment UUID), so tags and links were exported for another note. It went unnoticed because a
restore reads them again from the text. Fixed.

Differences from the plan below:

- **Properties are shown, not edited in place.** They are edited in the note's text, which
  holds the front matter. Cell editing belongs with the tables of Phase 7.
- **A nested tag is stored with its parents** (`#redes/tcp` also adds `redes`), so the tag
  filter finds it under the parent without a prefix query.
- **Import indexes each batch as it goes**, vectors included, instead of storing everything
  first. A batch of 20 notes is one transaction; search and chat run between batches.
- **`MarkdownInterop` was kept** and extended, not replaced by a `VaultInterop`.
- **The import fixture vault is not written yet**, so "its links resolve as Obsidian resolves
  them" is not measured.

## Resources verified against the code (2026-11-12, before any building)

| Needed | Found | Result |
| --- | --- | --- |
| Links with an anchor and an embed flag | The `link` table is read and written in six places: `NoteStore` (graph, `setMarkup`, backlinks, `linkedFrom`, delete) and `VaultBackupSnapshot` | Rebuild is contained. `setMarkup` takes `List<String>` and must take a link type |
| Parsing `[[Note#Heading]]`, `[[Note\|text]]`, `![[Note]]` | `NoteMarkup.LINK` already accepts the heading and the display text and throws them away; an embed is already counted as a link | Small change: keep what is now discarded |
| A title for a note that starts with front matter | **Problem.** `NoteMarkup.titleOf` takes the first non-empty line, so such a note would be titled `---` | `titleOf`, `Chunker` and the tag parser must skip the front matter block. Added to the steps |
| Importing notes with the same text | **Problem.** `capture.content_sha256` is `UNIQUE`. Two files with the same text (empty notes, templates, daily notes) cannot both be imported, and links to the second would dangle | Proposed below: for a note with a path, the hash covers path and text. Needs approval with the schema |
| Reading a folder tree | No `androidx.documentfile` in the build | Use the framework's `DocumentsContract` with `OpenDocumentTree`. No new dependency |
| A YAML parser | No YAML library in the build | The small parser of this plan stands. No new dependency |
| Indexing a large import | EmbeddingGemma on the NPU: 41.3 texts/s sustained for 389-character texts, foreground only (`benchmarks.md`); `ChunkStore.chunksWithoutVector` already lets vectors be added later | 15,000 chunks is about 6 minutes at that rate; longer chunks will be slower (not measured). Words search works at once; vectors fill in while the app is open, and resume if it is closed |
| Showing an embedded note | `MarkdownBody` renders from text alone and has `onLinkClick` and `LinkPill` | The note view must load the embedded notes first and pass them in. Medium work |
| Unlinked mentions | `chunk_fts` with `unicode61 remove_diacritics 2` supports phrase queries | As planned; "Café" also matches "cafe" |
| Partial unique index on `path` | SQLite supports `CREATE UNIQUE INDEX … WHERE` | As planned |

Two changes to the proposal come out of this, both marked **(verified)** below.

## Goal

A note in SeBrain can hold everything a note in Obsidian holds that matters for combining
concepts, and a whole Obsidian vault can be imported and exported without losing it.

## What the user sees

- **Properties:** a note can start with YAML front matter. Its fields show as a small table
  at the top of the note and can be edited there.
- **Aliases:** `aliases: [TCP, Transmission Control Protocol]` makes `[[TCP]]` resolve to the
  note.
- **Folders:** a note has a path, such as `Redes/Protocolos/TCP`. Notes can be browsed by
  folder.
- **Precise links:** `[[Note#Heading]]`, `[[Note#^block]]`, `[[Note|shown text]]`, and
  embeds `![[Note]]`, which show the other note's text inside this one.
- **Unlinked mentions:** under the backlinks, "Notes that name this one without linking it",
  with one tap to turn a mention into a link.
- **Nested tags:** `#redes/protocolos` also matches the filter `#redes`.
- **Vault import and export:** pick a folder; every `.md` file comes in with its path,
  properties, tags and links. Export writes the same structure back.

## Schema v6 (proposal)

Added to the encrypted vault. Derived data (chunks, FTS, vectors) is rebuilt, not migrated.

```sql
-- A note's place in the folder tree, without ".md". NULL for notes created before v6;
-- they are shown at the root under their title.
ALTER TABLE capture ADD COLUMN path TEXT;
CREATE UNIQUE INDEX capture_by_path ON capture(path) WHERE path IS NOT NULL;

-- Front matter, one row per value, so it can be filtered and sorted (Phase 3 scopes,
-- Phase 7 tables). A list property has one row per item.
CREATE TABLE property(
    capture_id INTEGER NOT NULL,
    key TEXT NOT NULL COLLATE NOCASE,
    type TEXT NOT NULL,            -- text | number | date | bool | list
    value TEXT NOT NULL,           -- as written
    number REAL,                   -- set for number, date (epoch day) and bool
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(capture_id, key, position)
);
CREATE INDEX property_by_key ON property(key, value);

-- Other names of a note. A link resolves to a title first, then to an alias.
CREATE TABLE alias(
    capture_id INTEGER NOT NULL,
    name TEXT NOT NULL COLLATE NOCASE,
    PRIMARY KEY(capture_id, name)
);
CREATE INDEX alias_by_name ON alias(name);

-- Links gain the part of the target they point to and whether they embed it.
-- The old table has PRIMARY KEY(source_id, target_title); it is rebuilt.
CREATE TABLE link_v6(
    source_id INTEGER NOT NULL,
    target_title TEXT NOT NULL COLLATE NOCASE,
    anchor TEXT NOT NULL DEFAULT '',   -- 'Heading', '^blockid' or ''
    embed INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(source_id, target_title, anchor, embed)
);
-- INSERT INTO link_v6 SELECT source_id, target_title, '', 0 FROM link; DROP TABLE link;
-- ALTER TABLE link_v6 RENAME TO link; CREATE INDEX link_by_target ON link(target_title);

-- Phase 3, same migration: see phase-3-brains.md for brain, brain_pin and
-- conversation.brain_id.
```

**(verified) The content hash of a note with a path.** `content_sha256` stays `UNIQUE`, and
its meaning changes for notes that have a path: it is the SHA-256 of the path, a newline and
the text. Notes without a path (shared text, notes written in the app before v6) keep the
hash of the text alone, so sharing the same text twice is still caught. The cost: text shared
into the app is no longer recognised as a duplicate of an imported file with the same text.

**(verified) Front matter is not chunked.** The YAML block stays in `capture.text` and is
skipped by the title rule, the chunker and the inline tag parser. Its values are searchable
through `property`, and Phase 4 puts tags and aliases in the chunk headers.

Not stored: block ids and headings. They are found by reading the target note's text when
the link is followed, which keeps one source of truth.

**Backup format v3** adds `path`, properties and aliases to each note record, and the brains
of Phase 3. Formats v1 and v2 are still read.

## Design notes

- **Front matter is part of the note text.** The text stored in `capture.text` keeps the
  YAML block, so export gives back the same file. `property` and `alias` are derived from it
  by `NoteIndexer.markup`, like tags and links are today, and rebuilt by `rebuildAll`.
- **YAML subset:** scalars, inline lists `[a, b]`, block lists, quoted strings, dates
  `YYYY-MM-DD`. Nested maps are kept in the text and not indexed. A block that does not
  parse is kept as text and reported, never dropped. No YAML library is added; the parser is
  small and unit-tested.
- **Title:** the first `# Heading`, as today; for an imported file without one, the file
  name. `[[X]]` resolves, in order, to a title, an alias, then the last part of a path.
- **Tags in properties** (`tags: [a, b]`) are merged with inline `#tags`.
- **Embeds** render the target's text (or the heading section, or the block) inline, one
  level deep; an embed inside an embedded note is shown as a link, which also stops cycles.
- **Unlinked mentions** use `chunk_fts`: notes whose text contains this note's title or an
  alias as a phrase, minus the notes that already link to it. Titles shorter than 4
  characters are skipped.

## Steps

1. Approve the schema above (and the Phase 3 tables with it).
2. `VaultSchema.upgradeToV6`; test vault tests for a fresh v6 and for v5 → v6 with links.
3. `FrontMatter` parser in `:cognition` (pure Kotlin), with unit tests.
4. `NoteMarkup.parse`: link syntax with `#anchor`, `^block`, `|display`, `!` embed; nested
   tags; tags and aliases from properties. `titleOf` and `Chunker.chunk` skip the front
   matter block.
5. `NoteStore`: write properties, aliases and the new link fields in `setMarkup`; resolve a
   link through title, alias, path; `unlinkedMentions(id)`; `folders()` and `inFolder(path)`.
6. `NoteIndexer.rebuildAll` fills the new tables for existing notes after the migration.
7. UI: properties table in the note view and editor; folder browser in Notes; embeds and
   anchor links in `Markdown.kt`; unlinked mentions under backlinks.
8. `VaultInterop` replacing `MarkdownInterop`: import a folder tree through SAF
   (`OpenDocumentTree`, read with `DocumentsContract`; no new dependency), with a report of files imported, skipped as duplicates, and
   attachments ignored; export a folder tree.
9. Backup format v3; restore validation test.
10. `NoteFormat`: notes written by the assistant get front matter (`source: assistant`,
    `created`, `brain` from Phase 3), so they can be told apart and filtered.

## Tests

- Unit: front matter parser (valid, malformed, unicode, dates, lists); link parser (every
  syntax, links inside code blocks ignored); title resolution order.
- Phone, test vault: migration v5 → v6 keeps every link; properties and aliases survive a
  rebuild; unlinked mentions; backup v3 round trip and reading of v2.
- Import fixture: a small Obsidian vault kept in `core/vault/src/androidTest/assets`, with
  folders, front matter, aliases, embeds, heading and block links. After import, the set of
  resolved links equals a list written by hand in the fixture.

## Acceptance

- [ ] The fixture vault imports with every link resolved as Obsidian resolves it.
- [ ] Export then import of the vault gives the same notes, paths, properties and links.
- [ ] A note with malformed front matter opens, shows its text, and says what failed.
- [ ] Existing notes and links are unchanged after the update.
- [ ] The 20 conversations pass (notes written by the assistant now carry front matter).

## Risks and limits

- Obsidian plugins (Dataview queries, Templater) are text to SeBrain; they are kept, not run.
- Attachments (images, PDFs) are not imported in this phase; links to them stay as text.
- A large vault import embeds every note: with EmbeddingGemma on the NPU this is the slow
  part. The import first stores and parses everything, then indexes in the background with
  a progress line; search by words works immediately.

## Out of scope

Attachments, Canvas files, `.base` files (Phase 7), sync with a live Obsidian vault.
