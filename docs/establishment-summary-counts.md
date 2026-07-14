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
locations-inside-prison-api. The queries read **denormalised current-state columns** — not the event
stream — so no container events are loaded to produce the tiles.

```kotlin
val counts           = repository.countContainersByStatus(prisonId)      // group by current_status
val countsByLocation = repository.countContainersByLocation(prisonId)    // group by current_internal_location_id
val storedOnSite     = countsByLocation.values.sum()
val availableStorageSpaces = locationsClient.getPropertyLocations(prisonId).sumOf { location ->
  ((location.capacity ?: 0) - (countsByLocation[location.id] ?: 0)).coerceAtLeast(0)
}
PrisonPropertySummaryDto(
  availableStorageSpaces = availableStorageSpaces,
  storedOnSite           = storedOnSite,
  dueToTransferOut       = counts.count(DUE_FOR_TRANSFER_OUT),
  dueToBeReturned        = counts.count(DUE_FOR_RETURN),
  dueToBeDisposed        = repository.countDueForDisposal(prisonId, LocalDate.now()).toInt(),
)
```

## Where the current-state columns come from

Four of the five tiles read denormalised mirrors on `property_container`:

| Column | Mirrors | Refreshed by |
| --- | --- | --- |
| `current_status` | `PropertyContainer.baseStatus()` | `refreshDerivedState()` on every write |
| `current_internal_location_id` | `PropertyContainer.currentLocation()` | `refreshDerivedState()` on every write |

The domain is event-sourced: a container's status and location are *derived* from its latest relevant
`PropertyEvent`. Rather than re-derive per read, every write path (create / update / dispose / remove /
combine / move / transfer, plus NOMIS sync) calls `refreshDerivedState()` after appending its events, so
these columns always reflect the event-derived state. The tiles therefore change **the moment a write
commits an event** — there is no separate scheduled job or projection to rebuild.

The one exception is disposal (see below), which is time-based and so is **not** denormalised — it is
recomputed from a date on every read.

Only **active** containers count: every query filters `archived = false`, and the status/disposal
queries also filter `removal_outcome is null` (a container that has been disposed, returned, transferred
out or combined is no longer live stock).

## The five tiles

### 1. Available storage spaces on-site (`availableStorageSpaces`)

Remaining room for property across the prison's storage locations.

- Fetch the prison's property locations **live** from locations-inside-prison-api
  (`locationsClient.getPropertyLocations(prisonId)`), each carrying a `capacity`.
- For each location: `capacity − (containers it currently holds)`, floored at 0 with `coerceAtLeast(0)`
  so an over-capacity location contributes 0, never a negative.
- Sum across all locations.

"Containers it holds" is `countContainersByLocation` — grouped by `current_internal_location_id`, counting
only containers **physically present in an internal box** (id not null, so offsite-at-Branston and removed
containers are excluded), `archived = false`.

**Triggers a change:** a container moving into / out of an internal box (create, move, transfer, remove),
or an admin editing a location's capacity in locations-inside-prison-api. This call is deliberately **not
cached** (MAPB-656) so a capacity edit or a transfer shows up immediately across all pods.

### 2. Property containers stored on-site (`storedOnSite`)

`countsByLocation.values.sum()` — the total of the same per-location counts, i.e. every active container
physically sitting in an internal location at this establishment. Excludes containers held offsite at
Branston (null internal location id), removed containers, and archived ones.

**Triggers a change:** any event that changes where a container physically is — a new sealed container
added to a box (`CREATED_SEALED`), a move to Branston or a transfer out (both clear the internal location),
or a removal.

### 3. Property containers due to transfer out (`dueToTransferOut`)

`countContainersByStatus` grouped by `current_status`, taking the `DUE_FOR_TRANSFER_OUT` bucket.

A container reaches this status via a **`PRISONER_RECEIVED`** event — the person has been received at a
*new* establishment while their property is still held here, so it needs sending on. The count drops when
that container is transferred out (`TRANSFERRED`, which removes it from live stock) or otherwise removed.

### 4. Property containers due to be returned (`dueToBeReturned`)

Same `countContainersByStatus`, taking the `DUE_FOR_RETURN` bucket.

Reached via a **`PRISONER_RELEASED`** or **`DIED_IN_CUSTODY`** event (both map to `DUE_FOR_RETURN` in
`PropertyEventType`) — the person has left custody, so their property should be returned. The count drops
when the container is returned (`RETURNED`) or otherwise removed.

> Note: the `@Operation` description on the endpoint still says "'Due to be returned' is always 0 for now".
> That is stale — the code counts `DUE_FOR_RETURN` as described here.

### 5. Property containers due to be disposed (`dueToBeDisposed`)

`countDueForDisposal(prisonId, LocalDate.now())` — active containers where
`proposed_disposal_date is not null and proposed_disposal_date <= today`.

Disposal is **time-based**, so unlike the other statuses it is **not** denormalised into `current_status`:
the number can change with the passing of a day even if no event is written. A container with a proposed
disposal date in the future contributes nothing until that date arrives; once today reaches the proposed
date it counts, until it is actually disposed (`DISPOSED`, which sets a removal outcome and drops it from
the query). This mirrors `PropertyContainer.isDisposalDue()`, which drives the same `DISPOSAL_REQUIRED`
overlay wherever a container's status is shown.

## Summary of triggers

| Tile | Source | Changes when |
| --- | --- | --- |
| Available storage spaces | live locations capacity − internal counts | container enters/leaves an internal box; location capacity edited upstream |
| Stored on-site | count by internal location | `CREATED_SEALED`, `MOVED`, `TRANSFERRED`, any removal |
| Due to transfer out | `current_status = DUE_FOR_TRANSFER_OUT` | `PRISONER_RECEIVED` sets it; `TRANSFERRED`/removal clears it |
| Due to be returned | `current_status = DUE_FOR_RETURN` | `PRISONER_RELEASED` / `DIED_IN_CUSTODY` set it; `RETURNED`/removal clears it |
| Due to be disposed | `proposed_disposal_date <= today` | proposed disposal date arrives (time-based); `DISPOSED` clears it |

The status-driven tiles update the instant the triggering event's transaction commits (via
`refreshDerivedState()`); the disposal tile also rolls forward each day as `today` advances; the storage
tile additionally reflects live capacity changes made in locations-inside-prison-api.
