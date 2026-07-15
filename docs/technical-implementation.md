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
| `event/` | Domain-event plumbing: `PropertyContainerEventFactory`, `DomainEventPublisher` (out), `PrisonerEventListener` (in), `HmppsDomainEvent`. |
| `client/` | Outbound WebClients, one per external service, each declaring its own small response types. |
| `config/` | WebClient/OAuth2 wiring, caching, OpenAPI, and the `@RestControllerAdvice` exception handler. |
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
action and one raised by NOMIS sync are the same shape. Three smaller services support them:
`ActiveAgenciesService` (rollout flag — deliberately **not** cached, so an admin toggle can't flip-flop
between pods), `BoxLocationService` (storage locations with space) and `PropertyLocationAdminService`
(location CRUD, guarding capacity and in-use deletes).

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

A container's status, location and seal are **computed, not stored**:

| Method | Rule |
| --- | --- |
| `currentStatus()` | `removalOutcome` wins → else `DISPOSAL_REQUIRED` if disposal is due → else the latest event's status. |
| `baseStatus()` | As above but **without** the time-based disposal overlay — this is what gets denormalised. |
| `isRemoved()` | `removalOutcome != null`. |
| `isDisposalDue()` | Not removed, and `proposedDisposalDate` is today or earlier. |
| `currentLocation()` | Location from the latest location-bearing event; **null once removed**. |
| `receivingPrison()` | The destination prison, only while `baseStatus()` is `DUE_FOR_TRANSFER_OUT`. |

Four columns mirror this state — `currentStatusValue`, `currentInternalLocationId`,
`currentStorageLocationType`, `receivingPrisonId` — purely so the establishment list can filter and
paginate in SQL without loading every event. **They are not the truth.** Every write path must call
`refreshDerivedState()` after mutating events or removal state, or the list view silently drifts from
the container's real state.

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

**Schema changes go in a new `V{n}__*.sql` Flyway migration — never an entity-only DDL change.** Twelve
migrations so far (`src/main/resources/db/migration/`); their names describe the change, and reading them
in order is the fastest way to understand how the model arrived where it is.

---

## Testing

Integration tests extend `IntegrationTestBase` (RANDOM_PORT, `test` profile, Testcontainers Postgres +
LocalStack, WireMock HMPPS Auth) and authenticate with the `setAuthorisation()` JWT helper. **Docker must
be running.** `OpenApiDocsTest` and `ResourceSecurityTest` enforce the annotation/role conventions above.

Full check: `./gradlew check`. See the [README](../README.md) for the rest.

---

## Known rough edges

- [`timeline-events-architecture-scoping.md`](timeline-events-architecture-scoping.md) is a **superseded
  draft**. It was a decision aid written before the timeline work; most of what it proposes is now built,
  and some of its current-state claims are false (it says there is no prison-api client — there is). It is
  kept for the *why*. Do not read it as current state.
- `server/routes/index.ts` in the UI is one very large file — noted here because it is the thing most
  likely to surprise someone crossing over from this repo.
