package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import java.time.LocalDateTime
import java.util.UUID

class PropertyContainerResourceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var cacheManager: CacheManager

  private lateinit var containerId: UUID

  @BeforeEach
  fun setUp() {
    // getPropertyLocations is @Cacheable and several tests here stub it with different locations for the same
    // prison - clear so each resolves against its own stub rather than a prior test's cached result.
    cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }
    containerId = repository.save(seedContainer()).id!!
  }

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `returns the property containers for a prisoner enriched with names`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner("A1234BC")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prisoner/A1234BC")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(1)
      .jsonPath("$[0].id").isEqualTo(containerId.toString())
      .jsonPath("$[0].prisonerNumber").isEqualTo("A1234BC")
      .jsonPath("$[0].prisonerName").isEqualTo("JOHN SMITH")
      .jsonPath("$[0].prisonId").isEqualTo("LEI")
      .jsonPath("$[0].prisonName").isEqualTo("Leeds (HMP)")
      // the stubbed prisoner is at MDI, this container is held at LEI - the current establishment is
      // still surfaced even though no property is held there
      .jsonPath("$[0].prisonerCurrentPrisonId").isEqualTo("MDI")
      .jsonPath("$[0].prisonerCurrentPrisonName").isEqualTo("Moorland (HMP & YOI)")
      .jsonPath("$[0].inPrisonersCurrentPrison").isEqualTo(false)
      .jsonPath("$[0].currentSealNumber").isEqualTo("SEAL002")
      .jsonPath("$[0].currentStatus").isEqualTo("STORED")
      .jsonPath("$[0].currentLocation").isEqualTo(LOCATION_B.toString())
      .jsonPath("$[0].locationDescription").isEqualTo("Reception Property Store")
  }

  @Test
  fun `filters the prisoner's containers by status`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner("A1234BC")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prisoner/A1234BC?status=DISPOSED")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(0)
  }

  @Test
  fun `returns the prison property as a page of prisoners with their enriched containers`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbers("A1234BC" to "MDI")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prison/LEI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content.length()").isEqualTo(1)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("A1234BC")
      .jsonPath("$.content[0].prisonerName").isEqualTo("JOHN SMITH")
      // the prisoner is now at MDI, the property stays at LEI
      .jsonPath("$.content[0].prisonerCurrentPrisonId").isEqualTo("MDI")
      .jsonPath("$.content[0].containers.length()").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].prisonId").isEqualTo("LEI")
      .jsonPath("$.content[0].containers[0].currentStatus").isEqualTo("STORED")
      .jsonPath("$.content[0].containers[0].currentLocation").isEqualTo(LOCATION_B.toString())
      .jsonPath("$.content[0].containers[0].locationDescription").isEqualTo("Reception Property Store")
      .jsonPath("$.content[0].containers[0].inPrisonersCurrentPrison").isEqualTo(false)
  }

  @Test
  fun `hides removed containers by default but reveals them when their status is requested`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbers("A1234BC" to "LEI")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    val disposed = seedContainer().apply {
      removalOutcome = uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome.DISPOSED
      removalDate = baseTime.toLocalDate()
      refreshDerivedState()
    }
    repository.save(disposed)

    // default: only the active container is returned (the disposed one is hidden)
    webTestClient.get().uri("/property-containers/prison/LEI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].containers.length()").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].currentStatus").isEqualTo("STORED")

    // requesting DISPOSED reveals the removed container
    webTestClient.get().uri("/property-containers/prison/LEI?status=DISPOSED")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].containers.length()").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].currentStatus").isEqualTo("DISPOSED")
  }

  @Test
  fun `filters the prison list by a storage location code or local name resolved against locations-inside-prison`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbers("A1234BC" to "LEI")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    locations.stubGetBoxLocations("LEI", listOf(Triple(LOCATION_B.toString(), "PB5638", "Reception Property Store")))

    // matching by code returns the container held in that location
    webTestClient.get().uri("/property-containers/prison/LEI?storageLocation=PB5638")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].currentLocation").isEqualTo(LOCATION_B.toString())

    // matching by local name resolves to the same box
    webTestClient.get().uri("/property-containers/prison/LEI?storageLocation=Reception Property Store")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].currentLocation").isEqualTo(LOCATION_B.toString())

    // a term that resolves to no location returns nothing
    webTestClient.get().uri("/property-containers/prison/LEI?storageLocation=UNKNOWN")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)
  }

  @Test
  fun `filters the prison list by multiple container types`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbers("A1234BC" to "LEI")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    repository.save(
      containerWithStatus("SEAL-VAL") { containerType = ContainerType.VALUABLES },
    )

    // both types for the same prisoner come back together
    webTestClient.get().uri("/property-containers/prison/LEI?containerType=STANDARD&containerType=VALUABLES")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].containers.length()").isEqualTo(2)

    // narrowing to one type returns only that container
    webTestClient.get().uri("/property-containers/prison/LEI?containerType=VALUABLES")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].containers.length()").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].containerType").isEqualTo("VALUABLES")
  }

  @Test
  fun `includeRemoved surfaces returned and disposed containers alongside active ones`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbers("A1234BC" to "LEI")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    repository.save(
      containerWithStatus("SEAL-DISPOSED") {
        removalOutcome = RemovalOutcome.DISPOSED
        removalDate = baseTime.toLocalDate()
      },
    )

    // default hides the disposed container
    webTestClient.get().uri("/property-containers/prison/LEI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].containers.length()").isEqualTo(1)

    // includeRemoved brings it back alongside the active one
    webTestClient.get().uri("/property-containers/prison/LEI?includeRemoved=true")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].containers.length()").isEqualTo(2)
  }

  @Test
  fun `free-text query matches a seal number across the prison list`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbers("A1234BC" to "LEI")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    locations.stubGetBoxLocations("LEI", listOf(Triple(LOCATION_B.toString(), "PB5638", "Reception Property Store")))

    // the seed container's seal is SEAL002
    webTestClient.get().uri("/property-containers/prison/LEI?query=SEAL002")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].currentSealNumber").isEqualTo("SEAL002")

    // a term matching no prisoner number, seal or location returns nothing
    webTestClient.get().uri("/property-containers/prison/LEI?query=NO-SUCH-TERM")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)
  }

  @Test
  fun `returns a property container by id`() {
    webTestClient.get().uri("/property-containers/{id}", containerId)
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.id").isEqualTo(containerId.toString())
      .jsonPath("$.containerType").isEqualTo("STANDARD")
  }

  @Test
  fun `returns not found for an unknown id`() {
    webTestClient.get().uri("/property-containers/{id}", UUID.randomUUID())
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `returns a container's events newest first`() {
    prisonRegister.stubGetPrisons()

    webTestClient.get().uri("/property-containers/{id}/events", containerId)
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(3)
      .jsonPath("$[0].eventType").isEqualTo("MOVED")
      .jsonPath("$[0].toInternalLocationId").isEqualTo(LOCATION_B.toString())
      // the container type is snapshotted on every event
      .jsonPath("$[0].containerType").isEqualTo("STANDARD")
      .jsonPath("$[2].containerType").isEqualTo("STANDARD")
      .jsonPath("$[1].eventType").isEqualTo("SEAL_CHANGED")
      .jsonPath("$[1].sealNumber").isEqualTo("SEAL002")
      .jsonPath("$[2].eventType").isEqualTo("CREATED_SEALED")
      .jsonPath("$[2].sealNumber").isEqualTo("SEAL001")
      .jsonPath("$[2].eventUserId").isEqualTo("USER1")
  }

  @Test
  fun `returns not found for the events of an unknown container`() {
    webTestClient.get().uri("/property-containers/{id}/events", UUID.randomUUID())
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `events returns forbidden without the read role`() {
    webTestClient.get().uri("/property-containers/{id}/events", containerId)
      .headers(setAuthorisation(roles = listOf("ROLE_WRONG")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `events returns unauthorized when no token is presented`() {
    webTestClient.get().uri("/property-containers/{id}/events", containerId)
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns a prisoner's whole-property timeline newest first with resolved names`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner("A1234BC")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    // Admission and transfer-in movement rows are sourced from prison-api (later-dated than the container
    // events, so they sit at the top of the newest-first list).
    prisonApi.stubGetPrisonTimeline(
      "A1234BC",
      admissions = listOf("LEI" to "2026-05-01T09:00:00"),
      transfers = listOf("MDI" to "2026-06-01T10:00:00"),
    )
    // A second container for the same prisoner: created, the prisoner then moved to MDI (received),
    // and it was finally transferred out. Its seal never changes, so every item shows SN880032.
    repository.save(transferredContainer())

    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // 3 seed events + 3 transferred-container events + 2 prison-api movements (admission + transfer-in)
      .jsonPath("$.length()").isEqualTo(8)
      // newest first: the transfer in to Moorland, then the admission to Leeds
      .jsonPath("$[0].itemType").isEqualTo("PRISONER_MOVEMENT")
      .jsonPath("$[0].movementKind").isEqualTo("TRANSFER_IN")
      .jsonPath("$[0].toPrisonName").isEqualTo("Moorland (HMP & YOI)")
      .jsonPath("$[0].systemGenerated").isEqualTo(true)
      .jsonPath("$[1].itemType").isEqualTo("PRISONER_MOVEMENT")
      .jsonPath("$[1].movementKind").isEqualTo("ADMISSION")
      .jsonPath("$[1].toPrisonName").isEqualTo("Leeds (HMP)")
      // then the container events, newest first: the transfer out, held at Leeds, moving to Moorland
      .jsonPath("$[2].itemType").isEqualTo("CONTAINER_EVENT")
      .jsonPath("$[2].eventType").isEqualTo("TRANSFERRED")
      .jsonPath("$[2].eventStatus").isEqualTo("TRANSFER")
      .jsonPath("$[2].actingEstablishmentName").isEqualTo("Leeds (HMP)")
      .jsonPath("$[2].sealNumber").isEqualTo("SN880032")
      .jsonPath("$[3].itemType").isEqualTo("CONTAINER_EVENT")
      .jsonPath("$[3].eventType").isEqualTo("PRISONER_RECEIVED")
      .jsonPath("$[3].eventStatus").isEqualTo("DUE_FOR_TRANSFER_OUT")
      // oldest item is the seed container's creation: seal-as-of-event is the original seal, while the
      // container's *current* seal (in the expandable details) is the later one
      .jsonPath("$[7].eventType").isEqualTo("CREATED_SEALED")
      .jsonPath("$[7].sealNumber").isEqualTo("SEAL001")
      .jsonPath("$[7].containerSealNumber").isEqualTo("SEAL002")
      .jsonPath("$[7].containerLocationDescription").isEqualTo("Reception Property Store")
  }

  @Test
  fun `timeline shows prison-api admissions even for a prisoner with no property`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner("A1234BC")
    prisonRegister.stubGetPrisons()
    prisonApi.stubGetPrisonTimeline("A1234BC", admissions = listOf("LEI" to "2026-05-01T09:00:00"))
    // No property saved for A1234BC beyond the seed - remove it so the person is property-less.
    repository.deleteAll()

    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // just the admission - the timeline is no longer empty when the person has no property
      .jsonPath("$.length()").isEqualTo(1)
      .jsonPath("$[0].itemType").isEqualTo("PRISONER_MOVEMENT")
      .jsonPath("$[0].movementKind").isEqualTo("ADMISSION")
      .jsonPath("$[0].toPrisonName").isEqualTo("Leeds (HMP)")
  }

  @Test
  fun `timeline includes a scheduled-for-release marker, preferring the confirmed release date`() {
    hmppsAuth.stubGrantToken()
    // Confirmed date is preferred over the conditional (sentence-calculated) one.
    prisonerSearch.stubGetPrisoner("A1234BC", confirmedReleaseDate = "2026-08-12", conditionalReleaseDate = "2026-05-01")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // 3 seed events + 1 synthesised scheduled-for-release marker
      .jsonPath("$.length()").isEqualTo(4)
      // the release date is in the future, so the marker sits at the top of the newest-first list
      .jsonPath("$[0].itemType").isEqualTo("SCHEDULED_FOR_RELEASE")
      .jsonPath("$[0].eventDate").isEqualTo("2026-08-12")
      .jsonPath("$[0].prisonerName").isEqualTo("JOHN SMITH")
      .jsonPath("$[0].systemGenerated").isEqualTo(true)
  }

  @Test
  fun `timeline falls back to the conditional release date when there is no confirmed date`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner("A1234BC", conditionalReleaseDate = "2026-09-01")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(4)
      .jsonPath("$[0].itemType").isEqualTo("SCHEDULED_FOR_RELEASE")
      .jsonPath("$[0].eventDate").isEqualTo("2026-09-01")
  }

  @Test
  fun `timeline omits the scheduled-for-release marker once the prisoner has actually been released`() {
    hmppsAuth.stubGrantToken()
    // Released (prisonId OUT + REL): the real release is already recorded, so no forward-looking marker.
    prisonerSearch.stubGetPrisoner("A1234BC", prisonId = "OUT", lastMovementTypeCode = "REL", confirmedReleaseDate = "2026-08-12")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // only the 3 seed container events - no synthesised marker
      .jsonPath("$.length()").isEqualTo(3)
      .jsonPath("$[0].itemType").isEqualTo("CONTAINER_EVENT")
      .jsonPath("$[1].itemType").isEqualTo("CONTAINER_EVENT")
      .jsonPath("$[2].itemType").isEqualTo("CONTAINER_EVENT")
  }

  @Test
  fun `timeline excludes archived containers`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner("A1234BC")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    repository.save(seedContainer().apply { archived = true })

    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // only the (non-archived) seed container's 3 events, not the archived container's
      .jsonPath("$.length()").isEqualTo(3)
  }

  @Test
  fun `timeline is empty for a prisoner with no property and no movements`() {
    hmppsAuth.stubGrantToken()
    prisonRegister.stubGetPrisons()
    prisonerSearch.stubGetPrisonerNotFound("Z9999ZZ")
    prisonApi.stubGetPrisonTimelineEmpty("Z9999ZZ")

    webTestClient.get().uri("/property-containers/prisoner/Z9999ZZ/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(0)
  }

  @Test
  fun `timeline returns bad request when the prisoner number is invalid`() {
    webTestClient.get().uri("/property-containers/prisoner/INVALID/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `timeline returns forbidden without the read role`() {
    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_WRONG")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `timeline returns unauthorized when no token is presented`() {
    webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns unauthorized when no token is presented`() {
    webTestClient.get().uri("/property-containers/prisoner/A1234BC")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns forbidden when the wrong role is presented`() {
    webTestClient.get().uri("/property-containers/prisoner/A1234BC")
      .headers(setAuthorisation(roles = listOf("ROLE_WRONG")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `returns bad request when the prisoner number is invalid`() {
    webTestClient.get().uri("/property-containers/prisoner/INVALID")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `returns bad request when the prison id is invalid`() {
    webTestClient.get().uri("/property-containers/prison/ZZZ")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `returns the prison's box locations with container counts, alphabetically by default`() {
    // the seeded container's current location is LOCATION_B
    hmppsAuth.stubGrantToken()
    locations.stubGetBoxLocations(
      "LEI",
      listOf(
        Triple(LOCATION_B.toString(), "PROP2", "Box Two"),
        Triple(LOCATION_A.toString(), "PROP1", "Box One"),
        Triple(EMPTY_BOX.toString(), "PROP3", "Box Three"),
      ),
    )

    webTestClient.get().uri("/property-containers/prison/LEI/box-locations")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(3)
      .jsonPath("$.content.length()").isEqualTo(3)
      .jsonPath("$.content[0].name").isEqualTo("Box One")
      .jsonPath("$.content[0].containerCount").isEqualTo(0)
      .jsonPath("$.content[1].name").isEqualTo("Box Three")
      .jsonPath("$.content[1].containerCount").isEqualTo(0)
      .jsonPath("$.content[2].name").isEqualTo("Box Two")
      .jsonPath("$.content[2].containerCount").isEqualTo(1)
  }

  @Test
  fun `filters and paginates the prison's box locations by search query`() {
    hmppsAuth.stubGrantToken()
    locations.stubGetBoxLocations(
      "LEI",
      listOf(
        Triple(LOCATION_A.toString(), "PROP1", "Reception Store"),
        Triple(LOCATION_B.toString(), "PROP2", "Wing Store"),
        Triple(EMPTY_BOX.toString(), "PROP3", "Reserve Store"),
      ),
    )

    webTestClient.get().uri("/property-containers/prison/LEI/box-locations?query=re*store&size=1")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // "re*store" matches "Reception Store" and "Reserve Store" (name), paged one at a time
      .jsonPath("$.totalElements").isEqualTo(2)
      .jsonPath("$.content.length()").isEqualTo(1)
      .jsonPath("$.content[0].name").isEqualTo("Reception Store")
  }

  @Test
  fun `returns the prison's box locations with most available spaces first when sorted`() {
    hmppsAuth.stubGrantToken()
    locations.stubGetBoxLocations(
      "LEI",
      listOf(
        Triple(LOCATION_B.toString(), "PROP2", "Box Two"),
        Triple(EMPTY_BOX.toString(), "PROP3", "Box Three"),
      ),
    )

    webTestClient.get().uri("/property-containers/prison/LEI/box-locations?sort=MOST_AVAILABLE")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // both have capacity 10; Box Three is empty (10 spaces), Box Two holds the seeded container (9 spaces)
      .jsonPath("$.content[0].name").isEqualTo("Box Three")
      .jsonPath("$.content[0].availableSpaces").isEqualTo(10)
      .jsonPath("$.content[1].name").isEqualTo("Box Two")
      .jsonPath("$.content[1].availableSpaces").isEqualTo(9)
  }

  @Test
  fun `excludes storage locations that are full from the box locations`() {
    hmppsAuth.stubGrantToken()
    // Every location has capacity 1; LOCATION_B already holds the seeded container, so it is full and dropped.
    locations.stubGetBoxLocations(
      "LEI",
      listOf(
        Triple(LOCATION_B.toString(), "PROP2", "Full Box"),
        Triple(EMPTY_BOX.toString(), "PROP3", "Empty Box"),
      ),
      capacity = 1,
    )

    webTestClient.get().uri("/property-containers/prison/LEI/box-locations")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].name").isEqualTo("Empty Box")
      .jsonPath("$.content[0].availableSpaces").isEqualTo(1)
  }

  @Test
  fun `box locations returns forbidden without the read role`() {
    webTestClient.get().uri("/property-containers/prison/LEI/box-locations")
      .headers(setAuthorisation(roles = listOf("ROLE_WRONG")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `box locations returns bad request for an invalid prison id`() {
    webTestClient.get().uri("/property-containers/prison/ZZZ/box-locations")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `describes the prisoner's movement status in the prison list - released`() {
    hmppsAuth.stubGrantToken()
    // the prisoner has been released (prisonId OUT, last movement REL) but their property remains at LEI
    prisonerSearch.stubFindByNumbersWithMovement(Triple("A1234BC", "OUT", "REL"))
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prison/LEI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].prisonerMovementStatus").isEqualTo("RELEASED")
      .jsonPath("$.content[0].containers[0].prisonerMovementStatus").isEqualTo("RELEASED")
  }

  @Test
  fun `describes the prisoner's movement status in the prison list - in transit`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbersWithMovement(Triple("A1234BC", "TRN", "TRN"))
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())

    webTestClient.get().uri("/property-containers/prison/LEI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].prisonerMovementStatus").isEqualTo("IN_TRANSIT")
  }

  @Test
  fun `filters the prison list by person location, resolved from prisoner-search`() {
    hmppsAuth.stubGrantToken()
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    // the seeded A1234BC container is held at LEI, but the prisoner has moved on to MDI
    prisonerSearch.stubFindByNumbers("A1234BC" to "MDI")

    // "in this establishment" (LEI): the prisoner is now at MDI, so nothing matches
    webTestClient.get().uri("/property-containers/prison/LEI?personLocation=IN_ESTABLISHMENT")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)

    // "no longer in this establishment": the property is still here but the person has left
    webTestClient.get().uri("/property-containers/prison/LEI?personLocation=LEFT_ESTABLISHMENT")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].prisonerNumber").isEqualTo("A1234BC")
  }

  @Test
  fun `returns the prison property summary counts`() {
    hmppsAuth.stubGrantToken()
    // setUp already seeded one STORED container at LEI; add one due for disposal and one due to transfer out
    repository.save(containerWithStatus("SEALD") { proposedDisposalDate = baseTime.toLocalDate() })
    repository.save(
      containerWithStatus("SEALT") {
        events.add(PropertyEvent(this, PropertyEventType.PRISONER_RECEIVED, baseTime.plusHours(1), "USER1"))
      },
    )
    locations.stubGetBoxLocations(
      "LEI",
      listOf(
        Triple(LOCATION_A.toString(), "PROP1", "Box One"),
        Triple(LOCATION_B.toString(), "PROP2", "Box Two"),
        Triple(EMPTY_BOX.toString(), "PROP3", "Box Three"),
      ),
      capacity = 1,
    )

    webTestClient.get().uri("/property-containers/prison/LEI/summary")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      // Each of the 3 boxes has capacity 1; LOCATION_B holds the one seeded container (0 spaces), the other
      // two are empty (1 space each), so 2 spaces remain across the prison.
      .jsonPath("$.availableStorageSpaces").isEqualTo(2)
      .jsonPath("$.storedOnSite").isEqualTo(1)
      .jsonPath("$.dueToTransferOut").isEqualTo(1)
      // SEALD's proposed disposal date (2026-01-01) has arisen.
      .jsonPath("$.dueToBeDisposed").isEqualTo(1)
      .jsonPath("$.dueToBeReturned").isEqualTo(0)
  }

  @Test
  fun `summary returns forbidden without the read role`() {
    webTestClient.get().uri("/property-containers/prison/LEI/summary")
      .headers(setAuthorisation(roles = listOf("ROLE_WRONG")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `summary returns bad request for an invalid prison id`() {
    webTestClient.get().uri("/property-containers/prison/ZZZ/summary")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isBadRequest
  }

  private fun containerWithStatus(seal: String, configure: PropertyContainer.() -> Unit): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = seal,
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, baseTime, "USER1", sealNumber = seal),
    )
    container.configure()
    container.refreshDerivedState()
    return container
  }

  private fun seedContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = "SEAL002",
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, baseTime, "USER1", sealNumber = "SEAL001", toInternalLocationId = LOCATION_A),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.SEAL_CHANGED, baseTime.plusHours(1), "USER1", sealNumber = "SEAL002"),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.MOVED, baseTime.plusHours(2), "USER1", toInternalLocationId = LOCATION_B),
    )
    container.refreshDerivedState()
    return container
  }

  /**
   * A second container for A1234BC that has been transferred out: created and sealed at Leeds, flagged due for
   * transfer out when the prisoner was received at Moorland, then transferred. Later-dated than [seedContainer]
   * so its events sort to the top of the timeline.
   */
  private fun transferredContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.VALUABLES,
      createdByUserId = "USER1",
      currentSealNumber = "SN880032",
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, baseTime.plusDays(1), "USER1", sealNumber = "SN880032", toPrisonId = "LEI"),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.PRISONER_RECEIVED, baseTime.plusDays(2), "PRISONER_PROPERTY_API", fromPrisonId = "LEI", toPrisonId = "MDI"),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.TRANSFERRED, baseTime.plusDays(3), "USER2", eventDate = baseTime.plusDays(3).toLocalDate(), fromPrisonId = "LEI", toPrisonId = "MDI"),
    )
    container.removalOutcome = RemovalOutcome.TRANSFERRED
    container.removalDate = baseTime.plusDays(3).toLocalDate()
    container.refreshDerivedState()
    return container
  }

  private companion object {
    private val baseTime: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val EMPTY_BOX: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
  }
}
