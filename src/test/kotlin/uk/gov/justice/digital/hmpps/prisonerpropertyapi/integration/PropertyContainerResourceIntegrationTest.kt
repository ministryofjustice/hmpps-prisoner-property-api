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
  fun `returns the property containers for a prisoner`() {
    webTestClient.get().uri("/property-containers/prisoner/A1234BC")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(1)
      .jsonPath("$[0].id").isEqualTo(containerId.toString())
      .jsonPath("$[0].prisonerNumber").isEqualTo("A1234BC")
      .jsonPath("$[0].currentSealNumber").isEqualTo("SEAL002")
      .jsonPath("$[0].currentStatus").isEqualTo("STORED")
      .jsonPath("$[0].currentLocation").isEqualTo(LOCATION_B.toString())
  }

  @Test
  fun `returns the property containers for a prison`() {
    webTestClient.get().uri("/property-containers/prison/LEI")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(1)
      .jsonPath("$[0].prisonId").isEqualTo("LEI")
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
    return container
  }

  private companion object {
    private val baseTime: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
  }
}
