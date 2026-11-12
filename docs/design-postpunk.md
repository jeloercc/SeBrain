# SeBrain visual direction: simple post-punk

**Status: implemented in the app shell, 2026-11-11.**

## Direction

SeBrain uses a restrained post-punk identity rather than a neon cyberpunk one:

- ink-black backgrounds and panels
- bone-white text and controls
- oxidized red for actions and emphasis
- muted olive for secondary accents
- monospace labels and headings
- angular, cut-corner shapes instead of rounded cards

The visual system is intentionally small. It should make the vault feel like a focused
private tool, not a game interface. Vault, search, chat and model behavior are unchanged.

## Notes screen

The Notes screen now uses:

- a circuit-brain mark, `SeBrain` identity and compact SQLCipher/local-AI diagnostics
- the `Search notes...` field and quick filters
- a priority `03. Network Graph` card with a coral center node, gray connections and a
  `[[Markdown elements]]` link pill
- coral emphasis for the active `PROT-01` note when that note exists
- uppercase `NOTES`, `GRAPH`, `ASK` and `MODELS` navigation with a coral active indicator

## Wiki links

Wiki links are parsed from Markdown prose and rendered as interactive pills. The brackets
remain visible but are muted, while the link title is brighter:

```text
[[backlinks]]  [[tags3]]  [[Markdown elements]]
```

Selecting a pill resolves the note by title and opens it when the target exists. This keeps
the stored Markdown format unchanged while replacing the dense plain-text presentation.

## Launcher icon

The adaptive launcher icon uses:

- a black field
- a bone-white circular outline
- a coral circuit-brain silhouette
- small bone circuit traces and coral nodes

The icon is defined in `app/src/main/res/drawable/ic_launcher_foreground.xml` and is
referenced by the adaptive icon in `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`.

## Device check

Command:

```text
ANDROID_SERIAL=57301FDCR001DP ./gradlew :app:assembleDebug :app:installDebug
```

Result: **PASS**. The debug APK built and installed on the Pixel 10.

The launcher showed the circuit-brain icon after installation. Debug builds allow screenshots for
visual verification; release builds keep `FLAG_SECURE` enabled. An attempted in-app screenshot was
not accepted as a visual verification result because Instagram remained the focused visible
window even though `com.sebrain.app/.MainActivity` resolved successfully. The redesigned UI was compiled successfully, and the UI hierarchy on the Pixel contained
the new header, diagnostics, filters, `03. NETWORK GRAPH` card and link pill. The visual
screenshot itself is black because `FLAG_SECURE` protects vault screens; it is not used as
visual evidence.

## Empty-vault visual correction

The empty Notes screen was revised after the 2026-11-11 screenshot review:

- The in-app mark now uses the coral circuit-brain shape instead of the temporary crosshair.
- The header separates identity, vault state and AI state so the diagnostics do not compete with
  the title.
- The AI label now says `LOCAL AI: READY` only when a model is loaded; otherwise it says
  `LOCAL AI: NOT LOADED`.
- The graph card no longer draws a fictitious network or the sample `Markdown elements` pill
  when the vault has no notes.
- The empty vault has explicit `NEW NOTE` and `IMPORT` actions.
- The floating new-note action uses the coral accent instead of the unrelated blue tone.

The corrected debug build compiles and was installed on the Pixel. A new visual screenshot is
pending because the device was locked and dozing during that historical pass.

## Real note graph preview

The Notes preview now uses the actual `NoteGraph` data:

- The `03.` prefix was removed from the graph label.
- Each real note contributes exactly one dot.
- Lines are drawn only for stored note links.
- There is no invented center node, placeholder ring or sample link.
- An empty vault shows an instruction instead of fabricated graph data.
