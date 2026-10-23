package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerTimelineItemDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.ReceiveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PropertyContainerReceiveIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @BeforeEach
  fun stubLookups() {
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner("A1234BC", prisonId = "MDI")
    prisonRegister.stubGetPrisons()
    locations.stubGetPropertyLocation(LOCATION.toString())
    locations.stubPostLocationsBatchNamed(LOCATION.toString() to "Receiving store", OLD_LOCATION.toString() to "Sending store")
  }

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `receives a due container in place and returns updated details with a durable receipt timeline event`() {
    val source = repository.save(incoming())
    val before = LocalDateTime.now()

    val received = receive(source.id!!)
      .expectStatus().isOk
      .expectBody(PropertyContainerDto::class.java)
      .returnResult().responseBody!!

    assertThat(repository.count()).isEqualTo(1)
    assertThat(received.id).isEqualTo(source.id)
    assertThat(received.prisonId).isEqualTo("MDI")
    assertThat(received.currentSealNumber).isEqualTo("5678")
    assertThat(received.currentStatus).isEqualTo(ContainerStatus.STORED)
    assertThat(received.currentLocation).isEqualTo(LOCATION)
    assertThat(received.currentLocationType).isEqualTo(StorageLocationType.INTERNAL)
    assertThat(received.createdByUserId).isEqualTo("ORIGINAL_USER")
    assertThat(received.createDateTime).isEqualTo(CREATED)
    assertThat(received.containerType).isEqualTo(ContainerType.STANDARD)
    assertThat(received.proposedDisposalDate).isEqualTo(LocalDate.parse("2099-01-01"))
    assertThat(received.removalOutcome).isNull()
    assertThat(received.removalDate).isNull()

    val persisted = repository.findById(source.id!!).orElseThrow()
    assertThat(persisted.events).hasSize(3)
    assertThat(persisted.currentStatusValue).isEqualTo(ContainerStatus.STORED)
    assertThat(persisted.currentInternalLocationId).isEqualTo(LOCATION)
    assertThat(persisted.receivingPrisonId).isNull()
    val receipt = persisted.events.single { it.eventType == PropertyEventType.RECEIVED }
    assertThat(receipt.eventUserId).isEqualTo("RECEPTION")
    assertThat(receipt.eventDateTime).isBetween(before, LocalDateTime.now())
    assertThat(receipt.fromPrisonId).isEqualTo("LEI")
    assertThat(receipt.toPrisonId).isEqualTo("MDI")
    assertThat(receipt.sealNumber).isEqualTo("5678")
    assertThat(receipt.toInternalLocationId).isEqualTo(LOCATION)
    assertThat(receipt.toStorageLocationType).isEqualTo(StorageLocationType.INTERNAL)
    assertThat(publishedEventsFor(source.id!!)).singleElement().satisfies({
      assertThat(it.eventType).isEqualTo("prison-property.container.updated")
      assertThat(it.changedFields).containsExactly("prisonId", "location", "currentStatus", "receivingPrisonId")
    })

    val timeline = webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBodyList(PrisonerTimelineItemDto::class.java)
      .returnResult().responseBody!!
    val item = timeline.single { it.eventType == PropertyEventType.RECEIVED }
    assertThat(item.eventDateTime).isEqualTo(receipt.eventDateTime)
    assertThat(item.eventUserId).isEqualTo("RECEPTION")
    assertThat(item.systemGenerated).isFalse()
    assertThat(item.fromPrisonName).isEqualTo("Leeds (HMP)")
    assertThat(item.toPrisonName).isEqualTo("Moorland (HMP & YOI)")
    assertThat(item.actingEstablishmentName).isEqualTo("Moorland (HMP & YOI)")
    assertThat(item.sealNumber).isEqualTo("5678")
    assertThat(item.containerLocationDescription).isEqualTo("Receiving store")
    assertThat(item.eventStatus).isEqualTo(ContainerStatus.STORED)
    val original = timeline.single { it.eventType == PropertyEventType.CREATED_SEALED }
    assertThat(original.actingEstablishmentName).isEqualTo("Leeds (HMP)")
    assertThat(original.containerLocationDescription).isEqualTo("Sending store")
  }

  @Test
  fun `receipt returns the basic container without calling enrichment services`() {
    prisonerSearch.resetAll()
    prisonRegister.resetAll()
    locations.resetAll()
    val id = repository.save(incoming()).id!!

    receive(id, request().copy(internalLocationId = null))
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.id").isEqualTo(id.toString())
      .jsonPath("$.prisonId").isEqualTo("MDI")
      .jsonPath("$.prisonerName").doesNotExist()
      .jsonPath("$.prisonName").doesNotExist()
      .jsonPath("$.inPrisonersCurrentPrison").doesNotExist()
      .jsonPath("$.locationDescription").doesNotExist()

    assertThat(prisonerSearch.allServeEvents).isEmpty()
    assertThat(prisonRegister.allServeEvents).isEmpty()
    assertThat(locations.allServeEvents).isEmpty()
  }

  @Test
  fun `receives an unreconciled transferred container and clears its removal state`() {
    val source = repository.save(incoming(transferred = true))
    receive(source.id!!)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.id").isEqualTo(source.id.toString())
      .jsonPath("$.currentStatus").isEqualTo("STORED")
      .jsonPath("$.removalOutcome").doesNotExist()
      .jsonPath("$.removalDate").doesNotExist()
      .jsonPath("$.receivingPrisonId").doesNotExist()

    val persisted = repository.findById(source.id!!).orElseThrow()
    assertThat(repository.count()).isEqualTo(1)
    assertThat(persisted.events.single { it.eventType == PropertyEventType.TRANSFERRED }.eventDateTime).isEqualTo(TRANSFERRED)
    assertThat(persisted.removalOutcome).isNull()
    assertThat(persisted.receivingPrison()).isNull()
    assertThat(publishedEventsFor(source.id!!).single().changedFields)
      .containsExactly("prisonId", "location", "removalOutcome", "currentStatus", "receivingPrisonId")
  }

  @Test
  fun `receives ordinary stored property even when the sender never flagged a transfer`() {
    val source = incoming().apply {
      events.removeIf { it.eventType == PropertyEventType.PRISONER_RECEIVED }
      refreshDerivedState()
    }
    receive(repository.save(source).id!!).expectStatus().isOk
    assertThat(repository.count()).isEqualTo(1)
  }

  @Test
  fun `receipt without a location clears the sending location and preserves its historical establishment`() {
    val source = incoming().apply {
      events.clear()
      events.add(PropertyEvent(this, PropertyEventType.CREATED_SEALED, CREATED, "ORIGINAL_USER", sealNumber = "5678", toInternalLocationId = OLD_LOCATION))
      refreshDerivedState()
    }
    val id = repository.save(source).id!!
    receive(id, request().copy(internalLocationId = null))
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentLocation").doesNotExist()
      .jsonPath("$.currentLocationType").doesNotExist()
      .jsonPath("$.locationDescription").doesNotExist()

    val timeline = webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange().expectStatus().isOk
      .expectBodyList(PrisonerTimelineItemDto::class.java).returnResult().responseBody!!
    assertThat(timeline.single { it.eventType == PropertyEventType.CREATED_SEALED }.actingEstablishmentName).isEqualTo("Leeds (HMP)")
    assertThat(timeline.single { it.eventType == PropertyEventType.RECEIVED }.containerLocationDescription).isNull()
  }

  @Test
  fun `receives property offsite at Branston`() {
    receive(repository.save(incoming()).id!!, request().copy(internalLocationId = null, locationType = StorageLocationType.BRANSTON))
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentLocationType").isEqualTo("BRANSTON")
      .jsonPath("$.currentLocation").doesNotExist()
  }

  @Test
  fun `concurrent confirmations record only one receipt`() {
    val id = repository.save(incoming()).id!!
    Executors.newFixedThreadPool(2).use { executor ->
      val start = CountDownLatch(1)
      val confirmations = List(2) {
        executor.submit<Int> {
          start.await()
          receive(id).expectBody().returnResult().status.value()
        }
      }
      start.countDown()
      assertThat(confirmations.map { it.get(30, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 400)
    }
    assertThat(repository.findById(id).orElseThrow().events.count { it.eventType == PropertyEventType.RECEIVED }).isEqualTo(1)
    assertThat(publishedEventsFor(id)).hasSize(1)
  }

  @Test
  fun `receipt records the submitted current seal without creating another container`() {
    val id = repository.save(incoming()).id!!
    receive(id, request().copy(sealNumber = "NEWSEAL"))
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentSealNumber").isEqualTo("NEWSEAL")
    val persisted = repository.findById(id).orElseThrow()
    assertThat(persisted.events.single { it.eventType == PropertyEventType.RECEIVED }.sealNumber).isEqualTo("NEWSEAL")
    assertThat(persisted.events.single { it.eventType == PropertyEventType.CREATED_SEALED }.sealNumber).isEqualTo("5678")
    assertThat(repository.count()).isEqualTo(1)
  }

  @Test
  fun `a repeated receipt is rejected without recording another event`() {
    val id = repository.save(incoming()).id!!
    receive(id).expectStatus().isOk
    receive(id).expectStatus().isBadRequest
    assertThat(repository.findById(id).orElseThrow().events.count { it.eventType == PropertyEventType.RECEIVED }).isEqualTo(1)
    assertThat(publishedEventsFor(id)).hasSize(1)
    assertThat(repository.count()).isEqualTo(1)
  }

  @ParameterizedTest
  @EnumSource(value = RemovalOutcome::class, names = ["TRANSFERRED"], mode = EnumSource.Mode.EXCLUDE)
  fun `rejects containers removed for reasons other than transfer`(outcome: RemovalOutcome) {
    val id = repository.save(
      incoming().apply {
        removalOutcome = outcome
        refreshDerivedState()
      },
    ).id!!
    receive(id).expectStatus().isEqualTo(409)
    assertThat(repository.findById(id).orElseThrow().prisonId).isEqualTo("LEI")
    assertNoEventsPublished()
  }

  @Test
  fun `rejects a transfer already reconciled against another record`() {
    val id = repository.save(
      incoming(transferred = true).apply {
        latestTransferEvent()!!.relatedContainerId = UUID.randomUUID()
        refreshDerivedState()
      },
    ).id!!
    receive(id).expectStatus().isEqualTo(409)
    assertNoEventsPublished()
  }

  @Test
  fun `rejects an active seal belonging to another container`() {
    val id = repository.save(incoming(transferred = true)).id!!
    repository.save(incoming().apply { prisonerNumber = "B1234CD" })
    receive(id).expectStatus().isEqualTo(409)
    assertThat(repository.findById(id).orElseThrow().removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    assertNoEventsPublished()
  }

  @Test
  fun `rejects invalid or full storage locations without changing the container`() {
    val id = repository.save(incoming()).id!!
    locations.stubGetPropertyLocationNotFound(LOCATION.toString())
    receive(id).expectStatus().isBadRequest
    locations.stubGetPropertyLocation(LOCATION.toString(), propertyCapacity = 0)
    receive(id).expectStatus().isBadRequest
    assertThat(repository.findById(id).orElseThrow().prisonId).isEqualTo("LEI")
    assertNoEventsPublished()
  }

  @Test
  fun `rejects a storage location at a different establishment`() {
    val id = repository.save(incoming()).id!!
    receive(id, request().copy(receivingPrisonId = "IWI")).expectStatus().isBadRequest
    assertThat(repository.findById(id).orElseThrow().prisonId).isEqualTo("LEI")
    assertNoEventsPublished()
  }

  @Test
  fun `rejects contradictory storage location details`() {
    val id = repository.save(incoming()).id!!
    receive(id, request().copy(locationType = StorageLocationType.BRANSTON)).expectStatus().isBadRequest
    assertNoEventsPublished()
  }

  @Test
  fun `requires a receiving establishment and seal number`() {
    val id = repository.save(incoming()).id!!
    receive(id, request().copy(receivingPrisonId = " ")).expectStatus().isBadRequest
    receive(id, request().copy(sealNumber = " ")).expectStatus().isBadRequest
    assertNoEventsPublished()
  }

  @Test
  fun `returns not found for an unknown container`() {
    receive(UUID.randomUUID()).expectStatus().isNotFound
    assertNoEventsPublished()
  }

  @Test
  fun `requires authentication and the write role`() {
    val id = repository.save(incoming()).id!!
    webTestClient.post().uri("/property-containers/{id}/receive", id)
      .bodyValue(request()).exchange().expectStatus().isUnauthorized
    webTestClient.post().uri("/property-containers/{id}/receive", id)
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .bodyValue(request()).exchange().expectStatus().isForbidden
    assertNoEventsPublished()
  }

  private fun receive(id: UUID, body: ReceiveContainerRequest = request()) = webTestClient.post()
    .uri("/property-containers/{id}/receive", id)
    .headers(setAuthorisation(username = "RECEPTION", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
    .bodyValue(body)
    .exchange()

  private fun request() = ReceiveContainerRequest(receivingPrisonId = "MDI", sealNumber = "5678", internalLocationId = LOCATION)

  private fun incoming(transferred: Boolean = false): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "ORIGINAL_USER",
      createDateTime = CREATED,
      currentSealNumber = "5678",
      proposedDisposalDate = LocalDate.parse("2099-01-01"),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, CREATED, "ORIGINAL_USER", sealNumber = "5678", toPrisonId = "LEI", toInternalLocationId = OLD_LOCATION),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.PRISONER_RECEIVED, CREATED.plusDays(1), "PRISONER_PROPERTY_API", fromPrisonId = "LEI", toPrisonId = "MDI"),
    )
    if (transferred) {
      container.events.add(
        PropertyEvent(container, PropertyEventType.TRANSFERRED, TRANSFERRED, "SENDING_USER", fromPrisonId = "LEI", toPrisonId = "MDI"),
      )
      container.removalOutcome = RemovalOutcome.TRANSFERRED
      container.removalDate = TRANSFERRED.toLocalDate()
    }
    container.refreshDerivedState()
    return container
  }

  private companion object {
    val LOCATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val OLD_LOCATION: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val CREATED: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    val TRANSFERRED: LocalDateTime = LocalDateTime.parse("2026-02-01T09:00:00")
  }
}
