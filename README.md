# hmpps-prisoner-property-api

[![Ministry of Justice Repository Compliance Badge](https://github-community.service.justice.gov.uk/repository-standards/api/hmpps-prisoner-property-api/badge?style=flat)](https://github-community.service.justice.gov.uk/repository-standards/hmpps-prisoner-property-api)
[![Docker Repository on ghcr](https://img.shields.io/badge/ghcr.io-repository-2496ED.svg?logo=docker)](https://ghcr.io/ministryofjustice/hmpps-prisoner-property-api)
[![API docs](https://img.shields.io/badge/API_docs_-view-85EA2D.svg?logo=swagger)](https://prisoner-property-api-dev.hmpps.service.justice.gov.uk/swagger-ui/index.html)

API for managing a prisoner's property held within a prison.

## Overview

This service is the system of record for **prisoner property** in HMPPS. It models property as
sealed **containers** and a full history of **events** against each container, and it keeps that
data in step with NOMIS.

It provides endpoints to:

- **Read** a prisoner's containers (or all containers in a prison, or a single container by id),
  including each container's current seal, status, type and location.
- **Create** a new sealed **property container** for a prisoner (Standard, Excess, Valuables or
  Confiscated).
- **Update** a container's mutable details (type, seal, location, proposed disposal date),
  recording any change in its history.
- **Move** a container to an internal prison location or offsite to the Branston warehouse.
- **Combine** two or more source containers into a single new sealed container.
- **Remove** a container from active storage by returning it to the prisoner or transferring it
  to another prison.
- **Dispose** of a container (destruction), taking it out of active storage.
- **Sync / migrate** containers from NOMIS, and read them back by DPS id for reconciliation.

Every write builds the corresponding **event** in the container's history, and (except for
migration) raises an HMPPS domain event after the transaction commits so downstream systems can
follow the change.

### Domain model

The data is **event-sourced**: a `PropertyContainer` owns an ordered history of `PropertyEvent`s, and the
container's *current* status and location are **derived from its most recent relevant event**.

Two things temper that. The **seal number is stored**, because uniqueness across containers in storage has
to be checked in SQL. And status, location, location type and receiving prison are each **mirrored into a
column** so the establishment-wide list can filter and page without loading every event — the derivation
stays authoritative, but every write path must call `refreshDerivedState()` to keep the mirrors honest.

```
PropertyContainer 1 ──────< * PropertyEvent
  prisonerNumber                eventType (enum)
  prisonId                      sealNumber
  containerType (enum)          eventDateTime
  proposedDisposalDate          from/to internal location id (UUID)
  createdByUserId               from/to location type (enum)
  createDateTime                from/to prison id
  (derived) currentSealNumber   eventUserId
  (derived) currentStatus
  (derived) currentLocation
  (derived) currentLocationType
  removalOutcome / removalDate
```

- **Container types:** `STANDARD`, `EXCESS`, `VALUABLES`, `CONFISCATED`.
- **Event types:** `CREATED_SEALED`, `SEAL_CHANGED`, `CONTAINER_TYPE_CHANGE`, `MOVED`, `TRANSFERRED`,
  `RETURNED`, `DISPOSAL_REQUIRED`, `DISPOSED`, `COMBINED`, `CREATED_IN_ERROR`, `REMOVED`, `REACTIVATED`,
  and the three movement events driven by the prisoner event listener — `PRISONER_RECEIVED`,
  `PRISONER_RELEASED`, `DIED_IN_CUSTODY`.
- **Status (derived):** `STORED`, `DUE_FOR_TRANSFER_OUT`, `DUE_FOR_RETURN`, `DISPOSAL_REQUIRED`,
  `DISPOSED`, `RETURNED`, `TRANSFER`, `COMBINED`, `CREATED_IN_ERROR`, `REMOVED`. The first three drive the
  establishment summary tiles and list filters.
- **Storage location types:** `INTERNAL` (a `hmpps-locations-inside-prison-api` location UUID) or
  `BRANSTON` (the offsite warehouse, where there is no internal location id).
- **Removal outcomes:** when a container leaves active storage its `removalOutcome` records why —
  `DISPOSED`, `RETURNED`, `TRANSFERRED`, `COMBINED`, `CREATED_IN_ERROR` or `REMOVED` — alongside the
  `removalDate`. `REMOVED` is the only reversible one: clearing it with a `REACTIVATED` event brings the
  container back, which is why there is no separate archived flag.
- **A transfer out is a removal, not a move.** Sending a container to another prison does not reassign its
  `prisonId`; it removes the source with outcome `TRANSFERRED` and the destination gets its own record when
  staff there log the arrival. See [architecture.md](docs/architecture.md) §6.
- Internal location ids reference the `hmpps-locations-inside-prison-api` location UUID.
- A container is only created once a seal has been entered; changing a seal keeps the same
  container id.
- Once a container has left active storage it cannot be moved, removed or disposed again
  (those endpoints return `409 Conflict`).

### API endpoints

All endpoints are JSON and require an HMPPS Auth bearer token. Reads require
`ROLE_PRISONER_PROPERTY__RO`; writes require `ROLE_PRISONER_PROPERTY__RW`; the NOMIS sync endpoints
require `ROLE_PRISONER_PROPERTY__SYNC`; the rollout console requires `ROLE_PRISONER_PROPERTY__ADMIN` and
storage-location management `ROLE_PRISONER_PROPERTY__LOCATION_ADMIN`.

**Property containers** (`/property-containers`)

| Method & path | Role | Description |
| --- | --- | --- |
| `GET /prisoner/{prisonerNumber}` | RO | All containers held for a prisoner |
| `GET /prisoner/{prisonerNumber}/summary` | RO | Property counts for one prisoner (feeds the profile tile) |
| `GET /prisoner/{prisonerNumber}/events` | RO | The prisoner's property timeline across all their containers |
| `GET /prison/{prisonId}` | RO | All containers held in a prison — searchable, filterable, paged by prisoner |
| `GET /prison/{prisonId}/summary` | RO | The establishment summary tiles |
| `GET /prison/{prisonId}/box-locations` | RO | Storage locations with space, for the "where stored" step |
| `GET /{id}` | RO | A single container by id |
| `GET /{id}/events` | RO | One container's history |
| `POST /` | RW | Create a new sealed container |
| `PUT /{id}` | RW | Update a container's type, seal, location and proposed disposal date |
| `POST /{id}/move` | RW | Move a container to an internal location or to Branston |
| `POST /combine` | RW | Combine two or more source containers into a new sealed container |
| `POST /{id}/remove` | RW | Remove from storage — returned, transferred, disposed or created in error |
| `POST /{id}/dispose` | RW | Dispose of (destroy) a container |

**Rollout console** (`/active-agencies`) — which prisons manage property in DPS rather than NOMIS. The
same list is published on the actuator `/info` payload, which is how the front end reads it.

| Method & path | Role | Description |
| --- | --- | --- |
| `GET /` | ADMIN | The prisons currently switched on |
| `GET /all` | ADMIN | Every prison with its on/off state, for the console |
| `PUT /{agencyId}` | ADMIN | Switch a prison on or off |

**Storage locations** (`/property-locations`)

| Method & path | Role | Description |
| --- | --- | --- |
| `GET /prison/{prisonId}` | LOCATION_ADMIN | The prison's property storage locations |
| `POST /prison/{prisonId}` | LOCATION_ADMIN | Add a storage location |
| `PUT /{id}` | LOCATION_ADMIN | Rename a location or change its capacity |
| `DELETE /{id}` | LOCATION_ADMIN | Remove an empty storage location |

**Sync with NOMIS** (`/sync/property-containers`)

| Method & path | Role | Description |
| --- | --- | --- |
| `POST /upsert` | SYNC | Create or update a container from an ongoing NOMIS change (raises a domain event) |
| `POST /migrate` | SYNC | Bulk-migrate a container from NOMIS (raises no domain event) |
| `GET /{id}` | SYNC | Read a synced container by DPS id, for reconciliation |

### NOMIS synchronisation

Sync with NOMIS is **decoupled** from this API, following the standard HMPPS pattern. This
service owns the data and raises domain events when property changes; separate services consume
them:

- [`hmpps-prisoner-to-nomis-update`](https://github.com/ministryofjustice/hmpps-prisoner-to-nomis-update)
  consumes our events and writes the changes to NOMIS.
- [`hmpps-prisoner-from-nomis-migration`](https://github.com/ministryofjustice/hmpps-prisoner-from-nomis-migration)
  performs the initial migration by calling our read endpoints.

This API never calls those services directly — they call us.

### Front end

The staff-facing front end is
[**`hmpps-prisoner-property-ui`**](https://github.com/ministryofjustice/hmpps-prisoner-property-ui) — a
TypeScript/Express/Nunjucks app built from the
[hmpps-template-typescript](https://github.com/ministryofjustice/hmpps-template-typescript). It consumes
this API to let staff manage prisoner property, and is the only caller of these endpoints other than the
NOMIS sync services above.

## Documentation

| Doc | For |
| --- | --- |
| [Business overview](docs/business-overview.md) | What the service does and why, in plain English. Start here. |
| [Architecture](docs/architecture.md) | The whole service — both repos, diagrams, messaging, domain model. |
| [Technical implementation](docs/technical-implementation.md) | This API's internals: packages, patterns, dependencies. |
| [Establishment summary counts](docs/establishment-summary-counts.md) | How the summary tiles are counted. |
| [Operational runbooks](docs/runbook.md) | One-off/coordinated operational procedures (migrations, backfills). Not a general operations reference. |
| [Property returned or transferred tab](docs/property-returned-or-transferred-tab.md) | How the tab was built, and how each open design question was answered. |
| [Property snag issues](docs/property-snags.md) | The MAPB-709 snag backlog and the decisions taken on each. |

## Tech stack

- **Kotlin** on **Spring Boot**, via the HMPPS
  [`hmpps-kotlin-spring-boot-starter`](https://github.com/ministryofjustice/hmpps-kotlin-lib).
- **HMPPS Auth** OAuth2 / JWT resource server for authentication and role-based authorisation.
- **Spring Data JPA / Hibernate**, **Flyway** migrations, **PostgreSQL** (AWS RDS in deployed
  environments).
- **AWS SQS / SNS** for HMPPS domain events, via
  [`hmpps-sqs-spring-boot-starter`](https://github.com/ministryofjustice/hmpps-sqs-spring-boot-starter).
- **springdoc-openapi** for the OpenAPI/Swagger documentation.
- **Gradle** (Kotlin DSL), Java 25 toolchain.
- **JUnit 5**, **Testcontainers** (PostgreSQL + LocalStack), **WireMock** for tests.
- Deployed to **MoJ Cloud Platform** (Kubernetes) with **Helm**; CI/CD via **GitHub Actions**;
  monitoring via **Application Insights** and Prometheus.

## API documentation

OpenAPI docs are served from the running application at `/swagger-ui/index.html`, with the raw
spec at `/v3/api-docs`. The dev environment is published
[here](https://prisoner-property-api-dev.hmpps.service.justice.gov.uk/swagger-ui/index.html).

## Running the application locally

The `docker-compose.yml` starts everything the service needs locally: **PostgreSQL**,
**LocalStack** (SQS/SNS), **HMPPS Auth**, and the application itself.

```bash
docker compose pull && docker compose up
```

### Running in IntelliJ

Start only the supporting services and run the application from the IDE with the `dev` profile
active:

```bash
docker compose pull && docker compose up --scale hmpps-prisoner-property-api=0
```

The `dev` Spring profile (which activates the `localstack` profile group) includes sensible
defaults for local Postgres and LocalStack.

## Running the tests

The integration tests use Testcontainers, so **Docker must be running**. They will start a
PostgreSQL and a LocalStack container automatically (or reuse one already listening on `5432` /
`4566`).

```bash
# unit + integration tests
./gradlew test

# full verification (tests + ktlint + assemble) - what CI runs
./gradlew check
```

## Building and running the docker image locally

The `Dockerfile` relies on the application being built first:

```bash
./gradlew clean assemble
cp build/libs/*.jar .
docker build --build-arg GIT_REF=local --build-arg GIT_BRANCH=local --build-arg BUILD_NUMBER=$(date '+%Y-%m-%d') .
docker run -e HMPPS_AUTH_URL="https://sign-in-dev.hmpps.service.justice.gov.uk/auth" <image sha>
```

## Deployment

The service deploys to the shared `hmpps-locations-inside-prison` Cloud Platform namespaces
(dev / preprod / prod) via Helm (`helm_deploy/`). Its RDS instance, SQS queues and IRSA service
account are provisioned in
[cloud-platform-environments](https://github.com/ministryofjustice/cloud-platform-environments).

## Support

Community managed by the mojdt `#kotlin-dev` Slack channel. Common HMPPS Kotlin patterns are
documented in the [HMPPS tech docs](https://tech-docs.hmpps.service.justice.gov.uk/common-kotlin-patterns/).
The security policy is [here](https://github.com/ministryofjustice/hmpps-prisoner-property-api/security/policy).
