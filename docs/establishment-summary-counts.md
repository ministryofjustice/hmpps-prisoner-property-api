# Establishment summary counts

How the five whole-prison tiles on the establishment home page are calculated and what triggers each
number to change.

```
Available storage spaces on-site        778
Property containers stored on-site       34
Property containers due to transfer out   0
Property containers due to be returned    1
Property containers due to be disposed    0
```

## The endpoint

`GET /prison/{prisonId}/summary` &rarr; `PrisonPropertySummaryDto` (role `ROLE_PRISONER_PROPERTY__RO`).

The UI calls this once when the establishment dashboard loads. The counts are **whole-prison totals** —
independent of any list paging, filtering or search on the property-list screen. All numbers are for
`prisonId`: the container's *current holding prison*, filtered on the denormalised `prison_id` column.

Everything is assembled in `PropertyContainerService.getPrisonPropertySummary`
(`service/PropertyContainerService.kt`), from three repository queries plus one live call to
locations-inside-prison-api and one bulk call to prisoner-search. The queries read **denormalised
current-state columns** — not the event stream — so no container events are loaded to produce the tiles.

```kotlin
// One grouped query supplies both the per-bucket counts and the prisoners whose location decides them.
val activeCounts = repository.countActiveByPrisonerAndStatus(prisonId, today)
val overlay      = overlayFactory.overlayFor(prisonId, activeCounts.map { it.prisonerNumber }.distinct()).overlay

val countsByLocation = repository.countContainersByLocation(prisonId)    // group by current_internal_location_id
val storedOnSite     = countsByLocation.values.sum()
val availableStorageSpaces = locationsClient.getPropertyLocations(prisonId).sumOf { location ->
  ((location.capacity ?: 0) - (countsByLocation[location.id] ?: 0)).coerceAtLeast(0)
}

fun tile(status: ContainerStatus) = activeCounts
  .filter { (overlay?.effectiveStatusOf(it.prisonerNumber, it.status) ?: it.status) == status }
  .sumOf { it.count }.toInt()

PrisonPropertySummaryDto(
  availableStorageSpaces = availableStorageSpaces,
  storedOnSite           = storedOnSite,
  dueToTransferOut       = tile(DUE_FOR_TRANSFER_OUT),
  dueToBeReturned        = tile(DUE_FOR_RETURN),
  dueToBeDisposed        = repository.countDueForDisposal(prisonId, today).toInt(),
)
```

### Why the counts are not a pure SQL aggregate

The two status tiles count containers by the status they are **shown** as, and for a container still in
storage that depends on where its owner now is — which lives in prisoner-search, not the property database.
See `ContainerStatusResolver` and `OwnerLocation` for the rule, and `StatusOverlay` for how it is applied to
a whole prison at once.

This matters because the tiles and the establishment list's status filter must agree: a tile that counts
rows its own filter cannot return is worse than no tile. The filter binds the same classification as
prisoner-number sets (`PrisonPropertyFilter.statusOverlay`), so clicking a tile reaches exactly the rows
behind it.

Cost is bounded by the number of *prisoners* holding live property at the prison, not the container count,
and the lookup is chunked. If prisoner-search is unavailable the lookup degrades to no prisoners, every
surface falls back to the persisted `current_status` together, and the page stays self-consistent rather
than failing.

## Where the current-state columns come from

Four of the five tiles are answered from denormalised mirrors on `property_container` — between them
they read just two columns:

| Column | Mirrors | Refreshed by |
| --- | --- | --- |
| `current_status` | `PropertyContainer.baseStatus()` | `refreshDerivedState()` on every write |
| `current_internal_location_id` | `PropertyContainer.currentLocation()` | `refreshDerivedState()` on every write |

`current_status` is the container's *own* record of its status. For a container still in storage that is
the starting point rather than the answer — the owner's location can override it (see tiles 3 and 4).

The domain is event-sourced: a container's status and location are *derived* from its latest relevant
`PropertyEvent`. Rather than re-derive per read, every write path (create / update / dispose / remove /
combine / move / transfer, plus NOMIS sync) calls `refreshDerivedState()` after appending its events, so
these columns always reflect the event-derived state. The tiles therefore change **the moment a write
commits an event** — there is no separate scheduled job or projection to rebuild.

The one exception is disposal (see below), which is time-based and so is **not** denormalised — it is
recomputed from a date on every read.

Only **live** containers count: the status and disposal queries filter `removal_outcome is null` — a
container that has been disposed, returned, transferred out, combined or removed is no longer live stock.

There is no `archived` column. There was one; `V14__drop_archived.sql` dropped it under MAPB-674 in favour
of the reversible `REMOVED` removal outcome, so removal is the single mechanism for "no longer here".

## The five tiles

### 1. Available storage spaces on-site (`availableStorageSpaces`)

Remaining room for property across the prison's storage locations.

- Fetch the prison's property locations **live** from locations-inside-prison-api
  (`locationsClient.getPropertyLocations(prisonId)`), each carrying a `capacity`.
- For each location: `capacity − (containers it currently holds)`, floored at 0 with `coerceAtLeast(0)`
  so an over-capacity location contributes 0, never a negative.
- Sum across all locations.

