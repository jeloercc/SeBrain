# Phase 7 — Views

**Status: not started.** Size L. No schema change. Needs Phase 2. Can be built in parts, in
any order, whenever a part is wanted.

## Goal

The ways Obsidian lets a person look at connected notes: the neighbourhood of one note,
tables over properties, and a free board.

## Part A — Local graph (M)

- From a note: **Local graph**, the notes connected to it, with a **depth** slider (1 to 3).
- Filters: incoming links, outgoing links, links between the neighbours, tags as nodes,
  orphans (in the brain graph).
- **Groups:** colour by a rule (folder, tag, a property value, written by the assistant or
  by the user). Rules are saved per brain.
- Node size by number of links, as in Obsidian.

Design: `GraphScreen` already draws a force-directed graph for a set of notes. It gains a
centre note with a breadth-first neighbourhood (the walk written for `linkedFrom` in Phase
3), a colour function, and saved group rules (in the brain's JSON). The layout runs off the
main thread as today; the stale-layout crash fixed on 2026-11-12 stays covered.

Acceptance:

- [ ] The local graph of a fixture note at depth 2 shows exactly the notes Obsidian shows
      for the same vault.
- [ ] 500 nodes stay interactive on the Pixel (pan and zoom without visible stutter;
      measured as frame time, recorded).

## Part B — Tables over properties, like Bases (L)

- A **view** is a saved table over the notes of a brain: columns are properties (and title,
  folder, tags, dates); it has a filter, a sort and an optional grouping.
- Layouts: **table**, **cards**, **list**. Kanban (group by a property, drag to change it)
  comes after the first three.
- Editing a cell edits the note's front matter.
- A view can be exported as a `.base` file and imported from one, for the subset of the
  Bases syntax that maps to this (filters on properties, sort, columns). Formulas are not
  supported at first; a `.base` file that uses them is imported without them, with a notice.

Design: filters reuse `BrainScope` (Phase 3) plus comparisons on `property.number`; a view is
JSON stored with the brain. The Bases file syntax must be read from the current Obsidian
documentation before coding; it is not copied from memory.

Acceptance:

- [ ] A table over 1,000 notes sorts and filters without a visible wait.
- [ ] Editing a cell changes the note text, and the change survives export to Markdown.
- [ ] A `.base` file exported from Obsidian with plain filters opens with the same rows.

## Part C — Canvas (L)

- A board where notes, text cards and links are placed freely and joined by arrows.
- Stored in the **JSON Canvas** open format (`.canvas`), so boards move to and from
  Obsidian. Stored in the vault as a note-like record with `kind: canvas`.
- The assistant can be asked to lay out a set of notes as a board ("put my notes about
  Atlas on a canvas by topic"): it proposes groups; positions are computed by the app.

Design: a Compose canvas with pan, zoom, drag and connect. The format's node and edge fields
must be read from the JSON Canvas specification before coding.

Acceptance:

- [ ] A canvas made in Obsidian opens with the same cards, positions and arrows.
- [ ] A canvas made in SeBrain opens in Obsidian.

## Order inside the phase

A first: it reuses the most and answers "how does this connect?". B second: it is what makes
properties worth filling in. C last: the largest new interface and the least tied to the
assistant.

## Risks and limits

- Each part is a large piece of interface on a phone screen; Obsidian's versions were
  designed for a desktop. Each part starts with a short design note of what is left out on
  a phone.
- Bases and JSON Canvas are living formats. Support is stated per version, and unknown
  fields are kept on export instead of dropped.

## Out of scope

Obsidian community plugins, Dataview queries, themes, Publish.
