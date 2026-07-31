# "Property returned or transferred" tab

> **Status: built and live** (MAPB-677). This document was written as a roadmap while the tab was still a
> design; it now records what was built and, more usefully, how each of the open questions was answered.
> The answers are the interesting part — several went the opposite way to the original design.

## Purpose

The prisoner property page has three tabs: **Property** (the person's active containers), **Property
history** (the interleaved timeline), and this one — a plain table of the containers that have *left*
active storage. Those removals appear in the timeline, but the timeline is not the place to answer "what
happened to this person's property".

## What was built

`GET /prisoner/:prisonerNumber/returned` in the UI, rendering `pages/prisonerPropertyReturned.njk` from
`buildReturnedOrTransferredView` in `server/utils/personProperty.ts`. **No API change was needed.**

The shipped table has five columns, sortable, newest removal first:

| Column | Notes |
| --- | --- |
| Seal number | The container's last seal number. |
| Establishment | Where the container was held. |
| Property type | Standard / Valuables / Confiscated / Excess etc. |
| Removal date | When it left storage. |
| Status (tag) | **Removed** / **Returned** / **Disposed** / **Transferred out**. |

## What counts as removed

Four outcomes are shown: `REMOVED`, `RETURNED`, `DISPOSED`, `TRANSFERRED`.

`COMBINED` and `CREATED_IN_ERROR` are **excluded**, and the reasoning is recorded next to the constant in
`personProperty.ts`: combined property did not leave, it became part of another container that is still
tracked; created-in-error property was never really there. Neither is something the person "had returned or
transferred out", which is what the tab claims to list.

Note `REMOVED` — it did not exist when this was designed. It arrived with MAPB-674 as the reversible
removal outcome that replaced the `archived` flag, and it belongs here.

## How the open questions were answered

The original document ended with four open questions. All four are settled, and three went against the
design mock-up:

- **"Does 'last known storage location' need the pre-removal location captured?"** — answered by **dropping
  the column**. Rather than surface a location the container no longer occupies, the design question was
  reframed: staff want to know *when* it left, not which shelf it was on beforehand. "Last known storage
  location", "Disposal date" and "Last updated" all collapsed into a single **Removal date**, which is why
  the shipped table has five columns and the design reference had seven.
- **"Is a removal date available?"** — yes. `removalDate` is on the container and exposed on
  `PrisonerPropertyContainerDto`, so no derivation from events was needed.
- **"Are `CREATED_IN_ERROR` and `COMBINED` in or out?"** — out, as above.
- **"Prisoner-only, or the establishment view too?"** — prisoner-only. Nothing equivalent was added to the
  establishment list; the `includeRemoved` filter there covers that need.

## How the API question was answered

Option 1 (reuse the existing prisoner endpoint) was chosen, and taken further than proposed: the UI does
not pass a status filter at all. It reuses the **unfiltered** `getPropertyForPrisoner` call that already
feeds the page header, and filters client-side. One request serves the whole page, and the dedicated
"removed history" read in option 2 was never built.
