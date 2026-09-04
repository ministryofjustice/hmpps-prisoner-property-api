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

The subject access request endpoints are separate from all of these and require `ROLE_SAR_DATA_ACCESS` —
see [Subject access requests](#subject-access-requests).

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
| `GET /ids` | SYNC | Page through every DPS container id, in a stable order, to reconcile against NOMIS |

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

### Subject access requests

Everything this service stores belongs to a prisoner, so all of it is disclosable in a subject access
request — see [Table and column descriptions](#table-and-column-descriptions) for why the sensitivity tag
on an individual column does not change that.

| Method & path | Role | Description |
| --- | --- | --- |
| `GET /subject-access-request` | `ROLE_SAR_DATA_ACCESS` | Everything held about one prisoner, optionally within a date range |
| `GET /subject-access-request/template` | `ROLE_SAR_DATA_ACCESS` | The mustache template the SAR tool renders that data with |

Neither endpoint is written in this repo. `hmpps-kotlin-spring-boot-starter` registers it automatically
when a bean implementing `HmppsPrisonSubjectAccessRequestService` is present, and guards it with the role
above — `PrisonerPropertySubjectAccessRequestService` is that bean. It returns `204` when the prisoner has
no property and `209` when asked for a probation case reference, which this service never holds.

Two things about it are load-bearing and easy to undo by accident:

- It reads the database directly rather than through `PropertyContainerService`. The product read paths
  enrich what they return from prisoner-search, prison-register, locations-inside-prison and prison-api,
  and a SAR response must contain only data this service owns.
- A date range selects *containers*, not events. A container created before the range but touched inside
  it is returned with its full history, because a partial history reads as a misleading account of what
  happened to someone's property.

The response deliberately omits internal ids and the denormalised `current*` columns, and carries prison
codes, DPS location ids and staff usernames as raw values in dedicated attributes — the SAR report
template resolves them to names at render time.

#### The report template

`src/main/resources/sar/templates/V1__sar_template.mustache` is the report the prisoner actually receives.
It is Handlebars-flavoured mustache, and it carries no `<style>` block or `<html>` wrapper of its own — the
SAR service prepends its own stylesheet, so the template only uses the classes that stylesheet defines
(`title`, `summary-list`, `data-table`). Helpers such as `getPrisonName`, `getLocationNameByDpsId` and
`getUserLastName` turn the raw codes in the API response into names at render time; `optionalValue`
substitutes "No data held".

The prison number is deliberately not rendered — the SAR tool prints it in the header of every page.

`hmpps.sar.template.enabled` is set in `application.yml` rather than only in helm so that every profile
validates the path on startup. The library resolves it as a classpath resource in a `@PostConstruct` and
refuses to start if it is wrong, which is much better found in CI than in a deploy. The path itself is
overridden per environment (`HMPPS_SAR_TEMPLATE_PATH`) so a new version can be rolled out one environment
at a time — the granularity the SAR tool's register-before-deploy rule needs.

#### Changing any of this

Not a routine code change. It is governed by the
[Central SAR Change Control Process](https://dsdmoj.atlassian.net/wiki/spaces/NDSS/pages/6057492803), and
two ordering rules bite:

1. No code or template reaches preprod or prod until the Offender SAR team have signed off the test report.
2. The template must be registered with the SAR tool in an environment **before** the code deploys there.
   The tool hashes the pending template, and an unknown hash suspends the product — after which there are
   48 hours to fix it before the report is marked failed.

Ask the HAA team on `#haa-sar-functionality-change-request` to register a template. See epic MAPB-764.

## Documentation

| Doc | For |
| --- | --- |
| [Getting started](docs/getting-started.md) | **New to the project? Start here.** How the two repos fit together, and the state model behind property containers. |
| [Business overview](docs/business-overview.md) | What the service does and why, in plain English. |
| [Architecture](docs/architecture.md) | The whole service — both repos, diagrams, messaging, domain model. |
| [Technical implementation](docs/technical-implementation.md) | This API's internals: packages, patterns, dependencies. |
| [Establishment summary counts](docs/establishment-summary-counts.md) | How the summary tiles are counted. |
| [Operational runbooks](docs/runbook.md) | One-off/coordinated operational procedures (migrations, backfills). Not a general operations reference. |
| [Property returned or transferred tab](docs/property-returned-or-transferred-tab.md) | How the tab was built, and how each open design question was answered. |
| [Property snag issues](docs/property-snags.md) | The MAPB-709 snag backlog and the decisions taken on each. |

## Database schema

A browsable schema report is published from `main` to
[ministryofjustice.github.io/hmpps-prisoner-property-api/schema-spy-report](https://ministryofjustice.github.io/hmpps-prisoner-property-api/schema-spy-report/),
along with two CSV exports for the MOJ Data Catalogue:

| File | Contents |
|------|----------|
| `data-dictionary.csv` | Every table and column, with its description, sensitivity classification, type, nullability, PK and FK |
| `reference-data.csv` | The enum lookups. Every code in this schema resolves in Kotlin — there are no reference tables — so without this a consumer sees a `varchar` with no idea which values are legal |

The report shows every table and column, with types, nullability, primary and foreign keys, and ER
diagrams. Share these rather than a hand-written description when explaining the schema — to the
Analytical Platform team, for the Data Hub transition, or when working out what a subject access
request covers.

Everything is generated from a database built by Flyway, so it cannot drift from the migrations. To
regenerate it all locally:

```bash
docker compose -f docker-compose-schema-spy.yml up -d --wait
./gradlew -Pinit-db=true test --tests '*InitialiseDatabase' --tests '*ExportReferenceData'
docker run --rm --network host -v /tmp/schemaspy:/output schemaspy/schemaspy:6.2.4 \
  -t pgsql -host localhost -port 5432 -db prisoner_property -s public \
  -u prisoner_property -p prisoner_property -vizjs
scripts/generate-data-dictionary.sh
```

### Table and column descriptions

Descriptions live in the database as `COMMENT ON` statements, applied by
`db/migration/V15__schema_comments.sql`, so SchemaSpy, the CSV export and any Glue crawl all read the
same source of truth. Each column description ends with a sensitivity classification:

| Tag | Meaning |
| --- | --- |
| `[Sensitivity: NONE]` | Not personal data in itself |
| `[Sensitivity: PERSONAL]` | Personal data about a prisoner — identifies or locates them |
| `[Sensitivity: STAFF]` | Personal data about a member of staff, typically the username that acted |
| `[Sensitivity: SPECIAL-CATEGORY]` | UK GDPR Article 9 data, or offence data under Article 10 |
| `[Sensitivity: OFFICIAL-SENSITIVE]` | Not personal data, but damaging if disclosed |

`STAFF` is still personal data, and still in scope for a staff member's own subject access request. It is
separated from `PERSONAL` so an extract about prisoners can be reasoned about without staff columns
inflating the count, and so staff data can be dropped or pseudonymised independently.

The tag describes the column's own content, not the row's: every container and event belongs to a
prisoner via `property_container.prisoner_number`, so the whole record is personal data about that
prisoner however an individual column is tagged — which is what matters for a subject access request.
Nothing in this schema is special category.

In `data-dictionary.csv` the tag is split into its own `sensitivity` column and stripped from the
description, so the text reads cleanly and the classification can be filtered on.

**Any new table or column needs a `COMMENT ON`** in a migration — `SchemaCommentsTest` fails the build
otherwise. A later migration can add to or replace any comment at any time. Likewise a new enum value
needs a description in `ExportReferenceData`, which fails rather than exporting a blank row.

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
