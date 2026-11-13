# Phase 2 — Obsidian's data model

**Status: not started.** Size L. **Schema v6: proposed here, to be approved before any code.**
Confirms decisions 4 and 5.

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
   tags; tags and aliases from properties.
5. `NoteStore`: write properties, aliases and the new link fields in `setMarkup`; resolve a
   link through title, alias, path; `unlinkedMentions(id)`; `folders()` and `inFolder(path)`.
6. `NoteIndexer.rebuildAll` fills the new tables for existing notes after the migration.
7. UI: properties table in the note view and editor; folder browser in Notes; embeds and
   anchor links in `Markdown.kt`; unlinked mentions under backlinks.
8. `VaultInterop` replacing `MarkdownInterop`: import a folder tree through SAF
   (`OpenDocumentTree`), with a report of files imported, skipped as duplicates, and
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
