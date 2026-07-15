# Property history timeline — missing events: architecture & scoping

> ## ⚠️ Superseded — historical record only
>
> This was a decision aid written *before* the timeline work. Most of what it proposes has since been
> built, and **some of its current-state claims are now false** — it says there is no prison-api /
> movements client (§4.3), but `client/PrisonApiClient.kt` exists and powers the timeline's movement
> items. Do not read this as a description of how things work today.
>
> It is kept because it still explains **why** the timeline was built the way it was, and records the
> options that were rejected. For current state see [architecture.md](architecture.md) and
> [technical-implementation.md](technical-implementation.md).

**Status:** Draft for discussion · **Scope:** Task: property history/timeline work
· **Audience:** property service engineers + designers making a build/no-build decision

This document exists to lay out the problems behind the remaining history events and
the realistic ways forward — *before* we commit to building anything. It is a decision aid, not a
signed-off design. Where it recommends an approach it says so, but the open questions in
[§7](#7-open-questions--decisions-needed) need answers first.

---

## 1. Purpose & principles

The property **history/timeline** is intended to be a **global, cross-establishment, enduring
record**: it should read correctly regardless of where the person or their property is now, and
even for establishments still using NOMIS (not DPS) for property. History may eventually be the
*only* visible record of a container (e.g. after the person is released), so — for anything we
choose to store — an event must carry enough detail to stand alone and must not depend on the live
container row still existing.

The  spec ("History items and when they are triggered") introduces events that the current
model **either cannot represent or cannot source**. They fall into two distinct problem classes,
addressed separately below because their cost and risk are very different:

- **Problem A — missing *inbound* events** (§3): "died in custody" and "scheduled for release".
  These are conceptually container-attached (they behave like the existing "due for return"
  handling), so they are architecturally cheap — but we do not currently *receive* them.
- **Problem B — *non-container* events** (§4): "person admitted" (with initial-vs-transfer and
  DPS-vs-NOMIS variants) and "DPS first-used at an establishment". These are person- or
  establishment-level, and the current data model has nowhere to put them.

---

## 2. The current model (recap)

A short recap so the constraints below are concrete. All paths are in the API repo unless noted.

- **Events are container-owned.** `PropertyEvent` (`src/main/kotlin/.../domain/PropertyEvent.kt`)
  has a non-null `@ManyToOne` to `PropertyContainer` (`property_container_id UUID NOT NULL
  REFERENCES property_container(id)` — see `V1__create_property_container_and_event.sql`). There is
  **no** event table that exists independently of a container, and `property_event` has no
  `prisoner_number` / `prison_id` column of its own.
- **Event types.** `domain/PropertyEventType.kt` — 12 values: `CREATED_SEALED`, `SEAL_CHANGED`,
  `CONTAINER_TYPE_CHANGE`, `MOVED`, `PRISONER_RECEIVED`, `PRISONER_RELEASED`, `TRANSFERRED`,
  `RETURNED`, `DISPOSAL_REQUIRED`, `DISPOSED`, `COMBINED`, `CREATED_IN_ERROR`. Each carries a
  derived `ContainerStatus`.
- **The prisoner timeline (MAPB-568).** `PropertyContainerService.getPrisonerTimeline(prisonerNumber)`
  loads the prisoner's non-archived containers and:
  1. flattens every container's events into `CONTAINER_EVENT` timeline items, and
  2. builds a **synthetic** `PRISONER_MOVEMENT` ("arrived at …") item by taking each container's
     `PRISONER_RECEIVED` events and de-duplicating by `(toPrisonId, eventDateTime)`.

  Two consequences matter here: **(a)** a prisoner with **no property returns an empty timeline**
  (`if (containers.isEmpty()) return emptyList()`), and **(b)** the "arrived at establishment" item
  is *reverse-engineered* from container events, not a stored fact — it only appears if the person
  had property to flag at the time.
- **Inbound events.** The `prisonerproperty` SQS queue subscribes to **exactly two** domain event
  types — `prison-offender-events.prisoner.received` and `prison-offender-events.prisoner.released`
  — declared in both `src/main/resources/application.yml` (`hmpps.sqs…subscribeFilter`) and the
  three Terraform files
  `cloud-platform-environments/.../hmpps-locations-inside-prison-{dev,preprod,prod}/resources/prisoner-property-sqs.tf`.
- **The listener.** `event/PrisonerEventListener.kt` dispatches on `eventType`. For `released` it
  reads `additionalInformation.reason` and **only proceeds when `reason == "RELEASED"`** (the comment
  notes this "also covers death in custody"); temporary movements/transfers are ignored. For
  `received` it does **not** read `reason` at all. The repo does **not** enumerate the upstream
  reason-code vocabulary (e.g. `NEW_ADMISSION`, `TRANSFERRED`, `RECALL`, `DEATH`) anywhere; the only
  literal it compares against is `"RELEASED"`.
- **External clients wired today:** prisoner-search, prison-register, locations-inside-prison
  (`config/WebClientConfiguration.kt`). **There is no prison-api / movements client.**
  `PrisonerSearchClient` already returns `prisonId` and `lastMovementTypeCode`, and the
  prisoner-search record can expose release-date fields.
- **Rollout state.** `active_agency` (migration `V11`, entity `domain/ActiveAgency`) holds a per-prison
  `active` boolean plus `updated_at` / `updated_by`. `ActiveAgenciesService.setActive` flips the row
  and evicts the cache; it raises **no** domain event today.

---

## 3. Problem A — missing inbound events (container-attached, low architectural cost)

Both of these behave like the existing MAPB-637 "prisoner released → flag property due for return"
path (`PropertyContainerWriteService.prisonerReleased`), which appends a system event to each of the
person's active containers. So the *domain* work is small and well-understood. The hard part is
**sourcing** the trigger.

### 3.1 Died in custody

- **Spec:** a distinct timeline item / wording from an ordinary release (spec item 5).
- **The problem:** today a death arrives as an ordinary `prison-offender-events.prisoner.released`
  with the domain-event `reason == "RELEASED"` — **indistinguishable** from a normal release with the
  fields the listener currently reads. We cannot split it without more signal.
- **The underlying NOMIS distinction is confirmed:** a death is a release movement
  `movementType = REL` with `movementReasonCode = DEC` ("Died") — seed data
  `prison-api/.../db/migration/data/R__1_13__MOVEMENT_REASONS.sql:63`, reference domain `MOVE_RSN`
  (`repository/jpa/model/MovementReason.java`). So the signal exists in NOMIS; the only question is
  whether it reaches us on the event.
- **Ways forward:**
  - **(a) Read the movement reason off the released event (preferred).** `prison-offender-events`
    release messages commonly carry a `nomisMovementReasonCode` alongside the mapped `reason`. If
    `nomisMovementReasonCode == "DEC"` is present on the payload, split on it in the listener with
    **no new subscription**. **Action:** confirm `DEC` is actually on the `prisoner.released` payload
    (check a real message / the prison-offender-events schema).
  - **(b) Confirm via prison-api if the code isn't on the event.** If the released event doesn't
    carry the reason code, call `GET /api/movements/offender/{offenderNo}` (returns `Movement` with
    `movementType`/`movementReasonCode`; role `VIEW_PRISONER_DATA`) to check the latest REL movement
    reason. Adds a read-time call but needs no new subscription.
- **What it needs:** a listener branch + a `DEC` reason constant in `PrisonerEventListener.kt` (route
  b also needs a small prison-api movements client — see §4.3); a new
  `PropertyEventType.DIED_IN_CUSTODY` **or** reuse `PRISONER_RELEASED`/`DUE_FOR_RETURN` with distinct
  timeline wording; a `V12` migration only if a new stored event type is added; tests. **No new SNS
  subscription** either way.

### 3.2 Scheduled for release

- **Spec:** flag "due for return" when a person is *scheduled* for release, ahead of the actual
  release (spec item 5).
- **The problem:** nothing about a *scheduled* (future-dated) release is subscribed today; the
  service only hears about a release when it *happens*.
- **Investigation result — no ready-made "scheduled release" event exists in the obvious services:**
  - **`hmpps-transfer-scheduler-api`** schedules **inter-prison transfers only** — it has no release
    concept at all (statuses `PLANNING→…→COMPLETED`; reason data is all transfer reasons). It
    publishes `person.transfer.scheduled` (see the appendix) — useful for *transfer-out*, **not
    release**.
  - **`hmpps-external-movements-api`** owns **temporary absences (TAP) only** — day/escorted release
    outings, not custodial release or transfer. It publishes a rich `person.temporary-absence.*`
    family but nothing about release/discharge.
  - So a bespoke "release scheduled" domain event would have to come from elsewhere (a
    resettlement/pre-release service or NOMIS sentence-date changes) — not confirmed to exist.
- **Ways forward:**
  - **(a) Event-driven — not currently sourceable.** No suitable upstream event was found in the
    services checked. Parking this route unless one is identified.
  - **(b) Derive on the fly (recommended).** Do **not** subscribe or store anything. At
    timeline-read time, read the prisoner's release dates and synthesise a "scheduled for release"
    marker when a release date is set and in the future. Sources: prisoner-search release-date
    fields, or prison-api sentence dates — `GET /api/bookings/{bookingId}/sentenceDetail` /
    `GET /api/offenders/{offenderNo}/sentences` return `confirmedReleaseDate` / `releaseDate` /
    `conditionalReleaseDate` etc. (role `VIEW_PRISONER_DATA`). Mirrors the Problem B approach; avoids
    new events/tables/subscriptions. Trade-off: reflects the *current* known date, not an
    audit-durable "it was scheduled on date X" fact.
- **What it needs (route b):** read-side code only — reuse the prisoner-search projection or the
  small prison-api client (§4.3); a synthesised timeline item; tests. No migration, no subscription.

> **Note on reason vocabulary:** whichever route, the repo currently has no enumerated set of NOMIS
> movement reason codes. Introducing one (alongside the existing `RELEASED_REASON = "RELEASED"`
> constant) should be a deliberate, tested addition, seeded from real payloads rather than guessed.

---

## 4. Problem B — non-container timeline events

This is the thornier issue and the reason for this document. Two spec items are **not about a single
property container**:

- **"Person admitted to an establishment"** (spec item 1) — with two axes of variation: *initial
  admission vs admission-via-transfer*, and *the receiving establishment is on DPS vs NOMIS for
  property*. Shown even for NOMIS establishments, so users understand why later events don't display.
- **"DPS first-used at an establishment"** (spec item 8) — fires when Property-in-DPS is rolled out
  to a prison, irrespective of any user input.

### 4.1 Why they don't fit the current model

- Every `PropertyEvent` is bound to a container (§2). A person-admission or establishment-rollout
  fact belongs to a **person** or an **establishment**, not a box.
- The timeline is empty when the person has no property, so admissions — arguably *most* relevant
  when there is little/no property yet — would be missing precisely when wanted.
- An establishment-level rollout fact has **no home at all** in the schema.

### 4.2 A key observation: this is movement data, largely owned elsewhere

Admissions and transfers are **prisoner-movement facts owned upstream** (prison-api / movements,
surfaced via prisoner-search and related services). Storing our own copy of "person admitted to X on
date Y" would duplicate a system of record we don't own, and commit us to keeping it in sync via yet
more event subscriptions. The design steer for this document is therefore that **these events
probably should not be owned by the property service** — they can instead be **assembled on the fly
when the timeline is read**.

### 4.3 Option 1 (recommended) — construct on the fly at read time

Do not store admission or rollout events. Extend `getPrisonerTimeline` to *compose* them when the
timeline is requested:

- **Admissions:** call an upstream movements source for the prisoner's admission/transfer history
  and synthesise timeline items (reusing the existing `PRISONER_MOVEMENT` item kind, which the
  timeline DTO and the UI's `prisonerTimeline.ts` already render as "{name} arrived at {establishment}").
  - *Source — confirmed and well-suited:* **prison-api exposes a purpose-built prisoner timeline:**
    `GET /api/offenders/{offenderNo}/prison-timeline` → `PrisonerInPrisonSummary` (role
    `VIEW_PRISONER_DATA`). It returns `PrisonPeriod`s each containing `SignificantMovement`s with
    exactly the fields we need — `reasonInToPrison`, `dateInToPrison`, `inwardType` (`ADM`/`TAP`),
    `admittedIntoPrisonId`, `reasonOutOfPrison`, `outwardType` (`REL`/`TAP`), `releaseFromPrisonId` —
    plus a `transfers` list (`TransferDetail`: from/to prison + date + reason). This is essentially
    the timeline we want, pre-assembled. Lower-level alternative: `GET /api/movements/offender/{offenderNo}`
    → `List<Movement>` (`movementType` ADM/TRN/REL/TAP/CRT + `movementReasonCode`).
  - *New client needed:* the repo has **no prison-api client yet** — this is the main new piece (a
    `PrisonApiClient` + `WebClientConfiguration` wiring + Helm base-URL/creds mirroring the existing
    prisoner-search wiring), and its client-credentials identity needs the **`VIEW_PRISONER_DATA`**
    role granted in HMPPS Auth.
  - *Retention:* NOMIS holds the full movement history per booking (the endpoints expose all
    bookings), so historical depth is not a concern — this removes the main risk previously flagged
    against Option 1.
  - *Initial-vs-transfer* is read straight from the movement reason: a transfer-in is `ADM` with
    reason `INT`/`TRNCRT`/`TRNTAP`/`T`; other `ADM` reasons are a fresh admission (or, on the
    timeline endpoint, from `reasonInToPrison`/`inwardType`). No inference needed on our side.
- **DPS first-used:** derive from the **already-stored** `active_agency.updated_at` (`domain/ActiveAgency`).
  When that establishment appears in the person's movement/holding history, emit a synthesised
  "Property management started in DPS at {establishment} on {date}" item. **No new event, no new
  table** — the data already exists.
- **Pros:** no new tables, no new stored event types, no new subscriptions; movement data stays with
  its true owner; the timeline is always accurate to current upstream truth; fixes the
  empty-timeline case for property-less prisoners as a side effect.
- **Cons:** adds a read-time dependency (latency + a failure mode — must degrade gracefully, never
  500 the timeline); requires the new prison-api client (and the `VIEW_PRISONER_DATA` role grant);
  it is **not** an audit-durable snapshot (it reflects "what upstream says now"). *(Retention is no
  longer a concern — NOMIS keeps full per-booking movement history.)*

### 4.4 Option 2 — store person/establishment events

Persist these as first-class events. Two sub-shapes:

- **(2a) A new sibling table** (e.g. `person_event` / `establishment_event`) with its own owner
  columns (`prisoner_number`, `prison_id`), unioned into the timeline. Keeps `PropertyEvent` clean
  and container-owned.
- **(2b) Relax `PropertyEvent`** — make `property_container_id` nullable and add `prisoner_number` /
  `prison_id`, plus a timeline query path that includes container-less rows. Fewer entities but
  weakens the "every event belongs to a container" invariant that the aggregate relies on.
- **Either way it needs:** a `V12`+ migration; new `PropertyEventType`(s); **new inbound movement
  subscriptions** (`prisoner.received` reason handling for initial-vs-transfer, plus a rollout event
  or a hook in `ActiveAgenciesService.setActive` to raise a "DPS first-used" domain event — publish
  after commit, guarded to fire only on the first off→on transition); and a **backfill** for
  existing prisoners/establishments so history isn't blank for anyone already in the estate.
- **Pros:** a self-contained, audit-durable, enduring record that survives upstream purges and needs
  no read-time call. **Cons:** new tables + events, duplicates data owned elsewhere, and a standing
  sync/backfill burden.

### 4.5 Recommendation

Prefer **Option 1** (on-the-fly) for admissions, and derive **DPS first-used** from the existing
`active_agency` row (also Option-1-style). This matches the steer that movement data shouldn't be
re-owned here, needs **no new tables or stored events**, and its main cost is a single new
read-time movements client. Fall back to **Option 2** *only* if an audit-durable record that
outlives upstream retention is a hard, stated requirement — decide that explicitly (§7).

---

## 5. The DPS-vs-NOMIS variant gap (applies to Option 1 and 2)

The "person admitted" item has a **DPS-vs-NOMIS variant**: it should say whether the receiving
establishment was using DPS or NOMIS *for property at the time of that admission*. But
`active_agency` records only the **current** state plus a **single** `updated_at` — it cannot answer
"was prison X on DPS on 2025-11-03?" if it has been toggled more than once, and it has no history
before the row's last update. Answering the variant *historically* needs a **rollout history / audit
trail of on↔off transitions** (a new append-only table, or reconstruction from the domain events
`ActiveAgenciesService` would emit under Option 2). For a first cut we can render the variant using
*current* DPS/NOMIS state and flag the historical-accuracy limitation — but the team should decide
whether the historical variant is actually required, since it drives whether we need rollout history
at all.

---

## 6. Summary matrix

| Spec item               | Kind | Trigger / source | Storage needed | New events | New config / clients | Effort | Risk | Recommendation |
|-------------------------|---|---|---|---|---|---|---|---|
| Died in custody         | Container-attached | Existing `prisoner.released`; split on `nomisMovementReasonCode == DEC` (confirmed NOMIS code) | None (reuse) or `V12` if new type | `DIED_IN_CUSTODY` (optional) | Listener branch + `DEC` const (+ prison-api client only if code not on event) | Low | Low–Med — just confirm `DEC` is on the payload | Split on `DEC` |
| Scheduled for release   | Read-time (derived) | prison-api sentence dates / prisoner-search release date | None | None | Reuse prisoner-search / prison-api client | Low | Low — no event source exists, so derive | Derive on the fly |
| Person admitted (1a/1b) | Non-container | prison-api `GET /api/offenders/{n}/prison-timeline` (on the fly) | None (Option 1) | None (Option 1) | New prison-api client + `VIEW_PRISONER_DATA` role | Med | Low–Med — client + read-time dep | Option 1 (on the fly) |
| DPS/NOMIS variant       | Non-container | `active_agency` (current) / rollout history (historical) | Rollout-history table if historical | — | — | Low (current) / Med (historical) | Med | Current state now; decide if historical needed |
| DPS first-used (8)      | Establishment-level | `active_agency.updated_at` (already stored) | None (Option 1) | None (Option 1) | — | Low | Low | Derive from existing `active_agency` |

*(Effort/risk assume the recommended route in each row; Option 2 raises all of them.)*

---

## 7. Open questions / decisions needed

*Resolved by the source investigation (appendix §9):* the movements endpoint (**prison-api
`prison-timeline`**, `VIEW_PRISONER_DATA`, full NOMIS retention), the death code (**NOMIS `REL`/`DEC`**),
and the absence of any ready-made "scheduled release" event (neither transfer-scheduler nor
external-movements). The remaining open questions:

1. **Death code on the event:** is `nomisMovementReasonCode == "DEC"` actually present on the
   `prison-offender-events.prisoner.released` payload? If yes, no read-time call is needed; if no, we
   fall back to a prison-api movements lookup.
2. **Audit-durability:** is an enduring record that survives upstream purges a hard requirement? A
   "yes" pushes toward Option 2 (stored events); a "no" makes Option 1 clearly preferable. **This is
   the pivotal decision.**
3. **DPS-first-used rendering:** how should an establishment-level rollout fact appear inside a
   single person's timeline (only for people held there? at the rollout date? once per person)?
4. **DPS/NOMIS historical variant:** is the *historical* variant required (needs rollout history), or
   is rendering with current DPS/NOMIS state acceptable for now?
5. **Role provisioning:** granting the property-api client-credentials identity `VIEW_PRISONER_DATA`
   in HMPPS Auth (needed for any prison-api read route).

---

## 8. Recommended sequencing

1. **Died in custody** via a `DEC` movement-reason split on the existing `released` event — smallest,
   clearest win, no new subscription. First confirm `DEC` is on the payload (open question 1).
2. **Scheduled for release** derived on the fly from release dates — no new event/table/subscription.
3. **Settle the audit-durability decision** (open question 2) — it picks Option 1 vs Option 2 for the
   non-container events, so resolve it before building them.
4. **Build the prison-api client** (`prison-timeline` + movements; needs the `VIEW_PRISONER_DATA`
   grant). This single client unblocks steps 1(b), 2 and 5.
5. **Person admitted (Option 1)** reusing the existing `PRISONER_MOVEMENT` item kind; **DPS
   first-used** derived from `active_agency` alongside it.
6. Revisit **Option 2 / rollout history** only if audit-durability or the historical variant
   (open questions 2 & 4) come back as hard requirements.

---

## 9. Appendix — upstream sources investigated

Findings from reading the candidate source services. Paths are in each service's own repo.

### prison-api (NOMIS) — the source for movements, timeline and dates
- **Prisoner timeline (best fit for Problem B):** `GET /api/offenders/{offenderNo}/prison-timeline`
  → `PrisonerInPrisonSummary` (`OffenderResource.java`, role `VIEW_PRISONER_DATA`). Contains
  `PrisonPeriod` → `SignificantMovement` (`reasonInToPrison`, `dateInToPrison`, `inwardType`
  ADM/TAP, `admittedIntoPrisonId`, `reasonOutOfPrison`, `outwardType` REL/TAP, `releaseFromPrisonId`)
  and `TransferDetail` (from/to prison, date, reason). Essentially a pre-assembled timeline.
- **Raw movements:** `GET /api/movements/offender/{offenderNo}` → `List<Movement>` (`MovementResource.java`,
  `VIEW_PRISONER_DATA`). `Movement` carries `movementType` (ADM/CRT/REL/TAP/TRN), `movementReasonCode`,
  `movementReason`, `fromAgency`/`toAgency`, `movementDate`/`movementTime`, `directionCode`.
- **Movement reason reference data** (`MovementReason.java` / `MovementType.java`; seed
  `db/migration/data/R__1_13__MOVEMENT_REASONS.sql`): **death = `REL` / `DEC` ("Died")**;
  transfer-in = `ADM` with `INT`/`TRNCRT`/`TRNTAP`/`T`; fresh admission = `ADM` with other reasons.
- **Release/sentence dates (Problem A.2):** `GET /api/bookings/{bookingId}/sentenceDetail` →
  `SentenceCalcDates` (`confirmedReleaseDate`, `releaseDate`, `conditionalReleaseDate`, …); also
  `GET /api/offenders/{offenderNo}/sentences`. Roles `VIEW_PRISONER_DATA` / `GLOBAL_SEARCH`.
- **Common role:** `VIEW_PRISONER_DATA` covers all of the above for a service-to-service read.

### hmpps-transfer-scheduler-api — inter-prison transfers only (not release)
- Schedules **transfers**, no release concept. Publishes `person.transfer.scheduled` (+ `.planned`,
  `.recorded`, `.migrated`) to `hmpps-domain-events`; payload carries the NOMS number
  (`personReference`) + transfer UUID + a `detailUrl`, but **not** the date/destination inline (fetch
  via `GET /transfers/{id}`, a UI-role endpoint). Fires only on initial persist in `SCHEDULED` state;
  no cancel/reschedule event. **Relevance:** a possible *future* event-driven source for "due for
  transfer out" (spec item 6) — **not** for scheduled release.

### hmpps-external-movements-api — temporary absences (TAP) only
- Owns temporary-absence authorisations/occurrences/movements. Publishes a rich
  `person.temporary-absence.*` / `...-authorisation.*` / `...-movement.*` family. **No release,
  discharge or transfer** data or events. **Relevance:** none for release/transfer/admission; noted
  to confirm it is *not* a source.

---

*Cross-references: the "History items and when they are triggered" spec; MAPB-568 (prisoner
timeline), MAPB-637 (release → due-for-return), MAPB-640 (history wording/snapshotting), MAPB-641
(active agencies / rollout).*
