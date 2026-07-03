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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
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
    // getLocationsByType is @Cacheable and several tests here stub it with different boxes for the same
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
    webTestClient.get().uri("/property-containers/{id}/events", containerId)
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(3)
      .jsonPath("$[0].eventType").isEqualTo("MOVED")
      .jsonPath("$[0].toInternalLocationId").isEqualTo(LOCATION_B.toString())
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
  fun `returns the prison's box locations emptiest first when sorted by container count`() {
    hmppsAuth.stubGrantToken()
    locations.stubGetBoxLocations(
      "LEI",
      listOf(
        Triple(LOCATION_B.toString(), "PROP2", "Box Two"),
        Triple(EMPTY_BOX.toString(), "PROP3", "Box Three"),
      ),
    )

    webTestClient.get().uri("/property-containers/prison/LEI/box-locations?sort=FEWEST_CONTAINERS")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].name").isEqualTo("Box Three")
      .jsonPath("$.content[0].containerCount").isEqualTo(0)
      .jsonPath("$.content[1].name").isEqualTo("Box Two")
      .jsonPath("$.content[1].containerCount").isEqualTo(1)
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

  private companion object {
    private val baseTime: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val EMPTY_BOX: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
  }
}
