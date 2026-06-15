package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.check
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import java.time.LocalDateTime
import java.util.UUID

class PropertyContainerWriteIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @MockitoSpyBean
  private lateinit var domainEventPublisher: DomainEventPublisher

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `creates a container, persists it with the authenticated user, and publishes a created event`() {
    val created = webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(createRequest())
      .exchange()
      .expectStatus().isCreated
      .expectBody(PropertyContainerDto::class.java)
      .returnResult().responseBody!!

    assertThat(created.prisonerNumber).isEqualTo("A1234BC")
    assertThat(created.containerType).isEqualTo(ContainerType.STANDARD)
    assertThat(created.currentSealNumber).isEqualTo("SEAL1")
    assertThat(created.currentLocationType).isEqualTo(StorageLocationType.INTERNAL)
    assertThat(created.createdByUserId).isEqualTo("A_USER")

    // persisted - readable back
    webTestClient.get().uri("/property-containers/{id}", created.id)
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentSealNumber").isEqualTo("SEAL1")

    verify(domainEventPublisher).publish(
      check {
        assertThat(it.eventType).isEqualTo("prison-property.container.created")
        assertThat(it.prisonerNumber).isEqualTo("A1234BC")
      },
    )
  }

  @Test
  fun `updates a container and publishes an updated event`() {
    val id = repository.save(seedContainer()).id!!

    webTestClient.put().uri("/property-containers/{id}", id)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(updateRequest(sealNumber = "SEAL2"))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentSealNumber").isEqualTo("SEAL2")

    verify(domainEventPublisher).publish(
      check {
        assertThat(it.eventType).isEqualTo("prison-property.container.updated")
        assertThat(it.additionalInformation?.get("changedFields")).isEqualTo(listOf("sealNumber"))
      },
    )
  }

  @Test
  fun `returns not found when updating an unknown container`() {
    webTestClient.put().uri("/property-containers/{id}", UUID.randomUUID())
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(updateRequest())
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `returns bad request when the body is invalid`() {
    webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(createRequest(sealNumber = ""))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `returns unauthorized when no token is presented`() {
    webTestClient.post().uri("/property-containers")
      .bodyValue(createRequest())
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns forbidden when creating with the read-only role`() {
    webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .bodyValue(createRequest())
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `returns forbidden when updating with the read-only role`() {
    webTestClient.put().uri("/property-containers/{id}", UUID.randomUUID())
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .bodyValue(updateRequest())
      .exchange()
      .expectStatus().isForbidden
  }

  private fun seedContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2026-01-01T09:00:00"), "A_USER", sealNumber = "SEAL1", toInternalLocationId = LOCATION, toStorageLocationType = StorageLocationType.INTERNAL),
    )
    return container
  }

  private fun createRequest(sealNumber: String = "SEAL1") = CreatePropertyContainerRequest(
    prisonerNumber = "A1234BC",
    prisonId = "LEI",
    containerType = ContainerType.STANDARD,
    sealNumber = sealNumber,
    internalLocationId = LOCATION,
  )

  private fun updateRequest(sealNumber: String = "SEAL1") = UpdatePropertyContainerRequest(
    containerType = ContainerType.STANDARD,
    sealNumber = sealNumber,
    internalLocationId = LOCATION,
  )

  private companion object {
    private val LOCATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
  }
}
