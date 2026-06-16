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

The data is **event-sourced**: a `PropertyContainer` owns an ordered history of `PropertyEvent`s,
and the container's *current* seal number, status and location are **derived from its most recent
relevant event** rather than stored.

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
- **Event types:** `CREATED_SEALED`, `SEAL_CHANGED`, `CONTAINER_TYPE_CHANGE`, `MOVED`,
  `TRANSFERRED`, `RETURNED`, `DISPOSAL_REQUIRED`, `DISPOSED`, `COMBINED`.
- **Status (derived):** `STORED`, `DISPOSAL_REQUIRED`, `DISPOSED`, `RETURNED`, `TRANSFER`,
  `COMBINED`.
- **Storage location types:** `INTERNAL` (a `hmpps-locations-inside-prison-api` location UUID) or
  `BRANSTON` (the offsite warehouse, where there is no internal location id).
- **Removal outcomes:** when a container leaves active storage its `removalOutcome` records why —
  `DISPOSED`, `RETURNED`, `TRANSFERRED` or `COMBINED` — alongside the `removalDate`.
- Internal location ids reference the `hmpps-locations-inside-prison-api` location UUID.
- A container is only created once a seal has been entered; changing a seal keeps the same
  container id.
- Once a container has left active storage it cannot be moved, removed or disposed again
  (those endpoints return `409 Conflict`).

### API endpoints

All endpoints are JSON and require an HMPPS Auth bearer token. Reads require
`ROLE_PRISONER_PROPERTY__RO`; writes require `ROLE_PRISONER_PROPERTY__RW`; the NOMIS sync
endpoints require `ROLE_PRISONER_PROPERTY__SYNC`.

**Property containers** (`/property-containers`)

| Method & path | Role | Description |
| --- | --- | --- |
| `GET /prisoner/{prisonerNumber}` | RO | All containers held for a prisoner |
| `GET /prison/{prisonId}` | RO | All containers held in a prison |
| `GET /{id}` | RO | A single container by id |
| `POST /` | RW | Create a new sealed container |
| `PUT /{id}` | RW | Update a container's type, seal, location and proposed disposal date |
| `POST /{id}/move` | RW | Move a container to an internal location or to Branston |
| `POST /combine` | RW | Combine two or more source containers into a new sealed container |
| `POST /{id}/remove` | RW | Remove from storage by returning or transferring |
| `POST /{id}/dispose` | RW | Dispose of (destroy) a container |

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

A TypeScript front end, **`hmpps-prisoner-property`** (built from the
[hmpps-template-typescript](https://github.com/ministryofjustice/hmpps-template-typescript)),
will consume this API to let staff manage prisoner property. It is to be created shortly.

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
