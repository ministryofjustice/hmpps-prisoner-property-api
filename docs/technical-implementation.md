# Prisoner Property API — Technical Implementation

How this service is put together internally: what lives where, the patterns you need to know before
changing it, and what it depends on. For the service as a whole (both repos, diagrams, messaging
topology) see the [architecture doc](architecture.md).

**Related docs:** [Architecture](architecture.md) · [Business overview](business-overview.md) ·
[UI technical implementation](https://github.com/ministryofjustice/hmpps-prisoner-property-ui/blob/main/docs/technical-implementation.md)

**Not repeated here** (deliberately, so there's one place to fix each): the full endpoint table, domain
model prose, enum meanings, tech stack and run/deploy instructions are in the
[README](../README.md); the summary-tile counting rules are in
[establishment-summary-counts.md](establishment-summary-counts.md).

---

## Package map

Base package: `uk.gov.justice.digital.hmpps.prisonerpropertyapi`

| Package | What's in it |
| --- | --- |
| `resource/` | REST controllers. Enforce roles, document the API, and — unusually — **publish domain events**, because they run after the service transaction commits. |
| `service/` | All business logic. **The transaction boundary.** Never publishes. |
| `domain/` | JPA entities, enums, repositories, and the derivation logic that constitutes the model. |
| `dto/` | The wire contract (requests + responses), with springdoc `@Schema` annotations. `dto/sync/` holds the NOMIS wire types. |
| `event/` | Domain-event plumbing: `PropertyContainerEventFactory`, `DomainEventPublisher` (out), `PrisonerEventListener` (in), `HmppsDomainEvent`, and `PropertyEventSource` — the `DPS`/`NOMIS` attribute a sync-back filters on to avoid looping. |
| `client/` | Outbound WebClients, one per external service, each declaring its own small response types. |
| `config/` | WebClient/OAuth2 wiring, caching, OpenAPI, the `@RestControllerAdvice` exception handler, and `ActiveAgenciesInfo` — an `InfoContributor` that publishes the active-prison list on `/info`. That is a public contract: it is how the front end learns which prisons are switched on. |
| `health/` | One health-ping bean per external dependency. |

There is **no custom `SecurityConfiguration`** — JWT resource-server security comes from
`hmpps-kotlin-spring-boot-starter`. Authorisation is `@PreAuthorize` per resource.

---

## Resources

Four controllers, split by audience rather than by entity — each has a different caller and a different
role, and keeping them apart is what stops staff endpoints and machine endpoints sharing a blast radius.

| Resource | Base path | Role | Caller |
| --- | --- | --- | --- |
| `PropertyContainerResource` | `/property-containers` | `__RO` at class level; `__RW` on each mutating method | The front end (staff) |
| `SyncPropertyContainerResource` | `/sync/property-containers` | `__SYNC` | NOMIS sync/migration services |
| `ActiveAgenciesResource` | `/active-agencies` | `__ADMIN` | The rollout console |
| `PropertyLocationAdminResource` | `/property-locations` | `__LOCATION_ADMIN` | The location-admin screens |

> **Convention (enforced by tests):** every endpoint needs full springdoc `@Operation`/`@ApiResponse`
> annotations **and** a `@PreAuthorize` role, or `OpenApiDocsTest` / `ResourceSecurityTest` fail the build.

---

## Services — three paths, one event factory

| Service | Responsibility |
| --- | --- |
| `PropertyContainerService` | Every read. `@Transactional(readOnly = true)`. Also builds the prisoner timeline. |
| `PropertyContainerWriteService` | Every staff write — create, update, dispose, remove, combine, move — plus the listener-driven `prisonerReceived` / `prisonerReleased` / `prisonerDied`. |
| `SyncPropertyContainerService` | The NOMIS path only: `sync` (ongoing) and `migrate` (bulk initial load). |

All three build their events through **`PropertyContainerEventFactory`**, so an event raised by a staff
action and one raised by NOMIS sync are the same shape. Six smaller components support them:

| Component | Responsibility |
| --- | --- |
| `ActiveAgenciesService` | The rollout flag. Deliberately **not** cached, so an admin toggle can't flip-flop between pods. |
| `BoxLocationService` | Storage locations with space. |
| `PropertyLocationAdminService` | Location CRUD, guarding capacity and in-use deletes. |
| `ContainerStatusResolver` | The status any screen actually shows — removal, then disposal, then owner location, then base status. |
| `PrisonStatusOverlayFactory` | Resolves the owners of one prison's property into a `StatusOverlay`. |
| `PrisonRollFactory` | Who is currently on a prison's roll, for the incoming-property list. |

The last three are the owner-location machinery described in
[architecture.md §6a](architecture.md#6a-owner-location--the-status-you-see-is-not-the-status-in-the-column).
They have deliberately different failure modes when prisoner-search is slow or partial:
`PrisonStatusOverlayFactory` caps candidates and would rather mislabel than omit, `PrisonRollFactory`
tolerates truncation and would rather omit than mislabel. Both are wrong in the direction that is safe for
what they feed.

Write services are thin on dependencies on purpose: `PropertyContainerWriteService` takes only the
repository and `LocationsClient` — it validates a location and appends events; it does not know who the
prisoner is. `PropertyContainerService`, by contrast, aggregates from four external clients.

### Publish-after-commit

**The pattern to understand before touching a write path.** The service is `@Transactional` and returns
the event it *would* raise; the resource publishes once the transaction has committed.

```kotlin
// In the resource — after the service call returns, i.e. after the DB commit
private fun WriteResult.publishAfterCommit(): PropertyContainerDto {
  event?.let(domainEventPublisher::publish)
  return container
}
```

| Result type | Returned by | Carries |
| --- | --- | --- |
| `WriteResult` | update, dispose, remove, move | `event: HmppsDomainEvent?` — **null when nothing actually changed** |
| `CreateResult` | create | the new container's event, plus an update for a reconciled transfer-in source |
| `CombineResult` | combine | one created + one updated per source container |
| `SyncResult` | sync, migrate | `event?` — **always null for `migrate`** (a bulk load must not flood the topic) |

Two failure modes this prevents: publishing an event for a transaction that then rolls back, and a
subscriber reacting so fast it reads the API before the commit lands and sees stale state.
`PrisonerEventListener` follows the same shape — call the write service, publish afterwards.

If you add a write path: append the event, mutate the container, call `refreshDerivedState()`, return a
result type, and publish in the resource. Never inject `DomainEventPublisher` into a service.

---

## Derived state

A container's status and location are **computed** from its events. Its seal number is not — the seal is a
stored column, because uniqueness has to be checked in SQL, which is why there is no `currentSeal()` below.

| Method | Rule |
| --- | --- |
| `currentStatus()` | `removalOutcome` wins → else `DISPOSAL_REQUIRED` if disposal is due → else the latest event's status. |
| `baseStatus()` | As above but **without** the time-based disposal overlay — this is what gets denormalised. |
| `isRemoved()` | `removalOutcome != null`. |
| `isDisposalDue()` | Not removed, and `proposedDisposalDate` is today or earlier. |
| `currentLocation()` | `UUID?` of the latest location-bearing event's location. **Null once removed**, and null after a transfer out — the receiving prison assigns its own. |
| `receivingPrison()` | The destination prison, in **two** cases: while `baseStatus()` is `DUE_FOR_TRANSFER_OUT`, *and* once removed as `TRANSFERRED` while the latest transfer event is still unreconciled (`relatedContainerId == null`). |

**`currentStatus()` is not what users see.** Every read surface passes through `ContainerStatusResolver`,
which layers the owner's location over the container's own status — see
[architecture.md §6a](architecture.md#6a-owner-location--the-status-you-see-is-not-the-status-in-the-column).
The table above is the entity's view of itself, not the screen's.

The second branch of `receivingPrison()` is what makes transferred-out property visible at the prison it
was sent to: `incomingScope`'s in-flight predicate depends on it. Delete it and boxes vanish in transit.

Four columns mirror this state — `currentStatusValue`, `currentInternalLocationId`,
`currentStorageLocationType`, `receivingPrisonId` — purely so the establishment list can filter and
paginate in SQL without loading every event. **They are not the truth.** Every write path must call
`refreshDerivedState()` after mutating events or removal state, or the list view silently drifts from
the container's real state.

`receivingPrisonId` in particular is only as good as the events that reached the container: NOMIS-migrated
property has none, and a later sync-written move or reseal takes `baseStatus()` back to `STORED`, which
clears the column again. So the "due for transfer in" filter does **not** rely on it alone — it also matches
live property held elsewhere whose owner is on this prison's roll (`PrisonRollFactory`, backed by
prisoner-search). That mirrors the person page, which lists any live container held at another establishment
as incoming while its owner is here. With no roll available the filter falls back to the column alone.

Disposal is deliberately excluded from the mirror: it is time-based, so a container becomes overdue with
no write occurring. `V9__reset_denormalised_disposal_status.sql` exists precisely because it *was* once
denormalised and went stale.

---

## External dependencies

| Client | Service | Used for | Caching / failure behaviour |
| --- | --- | --- | --- |
| `PrisonerSearchClient` | Prisoner Search | Name, current prison, release dates; batch lookups for the establishment list | Not cached. Batched in chunks of 1000; a failed chunk degrades to empty rather than failing the page. |
| `PrisonRegisterClient` | Prison Register | Prison id → name; active prison ids | **Cached 24h** (`prisonNames`, per-pod, scheduled evict). A stale name is cosmetic. Uses the unauthenticated `/prisons` endpoint. |
| `LocationsClient` | Locations Inside Prison | Resolve/validate storage locations, capacity, location CRUD | **Deliberately not cached** — it drives capacity and validation decisions that must stay consistent across pods. |
| `PrisonApiClient` | Prison API | The prisoner's admission/transfer history for the timeline | Not cached. **Degrades gracefully** — any failure returns null and the timeline simply omits movement items rather than 500ing. Needs `VIEW_PRISONER_DATA` on the system client. |

All authenticated clients use client-credentials tokens via `OAuth2ClientConfiguration` (registration id
`prisoner-property-api`). HMPPS Auth is never called for data — only for tokens and the health ping.

**This service never calls NOMIS.** Sync is inbound only, via `/sync/property-containers`.

---

## Messaging

Publishes `prison-property.container.created` and `prison-property.container.updated` to the shared
`domainevents` SNS topic. There is no removed/deleted type — removal is an *update* that sets a removal
outcome.

Consumes from the `prisonerproperty` SQS queue, which subscribes to that **same** topic with a filter for
`prisoner.received` / `prisoner.released`. Note the subtleties in `PrisonerEventListener`: a release event
also fires for court, temporary absence and transfers, so only reason `RELEASED` counts; and a death in
custody arrives as a release distinguished only by NOMIS movement reason code `DEC`. See the
[architecture doc](architecture.md#5-messaging) for the topology diagram.

---

## Auth and roles

| Role | Grants |
| --- | --- |
| `ROLE_PRISONER_PROPERTY__RO` | Read. Class-level on `PropertyContainerResource`, so all GETs inherit it. |
| `ROLE_PRISONER_PROPERTY__RW` | Every mutating method on `PropertyContainerResource`. |
| `ROLE_PRISONER_PROPERTY__SYNC` | The NOMIS sync/migrate endpoints. |
| `ROLE_PRISONER_PROPERTY__ADMIN` | The rollout console (`/active-agencies`). |
| `ROLE_PRISONER_PROPERTY__LOCATION_ADMIN` | Storage-location management (`/property-locations`). |
| `ROLE_PRISONER_PROPERTY_QUEUE_ADMIN` | The hmpps-sqs operational queue-admin endpoints (infrastructure, excluded from the published API docs). |

---

## Persistence

Postgres via Spring Data JPA, `ddl-auto: none`, `open-in-view: false`. Ids are **UUID v7**
(`@GeneratedUuidV7`, `domain/helper/UuidV7Generator.kt`) — time-ordered, so they index well without
leaking a sequence.

`findById` uses the `PropertyContainer.withEvents` entity graph: deriving state touches every event, so
loading them lazily would be an N+1 on every read.

**Schema changes go in a new `V{n}__*.sql` Flyway migration — never an entity-only DDL change.** Fourteen
migrations so far (`src/main/resources/db/migration/`); their names describe the change, and reading them
in order is the fastest way to understand how the model arrived where it is. The last two are instructive:
`V13` adds a seal snapshot to combine events so history names the right destination even after a reseal,
and `V14` drops `archived` in favour of the reversible `REMOVED` outcome.

### The establishment-list query

`PrisonPropertyFilter` is the resolved criteria object for the establishment-wide list, and
`PropertyContainerRepositoryCustom` / `…Impl` build the Criteria API query from it. The list **pages by
prisoner rather than by container**, so a person's containers cannot split across a page boundary; and
`application.yml` sets `hibernate.query.in_clause_parameter_padding: true` because the owner-location
overlay binds a variable-size prisoner-number set that would otherwise compile a new statement per size.
`PersonLocation` filters the same list by where the owner is, in memory, from the overlay already resolved.

---

## Testing

Integration tests extend `IntegrationTestBase` (RANDOM_PORT, `test` profile, Testcontainers Postgres +
LocalStack) and authenticate with the `setAuthorisation()` JWT helper. **Docker must be running.**
`OpenApiDocsTest` and `ResourceSecurityTest` enforce the annotation/role conventions above.

`IntegrationTestBase` registers WireMock extensions for **all five** upstreams — HMPPS Auth, prisoner
search, prison register, locations inside prison and prison API — so a new test gets every stub seam for
free and does not need to add one.

Full check: `./gradlew check`. See the [README](../README.md) for the rest.

---

## Known rough edges

- [`timeline-events-architecture-scoping.md`](timeline-events-architecture-scoping.md) is a **superseded
  draft**. It was a decision aid written before the timeline work; most of what it proposes is now built,
  and some of its current-state claims are false (it says there is no prison-api client — there is). It is
  kept for the *why*. Do not read it as current state.
- `server/routes/index.test.ts` in the UI is a single ~2,500-line route test, even though the routes
  themselves were split into one file per journey. Noted here because someone crossing over from this repo
  will go looking for `establishmentList.test.ts` and not find it.
