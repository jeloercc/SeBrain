# Integrations and mini-brains

**Status:** notification bridge, encrypted policy settings, source-filtered graph and encrypted saved mini-brains implemented and verified on Pixel with Termux
**Date:** 2026-11-11

## Goal

SeBrain should become a local graph of the user's activities without unrestricted
access to other applications. Each approved source contributes captures, entities
and relationships to the same encrypted vault. A "mini-brain" is a filtered view
of that graph for one source or activity, not a second unencrypted database.

Examples:

- Messages and notifications
- Browser links and saved pages
- Tasks and calendar events
- Files and Markdown
- Development or study material

## Integration boundary

All integrations enter through `:ingest`. No source writes directly to the vault
or opens a network connection.

SeBrain is a privacy control plane, not an Android container. It cannot safely
host arbitrary third-party apps inside its process or transparently inspect every
app's private data. Android keeps each app sandboxed, and normal apps cannot
override another app's permissions or read its private database.

Supported Android mechanisms, in increasing sensitivity:

1. **Share target:** explicit, user-triggered capture of text, URLs, images or files.
2. **SAF / document picker:** explicit import and export of documents.
3. **Notification listener:** only for packages in a user-selected allowlist.
4. **Content providers or app APIs:** only where an app exposes a documented local
   provider or the user explicitly authorizes access.

Accessibility scraping and unrestricted background reading are out of scope.

## What SeBrain can and cannot control

SeBrain can:

- monitor data that the user explicitly shares into it;
- capture notifications from an explicit package allowlist;
- import data through user-approved document or provider APIs;
- show provenance, pause an integration, delete captured data and exclude it from
  a mini-brain;
- keep an encrypted local audit trail of what SeBrain itself received.

SeBrain cannot, without violating Android security boundaries:

- read arbitrary private data inside another app;
- change another app's permission grants;
- guarantee what another app sends to its own servers;
- inspect all device traffic transparently.

A `VpnService`-based traffic monitor would be a different product with major
privacy and security consequences. It would observe traffic from other apps,
often only as encrypted TLS streams, and would conflict with the project's
single-network-exit rule. It is not part of this design. Root access,
accessibility scraping and certificate interception are also rejected.

Android's system permission manager, Privacy Dashboard and per-app settings
remain authoritative for permissions and network behavior. SeBrain can link to
those settings, but must not pretend to replace them.

## Common capture envelope

Every integration must normalize into the same encrypted capture path and retain
provenance:

- source type and stable source identifier
- originating package or provider
- capture timestamp
- title, text, URL and optional attachment metadata
- user-selected mini-brain
- source-specific labels
- deduplication tag

The original payload remains encrypted. The graph exposes only indexed metadata,
entities and relationships permitted by the vault session.

## Graph model proposal

Do not change the database schema until this proposal is approved. The intended
model adds typed graph records alongside existing notes:

- `source`: an integration and its permission state
- `capture`: one normalized event from a source
- `entity`: a note, person, project, place, URL or application
- `relation`: typed edge with provenance and confidence
- `brain`: a saved filter over sources, tags, entities and time

Existing notes remain first-class entities. A capture may create or update a note,
but it must never silently overwrite user-authored text.

Graph edges should distinguish at least:

- `MENTIONS`
- `LINKS_TO`
- `FROM_SOURCE`
- `ABOUT`
- `PART_OF_BRAIN`
- `OCCURRED_AT`

Unknown targets are allowed as grey, unresolved graph nodes and can later resolve
to a note or entity.

## Mini-brain behavior

The user creates a brain such as `Work`, `Study` or `Personal`. A brain is not a
separate vault: it is a saved local query over the unified graph. Its view can
show notes, captures, entities, recent activity and AI answers restricted to that
brain.

The UI should provide:

- source and brain filters
- graph coloring by source or entity type
- per-source permission and pause controls
- provenance on every imported item
- "include in brain" and "exclude from brain" actions
- deletion that removes the capture and derived graph records

## Delivery order

1. Define and approve the typed capture and graph contracts.
2. Add source/provenance metadata without changing existing note behavior.
3. Finish explicit Share and Markdown source handling.
4. Implement notification capture behind a package allowlist.
5. Add brain filters and graph coloring.
6. Add additional app-specific providers only after their permission and privacy
   behavior is documented and tested.

The first slice of step 4 is now present: `NotificationCaptureService` is declared
in the ingest module, starts with a deny-all policy, accepts only exact package
names, extracts notification title/body text, and sends it through the existing
encrypted inbox. `IntegrationsScreen` lets the user select installed packages,
stores that allowlist with an Android Keystore AES-GCM key, and opens Android's
notification-listener settings. The initial allowlist is intentionally empty.
Granting Android access alone still captures nothing until a package is selected.

The package list now includes installed packages without requiring a launcher
activity, so system services and background-only apps can be selected as sources.
This uses Android's `QUERY_ALL_PACKAGES` visibility permission. It is appropriate
for the sideload/private distribution planned for development, but Google Play
distribution would require policy review because that permission is restricted.

The first graph integration is also implemented without a vault migration:
existing `capture.source` values are exposed as graph source filters, and each
graph node carries its source for future coloring and provenance display. The
filter is fail-safe: selecting a source excludes captures from other sources and
keeps unresolved link targets visibly marked as unresolved.

Saved mini-brains are encrypted filter definitions in `no_backup/mini-brains.bin`.
They contain only a user-visible name and source IDs; they never duplicate note text.
The Graph tab can select a saved brain, and the Integrate tab can create one from
one or more existing capture sources.

## Security invariants

- No integration may send plaintext note content to the network.
- No dependency may open sockets outside `TailnetGateway`.
- No notification source is enabled by default.
- Permissions are revocable and captures remain attributable to their source.
- Model answers must cite source records and respect the selected brain filter.
- Every integration has an explicit pause/revoke path and a visible source audit
  trail.