"Containers it holds" is `countContainersByLocation` — grouped by `current_internal_location_id`, counting
only containers **physically present in an internal box**: the query filters on `prison_id` and
`current_internal_location_id is not null`, and nothing else.

That excludes offsite-at-Branston property directly, and removed property only *indirectly* — removal
clears the location via `refreshDerivedState()`, so there is no `removal_outcome` clause here. Worth
knowing: the exclusion depends on that refresh being called, so a write path that skips it inflates this
count. (It returns a `List<LocationContainerCount>`, which the service associates into a map.)

**Triggers a change:** a container moving into / out of an internal box (create, move, transfer, remove),
or an admin editing a location's capacity in locations-inside-prison-api. This call is deliberately **not
cached** (MAPB-656) so a capacity edit or a transfer shows up immediately across all pods.

### 2. Property containers stored on-site (`storedOnSite`)

`countsByLocation.values.sum()` — the total of the same per-location counts, i.e. every active container
physically sitting in an internal location at this establishment. Excludes containers held offsite at
Branston (null internal location id) and removed containers (whose location is cleared on removal).

**Triggers a change:** any event that changes where a container physically is — a new sealed container
added to a box (`CREATED_SEALED`), a move to Branston or a transfer out (both clear the internal location),
or a removal.

### 3. Property containers due to transfer out (`dueToTransferOut`)

Containers still in storage here whose **owner is in another establishment**, or in transit to one — their
property needs to follow them.

Usually the container also carries a **`PRISONER_RECEIVED`** event recording the move, but the owner's
location is what decides: plenty of property (particularly NOMIS-migrated) has no such event, and before
MAPB-725 it was miscounted as merely stored. Conversely a stale `PRISONER_RECEIVED` on property whose owner
has since come *back* no longer counts here. The count drops when the container is transferred out
(`TRANSFERRED`, which removes it from live stock) or otherwise removed.

### 4. Property containers due to be returned (`dueToBeReturned`)

Containers still in storage here whose owner has been **released**, or is being released within a day
(their confirmed release date only — the sentence-calculated one can move, so it is deliberately not used).

Property flagged by a **`PRISONER_RELEASED`** or **`DIED_IN_CUSTODY`** event also counts and *keeps*
counting even while prisoner-search still shows the person as present: the two feeds come from the same
NOMIS movements and can lag each other, and quietly reverting such property to "stored" is exactly the miss
this tile exists to prevent. The count drops when the container is returned (`RETURNED`) or otherwise
removed, or if the person turns out to be back in custody elsewhere (their property then needs to follow
them, so it moves to *due to transfer out*).

### 5. Property containers due to be disposed (`dueToBeDisposed`)

`countDueForDisposal(prisonId, LocalDate.now())` — active containers where
`proposed_disposal_date is not null and proposed_disposal_date <= today`.

Disposal is **time-based**, so unlike the other statuses it is **not** denormalised into `current_status`:
the number can change with the passing of a day even if no event is written. A container with a proposed
disposal date in the future contributes nothing until that date arrives; once today reaches the proposed
date it counts, until it is actually disposed (`DISPOSED`, which sets a removal outcome and drops it from
the query). This mirrors `PropertyContainer.isDisposalDue()`, which drives the same `DISPOSAL_REQUIRED`
overlay wherever a container's status is shown.

Disposal **takes precedence** over the two owner-driven statuses, so a container past its disposal date is
counted here and *only* here. `countActiveByPrisonerAndStatus` excludes it for that reason; before MAPB-726
it was counted twice, in this tile and in one of the other two.

## Summary of triggers

| Tile | Source | Changes when |
| --- | --- | --- |
| Available storage spaces | live locations capacity − internal counts | container enters/leaves an internal box; location capacity edited upstream |
| Stored on-site | count by internal location | `CREATED_SEALED`, `MOVED`, `TRANSFERRED`, any removal |
| Due to transfer out | owner is in another establishment or in transit | the owner moves prison; `TRANSFERRED`/removal clears it |
| Due to be returned | owner released, releasing within a day, or property flagged `PRISONER_RELEASED` / `DIED_IN_CUSTODY` | the owner is released or the release date arrives; `RETURNED`/removal clears it |
| Due to be disposed | `proposed_disposal_date <= today` | proposed disposal date arrives (time-based); `DISPOSED` clears it |

The three **status** buckets — due to transfer out, due to be returned, due to be disposed — are mutually
exclusive, so a container appears in at most one of them, and each matches the establishment list filter of
the same name.

"Stored on-site" is not part of that set and **overlaps them on purpose**: it answers a different question,
*what is physically in our boxes*. A container sitting in a box whose owner has moved prison counts in both
"stored on-site" and "due to transfer out" — correctly, since it is both. The tiles are therefore not a
partition and will not sum to the prison's active stock.

The location-driven tiles update the instant the triggering event's transaction commits (via
`refreshDerivedState()`). The two owner-driven tiles additionally change when the *person* moves, with no
write against the property at all — property left behind at another prison is reclassified as soon as
prisoner-search reflects the movement. The disposal tile rolls forward each day as `today` advances; the
storage tile also reflects live capacity changes made in locations-inside-prison-api.
