# Prisoner Property API — Operational runbooks

One-off and recurring operational procedures for the deployed service. Day-to-day the service needs no
manual intervention; the steps here are for coordinated changes (migrations, backfills) that can't be
expressed as a Flyway migration alone.

**This is not a general operations reference** — there is nothing here about health checks, queues, alerts
or scaling. Each entry is a specific procedure, kept after the fact so the reasoning survives. For how the
service is put together see [architecture.md](architecture.md); for rolling a prison on or off see the
rollout console (`/active-agencies`, `ROLE_PRISONER_PROPERTY__ADMIN`) described in the
[README](../README.md#api-endpoints).

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

---

## Datahub ingestion: create the `digital_prison_reporting` user

**Ticket:** MAPB-763 · **Run once per environment**, after the RDS terraform in
[cloud-platform-environments](https://github.com/ministryofjustice/cloud-platform-environments) has applied
and the instance has been rebooted.

### Why

HMPPS Datahub ingests property data into the data mart via AWS DMS. It connects as its own database user
rather than the application's, and in production it reads from the **read replica** so its change-capture
reads never touch the operational database.

The [Datahub setup guide](https://dsdmoj.atlassian.net/wiki/spaces/DPR/pages/4461494352) still says to grant
`rds_superuser`. **Don't.** Follow
[Data Hub ingestion configuration amendments](https://dsdmoj.atlassian.net/wiki/spaces/moveandimprove/pages/6217335041)
instead: the Move and Improve team proved the ingestion works identically with read-only permissions, and
these credentials sit in AWS Secrets Manager where anyone with access to that instance can read them. A
superuser grant there would put every property table one leaked secret away from being dropped.

### Prerequisites

- The terraform in [cloud-platform-environments#44929](https://github.com/ministryofjustice/cloud-platform-environments/pull/44929)
  applied for the environment, **and the RDS instance rebooted** — `rds.logical_replication` and
  `shared_preload_libraries` are `pending-reboot`, so nothing below works until it has been.
- A password with no `;`, `+` or `%` — the Datahub ingestion rejects those characters.
- Run against the **primary**, not the replica. The role replicates to the replica automatically.

### Procedure

Connect as the master user (`./gradlew portForwardRDS`, or an SSM session) and run:

```sql
-- 1. Create the user
CREATE ROLE digital_prison_reporting WITH LOGIN PASSWORD '<generated>';

-- 2. Read-only, not superuser. pg_read_all_data covers every current and future table, so a new
--    migration cannot silently leave a table out of the ingestion.
GRANT pg_read_all_data TO digital_prison_reporting;
GRANT rds_replication  TO digital_prison_reporting;

-- 3. Lets Datahub create its own replication slots
GRANT CONNECT, CREATE ON DATABASE prisoner_property TO digital_prison_reporting;

-- 4. Required by the DMS process
CREATE EXTENSION IF NOT EXISTS pglogical;
```

### Verification

```sql
-- Should list pg_read_all_data and rds_replication, and NOT rds_superuser
SELECT r.rolname AS user_name, g.rolname AS granted_role
FROM pg_auth_members m
  JOIN pg_roles r ON m.member = r.oid
  JOIN pg_roles g ON m.roleid = g.oid
WHERE r.rolname = 'digital_prison_reporting';

-- Must return 'logical'. 'replica' means the instance has not been rebooted since the parameter
-- group changed - reboot it and check again.
SHOW wal_level;

-- pglogical should be listed
SELECT extname FROM pg_catalog.pg_extension;
```

If this user already exists with the old grants, revoke the superuser role rather than recreating it:

```sql
REVOKE rds_superuser FROM digital_prison_reporting;
```

### Afterwards

Share the credentials with Datahub following
[their instructions](https://dsdmoj.atlassian.net/wiki/spaces/DPR/pages/5350129665) — do not paste them into
Slack or a ticket. In production, give them the **read replica** endpoint
(`prisoner-property-rds-read-replica-output`), not the primary.

Datahub also needs the replication slot creating manually on a read replica, just before they start the DMS
task — that is their step, not ours, but it is the usual reason a production task fails first time.
