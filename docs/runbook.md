# Prisoner Property API — Operational runbooks

One-off and recurring operational procedures for the deployed service. Day-to-day the service needs no
manual intervention; the steps here are for coordinated changes (migrations, backfills) that can't be
expressed as a Flyway migration alone.

**Related docs:** [README](../README.md) · [Architecture](architecture.md) ·
[Technical implementation](technical-implementation.md)

---

## Backfill: legacy `DISPOSED`-from-NOMIS-inactive → `REMOVED`

**Ticket:** MAPB-674 · **Run once**, immediately after the MAPB-674 release is live in an environment.

### Why

Before MAPB-674, a NOMIS container marked inactive (`ACTIVE_FLAG='N'`) was mapped to the
`DISPOSED` removal outcome. That overstated it — inactive means "gone from the establishment, reason
unknown", which is not necessarily a disposal. MAPB-674 introduces the reversible `REMOVED` outcome and
remaps the sync path to use it. **Existing rows are not touched by the deploy** — there is deliberately no
Flyway data migration, because SQL cannot distinguish a NOMIS-inactive `DISPOSED` from a genuine DPS staff
disposal (both are `event_type = 'DISPOSED'` with a username in `event_user_id`, and there is no provenance
column). Re-running the idempotent NOMIS sync does make that distinction for free: it only touches
NOMIS-owned containers and re-derives each from `active=false`.

### Prerequisites

1. **Deploy the API and UI together.** The UI depends on the new `REMOVED` / `REACTIVATED` enum values;
   deploying the API alone would surface statuses the UI can't render.
2. Confirm the release is healthy in the target environment before backfilling.

### Procedure — full re-migration (preferred)

Trigger a **full NOMIS → DPS property re-migration** via
[`hmpps-prisoner-from-nomis-migration`](https://github.com/ministryofjustice/hmpps-prisoner-from-nomis-migration)
(its migration-admin trigger). This drives our `POST /migrate` endpoint, which:

- is **idempotent** — re-mapping every `active=false` container from `DISPOSED` to `REMOVED`;
- **raises no domain events** (see `SyncResult` in [technical-implementation.md](technical-implementation.md)),
  so the backfill does **not** write anything back to NOMIS — correct, since NOMIS already holds
  `ACTIVE_FLAG='N'` and this only corrects the DPS-side representation.

Re-create the containers from a cleared state (reset the migration's mapping and clear the DPS property
tables, then migrate fresh) so each inactive container is rebuilt through the `create()` path and ends with
a **single** `REMOVED` event and a clean history.

> **Why not an incremental sync-replay?** Replaying inactive snapshots through the ongoing `sync` path hits
> `update()`, where a legacy container's outcome is `DISPOSED` (not `REMOVED`), so `wasRemoved` is false and
> the service **appends a new `REMOVED` event while leaving the old `DISPOSED` event in place**
> (`SyncPropertyContainerService.kt`). The container's current status ends correct (`REMOVED` — the outcome
> takes precedence), but its **timeline would show both** "disposed" and "marked as removed". Prefer the
> clean re-migration above; if an incremental replay is unavoidable, plan a follow-up to remove the orphaned
> `DISPOSED` events.

### Verification

After the run, no NOMIS-originated `DISPOSED` rows should remain (only genuine DPS staff disposals, if any):

```sql
-- Containers still carrying the DISPOSED outcome
SELECT id, prisoner_number, prison_id, current_status
FROM property_container
WHERE removal_outcome = 'DISPOSED';

-- Denormalised mirror should match (no stale DISPOSED status)
SELECT count(*) FROM property_container WHERE current_status = 'DISPOSED';

-- Orphaned disposal events left behind (should be 0 after a clean re-migration)
SELECT count(*) FROM property_event WHERE event_type = 'DISPOSED';
```

Spot-check a previously-inactive prisoner in the UI: the container shows the grey **Removed** tag, and the
timeline reads "… marked as removed from the establishment" (not "disposed"). Reactivating it in NOMIS
(`ACTIVE_FLAG='Y'`) on the next sync should clear the outcome and add a **Reactivated** timeline entry.
