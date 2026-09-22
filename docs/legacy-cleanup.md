# Legacy property clean-up

> **Read this if:** you are switching a prison on and its property list is full of people who left years
> ago, or you are changing anything under `service/cleanup/`, `domain/cleanup/` or the
> `prisonerpropertycleanup` queue.

## The problem it solves

Every prison's NOMIS property records were migrated into this service. NOMIS made it awkward to inactivate
a storage location, so most prisons carry a backlog of active containers for people who were released or
transferred out long ago. The moment a prison is switched on those read as **Due for return** and **Due
for transfer out** — thousands of rows at a large prison — and occupy storage locations that are physically
empty. Staff would have to close each one by hand.

The clean-up closes that backlog for one prison in one admin action: a preview of what a look-back window
would close, then a queued run that marks each container **returned** (owner released) or **transferred**
(owner at another prison), releasing its location and telling NOMIS.

## The rule

A candidate is a live container held at the prison and not yet due for disposal — the same set the summary
tiles count. Its owner is resolved from prisoner-search and the decision is `LegacyCleanupRule.decide`:

| Owner (prisoner-search) | Window test (`olderThanDays` = N, default 28) | Action |
| --- | --- | --- |
| `prisonId = OUT`, `lastMovementTypeCode = REL` (a death in custody arrives this way too) | `lastMovementDate <= today − N` | mark **returned**, dated to the release |
| at another real prison (not `TRN` / `OUT`) | date-left `<= today − N`, where date-left = `previousPrisonLeavingDate` if `previousPrisonId` is this prison, else `lastAdmissionDate` | mark **transferred** to where they are now, dated to the leaving date |
| here, in transit, unresolved, out but not by a release, or no movement date | — | left alone, with the reason reported in the preview |

`lastAdmissionDate` is the date they were admitted to the prison they are at now. Whatever route they
took, they left here no later than that, so it is a safe upper bound: property is only ever left alone for
*longer* than strictly necessary, never closed early. (`previousPrisonId` in prisoner-search is scoped to
the current term, so a release followed by a new term elsewhere also takes this path.)

Deliberately narrower than `OwnerLocation`, which decides what a container *reads as*. Someone still here
with a confirmed release date tomorrow reads as due for return and is not touched; someone in transit reads
as due for transfer out and is not touched.

## What a closure looks like

- The `RETURNED` / `TRANSFERRED` event is attributed to the system user `LEGACY_CLEANUP`
  (`PropertySystemUsers`) and stamped with `legacy_cleanup_job_id`, so the timeline and the UI's history
  page say the closure was automatic. Who asked for it, when, and with what window is on the job.
- `event_date` and `removal_date` are the release / leaving date, not the day the job ran.
- A clean-up **transfer** records the destination on the event for the history, but
  `PropertyContainer.receivingPrison()` ignores events carrying a job id, so `receiving_prison_id` stays
  null and the destination prison's "due for transfer in" list is not flooded with property that was never
  sent. If the destination later logs the old seal, the two records still reconcile as normal.
- One `prison-property.container.updated` event per closed container, `source: DPS`, published after that
  container's commit — the NOMIS sync-back inactivates the NOMIS record from it.

## How a run works

```
POST /active-agencies/{id}/cleanup  ──►  LegacyCleanupService.start
   plan(): candidates query ─► one bulk prisoner-search read ─► decide per owner
   tx { save job PENDING + one item per container }  afterCommit ─► SQS start message
                                                                        │
LegacyCleanupListener (one message at a time) ◄─────────────────────────┘
   LegacyCleanupProcessingService.process
     claim: SELECT … FOR UPDATE; PENDING → STARTED (or re-claim a stale STARTED)
     one bulk prisoner-search read for the pending items
     per item:
       tx1 REQUIRES_NEW  re-decide from the fresh record → writeService.legacyCleanupReturn / Transfer
       tx2               item status + running counter (+ last_activity_at)
       publish           the container's .updated event
     finish: recount from items, FINISHED, telemetry
```

Why it is shaped this way:

- **Snapshot at request time.** The items are what the admin was shown; the total never moves, and a
  redelivery or restart resumes from the unprocessed rows.
- **Re-decide at processing time.** A person who came back, or whose record moved on, is skipped — the
  snapshot is an intention, not a licence.
- **Two sequential transactions per item, never nested.** The write must be durable before it is
  announced, and the bookkeeping must not roll back with a failed write — it is what records the failure.
  If a pod dies between the two, the next delivery finds the container already carrying this job's closing
  event and settles the item as processed rather than closing it twice.
- **Publish failures are swallowed.** The publisher logs and tracks them; rethrowing would bounce the SQS
  message and re-run the whole job for one lost event. The item keeps a note.
- **One job in flight per prison**, enforced by a partial unique index as well as the service pre-check.
- **Redelivery.** The queue's visibility timeout is 30 minutes. A start message that arrives for a
  `STARTED` job with recent activity is a duplicate and is dropped (`LEGACY_CLEANUP_DUPLICATE_MESSAGE`); one
  for a job quiet for over 30 minutes is a resume.

## Endpoints

All under `/active-agencies`, role `ROLE_PRISONER_PROPERTY__ADMIN`:

| Method & path | Result |
| --- | --- |
| `GET /{agencyId}/cleanup/preview?olderThanDays=28` | `LegacyCleanupPreviewDto`: what would be returned / transferred, the tile-equivalent "now" figures, what is left alone and why, age bands |
| `POST /{agencyId}/cleanup` `{ "olderThanDays": 28 }` | 202 with the job; 409 if one is pending or running |
| `GET /{agencyId}/cleanup` | the prison's jobs, newest first, without items |
| `GET /cleanup/{jobId}` | one job with its items |

## Sizing

Preview: one grouped query plus one prisoner-search request per 1,000 owners — the cost of the summary
tiles. A run: roughly three statements and one SNS publish per container, 50–100 ms serially, so 1,000
containers is around a minute and a large prison a few minutes. The NOMIS write-back is queued downstream
and lags the run.
