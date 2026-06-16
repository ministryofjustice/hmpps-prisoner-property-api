package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CombineContainersRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.DisposeContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.RemoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import java.time.LocalDate
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
  fun `rejects creating a container with a seal already used by an active container`() {
    repository.save(seedContainer(seal = "SEAL1"))

    webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(createRequest(sealNumber = "SEAL1"))
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `allows creating a container reusing a seal held only by a disposed container`() {
    repository.save(seedContainer(seal = "SEAL1", removalOutcome = RemovalOutcome.DISPOSED, removalDate = LocalDate.parse("2026-02-01")))

    webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(createRequest(sealNumber = "SEAL1"))
      .exchange()
      .expectStatus().isCreated
  }

  @Test
  fun `rejects amending a container's seal to one used by another active container`() {
    repository.save(seedContainer(prisonerNumber = "A1234BC", seal = "SEAL1"))
    val target = repository.save(seedContainer(prisonerNumber = "B2345CD", seal = "SEAL2")).id!!

    webTestClient.put().uri("/property-containers/{id}", target)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(updateRequest(sealNumber = "SEAL1"))
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `allows updating a container without changing its seal`() {
    val id = repository.save(seedContainer(seal = "SEAL1")).id!!

    webTestClient.put().uri("/property-containers/{id}", id)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(updateRequest(sealNumber = "SEAL1"))
      .exchange()
      .expectStatus().isOk
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
  fun `disposes a container, clears its location and publishes an updated event`() {
    val id = repository.save(seedContainer()).id!!

    webTestClient.post().uri("/property-containers/{id}/dispose", id)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(DisposeContainerRequest(disposalDate = LocalDate.parse("2026-09-15")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentStatus").isEqualTo("DISPOSED")
      .jsonPath("$.removalOutcome").isEqualTo("DISPOSED")
      .jsonPath("$.removalDate").isEqualTo("2026-09-15")
      .jsonPath("$.currentLocation").doesNotExist()

    verify(domainEventPublisher).publish(
      check {
        assertThat(it.eventType).isEqualTo("prison-property.container.updated")
        assertThat(it.additionalInformation?.get("changedFields")).isEqualTo(listOf("removalOutcome"))
      },
    )
  }

  @Test
  fun `rejects disposing a container that has already left active storage`() {
    val id = repository.save(seedContainer(removalOutcome = RemovalOutcome.RETURNED, removalDate = LocalDate.parse("2026-02-01"))).id!!

    webTestClient.post().uri("/property-containers/{id}/dispose", id)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(DisposeContainerRequest())
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `removes a container by returning it to the prisoner`() {
    val id = repository.save(seedContainer()).id!!

    webTestClient.post().uri("/property-containers/{id}/remove", id)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(RemoveContainerRequest(outcome = RemovalOutcome.RETURNED))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentStatus").isEqualTo("RETURNED")
      .jsonPath("$.removalOutcome").isEqualTo("RETURNED")
      .jsonPath("$.currentLocation").doesNotExist()
  }

  @Test
  fun `removes a container by transferring it to another prison`() {
    val id = repository.save(seedContainer()).id!!

    webTestClient.post().uri("/property-containers/{id}/remove", id)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(RemoveContainerRequest(outcome = RemovalOutcome.TRANSFERRED, toPrisonId = "MDI"))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.removalOutcome").isEqualTo("TRANSFERRED")
  }

  @Test
  fun `rejects transferring a container without a destination prison`() {
    val id = repository.save(seedContainer()).id!!

    webTestClient.post().uri("/property-containers/{id}/remove", id)
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(RemoveContainerRequest(outcome = RemovalOutcome.TRANSFERRED))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `combines two containers into a new sealed container and removes the sources`() {
    val a = repository.save(seedContainer(seal = "SEALA")).id!!
    val b = repository.save(seedContainer(seal = "SEALB")).id!!

    val created = webTestClient.post().uri("/property-containers/combine")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(CombineContainersRequest(sourceContainerIds = listOf(a, b), containerType = ContainerType.STANDARD, sealNumber = "NEWSEAL", internalLocationId = LOCATION))
      .exchange()
      .expectStatus().isCreated
      .expectBody(PropertyContainerDto::class.java)
      .returnResult().responseBody!!

    assertThat(created.currentSealNumber).isEqualTo("NEWSEAL")
    assertThat(created.currentStatus).isEqualTo(ContainerStatus.STORED)
    assertThat(repository.findById(a).get().removalOutcome).isEqualTo(RemovalOutcome.COMBINED)
    assertThat(repository.findById(b).get().removalOutcome).isEqualTo(RemovalOutcome.COMBINED)

    val captor = argumentCaptor<HmppsDomainEvent>()
    verify(domainEventPublisher, times(3)).publish(captor.capture())
    assertThat(captor.allValues.map { it.eventType })
      .containsExactly("prison-property.container.created", "prison-property.container.updated", "prison-property.container.updated")
  }

  @Test
  fun `rejects combining containers from different prisoners`() {
    val a = repository.save(seedContainer(prisonerNumber = "A1234BC", seal = "SEALA")).id!!
    val b = repository.save(seedContainer(prisonerNumber = "B2345CD", seal = "SEALB")).id!!

    webTestClient.post().uri("/property-containers/combine")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(CombineContainersRequest(sourceContainerIds = listOf(a, b), containerType = ContainerType.STANDARD, sealNumber = "NEWSEAL"))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `rejects combining into a seal already used by an active container`() {
    repository.save(seedContainer(prisonerNumber = "C3456DE", seal = "TAKEN"))
    val a = repository.save(seedContainer(seal = "SEALA")).id!!
    val b = repository.save(seedContainer(seal = "SEALB")).id!!

    webTestClient.post().uri("/property-containers/combine")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(CombineContainersRequest(sourceContainerIds = listOf(a, b), containerType = ContainerType.STANDARD, sealNumber = "TAKEN"))
      .exchange()
      .expectStatus().isEqualTo(409)
  }

  @Test
  fun `rejects combining fewer than two containers`() {
    val a = repository.save(seedContainer(seal = "SEALA")).id!!

    webTestClient.post().uri("/property-containers/combine")
      .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(CombineContainersRequest(sourceContainerIds = listOf(a), containerType = ContainerType.STANDARD, sealNumber = "NEWSEAL"))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `returns forbidden when disposing with the read-only role`() {
    webTestClient.post().uri("/property-containers/{id}/dispose", UUID.randomUUID())
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .bodyValue(DisposeContainerRequest())
      .exchange()
      .expectStatus().isForbidden
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

  private fun seedContainer(
    prisonerNumber: String = "A1234BC",
    seal: String = "SEAL1",
    removalOutcome: RemovalOutcome? = null,
    removalDate: LocalDate? = null,
  ): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
      currentSealNumber = seal,
      removalOutcome = removalOutcome,
      removalDate = removalDate,
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2026-01-01T09:00:00"), "A_USER", sealNumber = seal, toInternalLocationId = LOCATION, toStorageLocationType = StorageLocationType.INTERNAL),
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
