package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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

  private lateinit var containerId: UUID

  @BeforeEach
  fun setUp() {
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
      // the stubbed prisoner is at MDI, this container is held at LEI
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
  fun `filters the prison list by storage location code resolved against locations-inside-prison`() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubFindByNumbers("A1234BC" to "LEI")
    prisonRegister.stubGetPrisons()
    locations.stubPostLocationsBatch(LOCATION_B.toString())
    locations.stubGetNonResidentialLocations("LEI", "PB5638" to LOCATION_B.toString())

    // matching code returns the container held in that location
    webTestClient.get().uri("/property-containers/prison/LEI?storageLocation=PB5638")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.content[0].containers[0].currentLocation").isEqualTo(LOCATION_B.toString())

    // a code that resolves to no location returns nothing
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
  }
}
