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

The clean-up closes that backlog for one prison in one admin action: a preview of what the retention rule
would close, then a queued run that marks each container **removed**, releasing its location and telling
NOMIS.

## The rule

The rule is the property retention policy agreed with policy colleagues (MAPB-854; Confluence "Prisoner
property - Legacy clean up considerations"). Property is kept for **13 months** after the person leaves, then
closed. The 13 months is fixed — there is no window to choose — and runs to a cut-off of today minus 13
months (`LegacyCleanupRule.cutoff`), inclusive. Policy's 12-month retention periods (unclaimed property,
absconds and escapes, property awaiting transfer) plus a month's margin.

A candidate is any live container held at the prison, including one whose disposal date has already
arisen. The container is checked first (`LegacyCleanupRule.containerExclusion`), then its owner, resolved
from prisoner-search (`LegacyCleanupRule.decide`):

| Container | Action |
| --- | --- |
| type `CONFISCATED` | left alone, however old — there may be an ongoing dispute or review |
| proposed disposal date still to come | left alone — a disposal decision is already pending |
| disposal date arisen, or none | decided by its owner, below |

| Owner (prisoner-search) | Test | Reported as |
| --- | --- | --- |
| `prisonId = OUT`, `lastMovementTypeCode = REL` | `lastMovementDate <= cut-off` | released; **died** when the reason is `DEC`; **escaped** when it is `ESCP`, `UAL` or `UAL_ECL` |
| at another real prison (not `TRN` / `OUT`) | date-left `<= cut-off`, where date-left = `previousPrisonLeavingDate` if `previousPrisonId` is this prison, else `lastAdmissionDate` | transferred |
| here, in transit, unresolved, out but not by a release, or no movement date | — | left alone, with the reason reported in the preview |

Whatever the reason, the container is marked **removed**: once the person has been gone 13 months that is
all that can be said for certain about what happened to the property. The reason is reported in the preview
and the destination prison kept on the job item; it is not written to the container.

NOMIS records deaths, escapes and absconds as release movements (type `REL`), told apart only by the
movement reason code, so prisoner-search shows them exactly as it shows a release.

`lastAdmissionDate` is the date they were admitted to the prison they are at now. Whatever route they
took, they left here no later than that, so it is a safe upper bound: property is only ever left alone for
*longer* than strictly necessary, never closed early. (`previousPrisonId` in prisoner-search is scoped to
the current term, so a release followed by a new term elsewhere also takes this path.)

Deliberately narrower than `OwnerLocation`, which decides what a container *reads as*. Someone still here
— however long ago they arrived — is never touched, nor is someone with a confirmed release date tomorrow;
someone in transit reads as due for transfer out and is not touched.

## What a closure looks like

- A `REMOVED` event, outcome `REMOVED`, attributed to the system user `LEGACY_CLEANUP`
  (`PropertySystemUsers`) and stamped with `legacy_cleanup_job_id`. Who asked for it and when is on the job.
- **The job id is the flag** that tells a clean-up removal apart from a container NOMIS marked inactive
  (the only other source of `REMOVED`). It is exposed as `legacyCleanup` on the container events and the
  prisoner timeline, so the UI words the entry as "Legacy property record archived following DPS migration",
  and `PropertyContainer.removedByLegacyCleanup()` reads it.
- **The NOMIS sync does not reverse it.** A NOMIS-sourced `REMOVED` is reactivated when NOMIS sends the
  container as active again; a clean-up removal is not, because NOMIS may still hold long-gone property as
  active. Its NOMIS location, disposal-due event and removal date are not applied either (seal, type and
  disposal-date corrections still land on the container). Each time NOMIS sends one as active,
  `prison-property-sync-legacy-cleanup-retained` is tracked.
- `event_date` and `removal_date` are the release / leaving date, not the day the job ran; the event time is
  the run. Removing the container frees its storage location; the earlier events keep where it was, so the
  timeline shows its last known location.
- One `prison-property.container.updated` event per closed container, `source: DPS`, published after that
  container's commit — the NOMIS sync-back inactivates the NOMIS record from it. NOMIS cannot tell a
  removal from a return or a transfer, so the outcome makes no difference there. Syscon have confirmed they
  can take the volume a large prison's run produces.

Jobs run before the 13-month rule marked containers `RETURNED` or `TRANSFERRED` (with the same job id) and
recorded a window in `older_than_days`. Those rows are left as they are; `receivingPrison()` still keeps
an old clean-up transfer from being advertised at the destination, and a pending `RETURN` / `TRANSFER` item
is skipped rather than closed.

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
       tx1 REQUIRES_NEW  re-decide from the fresh record → writeService.legacyCleanupRemove
                         (which re-checks the container: same prison, not confiscated, no disposal date to come)
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

All under `/active-agencies`, role `ROLE_PRISONER_PROPERTY__ADMIN`. A window sent by an older client
(`?olderThanDays=` or a body) is ignored.

| Method & path | Result |
| --- | --- |
| `GET /{agencyId}/cleanup/preview` | `LegacyCleanupPreviewDto`: the cut-off, what would be removed (in total and by why the person left), the tile-equivalent "now" figures, what is left alone and why, age bands (13 months to 2 years, 2 to 5 years, over 5 years) |
| `POST /{agencyId}/cleanup` (no body) | 202 with the job; 409 if one is pending or running |
| `GET /{agencyId}/cleanup` | the prison's jobs, newest first, without items |
| `GET /cleanup/{jobId}` | one job with its items |

## Sizing

Preview: one grouped query plus one prisoner-search request per 1,000 owners — the cost of the summary
tiles. A run: roughly three statements and one SNS publish per container, 50–100 ms serially, so 1,000
containers is around a minute and a large prison a few minutes. The NOMIS write-back is queued downstream
and lags the run.
