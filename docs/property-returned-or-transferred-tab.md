# Roadmap: "Property returned or transferred" tab

> **Status: roadmap / backlog — not in alpha scope.** This document only records the intent and the build
> options so the work can be picked up later. There is nothing to build yet.

## Purpose

The prisoner property page today has two tabs: **Property** (the person's active containers) and
**Property history** (the interleaved timeline). Neither lists, as a plain table, the containers that have
*left* active storage — the ones that were **returned** to the person, **disposed** of, or **transferred
out**. Those events appear in the timeline but there is no at-a-glance list of them.

This roadmap item adds a **third tab**, "Property returned or transferred", showing exactly that set: every
container for the prisoner that is no longer in active storage.

### Design reference

From the design mock-up the tab is a sortable table, one row per removed container, with columns:

| Column                    | Notes                                                                 |
| ------------------------- | --------------------------------------------------------------------- |
| Seal number               | The container's last seal number.                                     |
| Prisoner establishment    | Where the container was held.                                         |
| Property type             | Standard / Valuables / Confiscated / Excess etc.                      |
| Last known storage location | The location it sat in before it left storage ("No date set" style). |
| Disposal date             | Where relevant (disposals); blank otherwise.                          |
| Last updated              | When the removal was recorded.                                        |
| Status (tag)              | **Returned** / **Disposed** / **Transferred out**.                    |

The status tag reuses the person-view timeline palette (Returned = green, Disposed = red, Transferred out =
grey), matching `server/utils/prisonerTimeline.ts` in the UI.

## What counts as "removed"

A container has left active storage when it has a removal outcome — i.e. `currentStatus()` is one of:

- `RETURNED` — returned to the person
- `DISPOSED` — destroyed
- `TRANSFER` — transferred out to another establishment
- `CREATED_IN_ERROR` — removed as a mistake (decide whether to surface these)
- `COMBINED` — merged into another container (probably **excluded** — it did not really leave, it became
  part of another live container)

These are exactly the containers the current property tab and the establishment list **hide by default**
(see `PropertyContainer.isRemoved()` and the `includeRemoved` handling).

## Build options

### API options

1. **Reuse the existing prisoner endpoint with a status filter (lowest cost).**
   `GET /property-containers/prisoner/{prisonerNumber}` already accepts a repeatable `status` query
   parameter and returns `PrisonerPropertyContainerDto`s. Passing
   `status=RETURNED&status=DISPOSED&status=TRANSFER` (plus `CREATED_IN_ERROR` if wanted) returns the removed
   set directly — no API change needed. The DTO already carries seal number, prison, container type,
   location description, proposed disposal date and removal fields.
   - *Caveat:* confirm the DTO exposes the **removal date** and the **last-known location** for a removed
     container. `PropertyContainer.currentLocation()` returns `null` once removed, so the "Last known
     storage location" column may need the pre-removal location — either surfaced from the latest
     location-bearing event, or a new field on the DTO.

2. **A dedicated "removed history" read** if the columns/sorting diverge from the active list — e.g. server
   -side sorting by disposal date or last-updated, or a shape that always includes the last-known location
   and removal date. Model it on `getByPrisonerNumber` in `PropertyContainerService`.

### UI options

- Add a third tab to the prisoner property page alongside "Property" and "Property history", rendering the
  removed set as a GOV.UK sortable table, reusing the timeline status-tag styling.
- Sorting: the design shows sortable "Prisoner establishment", "Disposal date" and "Last updated" columns —
  decide client-side vs server-side sorting.

## Open questions to resolve before building

- Does "Last known storage location" need the **pre-removal** location captured/exposed (it is currently
  cleared on removal)?
- Is a **removal/disposal date** available on the container or only derivable from the events?
- Are `CREATED_IN_ERROR` and `COMBINED` containers in or out of this tab?
- Is this scoped to the prisoner's own history only, or should it also work from the establishment view?
