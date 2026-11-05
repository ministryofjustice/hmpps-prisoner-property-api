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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
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
  fun `incoming lookup normalizes seals and prisons and can be followed by receipt with a changed seal`() {
    val id = repository.save(incoming().apply { currentSealNumber = " aBc5678 " }).id!!
    val found = lookup(seal = " ABC5678 ", prison = " mdi ")
      .expectStatus().isOk
      .expectBody(PropertyContainerDto::class.java).returnResult().responseBody!!
    assertThat(found.id).isEqualTo(id)
    assertThat(found.prisonId).isEqualTo("LEI")
    assertThat(repository.findById(id).orElseThrow().events).hasSize(2)
    assertNoEventsPublished()

    receive(found.id, request().copy(sealNumber = "NEWSEAL")).expectStatus().isOk
    lookup(seal = "NEWSEAL").expectStatus().isNotFound
    assertThat(repository.count()).isEqualTo(1)
  }

  @Test
  fun `lookup accepts stored review and in flight property including a different expected destination`() {
    val stored = incoming().apply {
      events.removeIf { it.eventType == PropertyEventType.PRISONER_RECEIVED }
      refreshDerivedState()
    }
    val id = repository.save(stored).id!!
    lookup().expectStatus().isOk
    stored.events.add(PropertyEvent(stored, PropertyEventType.PRISONER_ADMITTED, CREATED.plusDays(1), "PRISONER_PROPERTY_API", toPrisonId = "MDI"))
    stored.refreshDerivedState()
    repository.save(stored)
    lookup().expectStatus().isOk
    repository.deleteById(id)
    repository.save(incoming(transferred = true))
    lookup(prison = "IWI").expectStatus().isOk
  }

  @Test
  fun `lookup returns not found for unmatched seals other prisoners and property already held here`() {
    repository.save(incoming())
    lookup(seal = "NOT_FOUND").expectStatus().isNotFound
      .expectBody().jsonPath("$.errorCode").isEqualTo("INCOMING_CONTAINER_NOT_FOUND")
    lookup(prisoner = "B1234CD").expectStatus().isNotFound
    lookup(prison = " lei ").expectStatus().isNotFound
    assertNoEventsPublished()
  }

  @Test
  fun `lookup reports ambiguity instead of choosing one container`() {
    repository.save(incoming())
    repository.save(incoming(transferred = true))
    lookup().expectStatus().isEqualTo(409)
      .expectBody().jsonPath("$.errorCode").isEqualTo("AMBIGUOUS_INCOMING_CONTAINER")
    assertNoEventsPublished()
  }

  @ParameterizedTest
  @EnumSource(value = RemovalOutcome::class, names = ["TRANSFERRED"], mode = EnumSource.Mode.EXCLUDE)
  fun `lookup excludes removed property`(outcome: RemovalOutcome) {
    repository.save(
      incoming().apply {
        removalOutcome = outcome
        refreshDerivedState()
      },
    )
    lookup().expectStatus().isNotFound
  }

  @Test
  fun `lookup excludes reconciled transfers and receipt revalidates a stale lookup`() {
    val source = incoming(transferred = true)
    val id = repository.save(source).id!!
    lookup().expectStatus().isOk
    source.latestTransferEvent()!!.relatedContainerId = UUID.randomUUID()
    source.refreshDerivedState()
    repository.save(source)
    lookup().expectStatus().isNotFound
    receive(id).expectStatus().isEqualTo(409)
    assertNoEventsPublished()
  }

  @Test
  fun `lookup requires valid parameters and the read role`() {
    lookup(prisoner = "BAD").expectStatus().isBadRequest
    lookup(prison = " ").expectStatus().isBadRequest
    lookup(seal = " ").expectStatus().isBadRequest
    webTestClient.get().uri("/property-containers/incoming")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange().expectStatus().isBadRequest
    webTestClient.get().uri("/property-containers/incoming?prisonerNumber=A1234BC&prisonId=MDI&sealNumber=5678")
      .exchange().expectStatus().isUnauthorized
    webTestClient.get().uri("/property-containers/incoming?prisonerNumber=A1234BC&prisonId=MDI&sealNumber=5678")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .exchange().expectStatus().isForbidden
  }

  private fun lookup(seal: String = "5678", prison: String = "MDI", prisoner: String = "A1234BC") = webTestClient.get()
    .uri { it.path("/property-containers/incoming").queryParam("prisonerNumber", prisoner).queryParam("prisonId", prison).queryParam("sealNumber", seal).build() }
    .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
    .exchange()

  @Test
  fun `receives a due container in place with a durable receipt timeline event`() {
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
    assertThat(persisted.lastUpdatedDateTime).isEqualTo(receipt.eventDateTime)
    assertThat(received.lastUpdatedDateTime).isEqualTo(receipt.eventDateTime)
    assertThat(receipt.fromPrisonId).isEqualTo("LEI")
    assertThat(receipt.toPrisonId).isEqualTo("MDI")
    assertThat(receipt.sealNumber).isEqualTo("5678")
    assertThat(receipt.toInternalLocationId).isEqualTo(LOCATION)
    assertThat(receipt.toStorageLocationType).isEqualTo(StorageLocationType.INTERNAL)
    assertThat(publishedEventsFor(source.id!!)).singleElement().satisfies({
      assertThat(it.eventType).isEqualTo("prison-property.container.updated")
      assertThat(it.changedFields).containsExactly("prisonId", "location", "currentStatus", "receivingPrisonId")
    })

    val timeline = timeline()
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
  fun `legacy create still reconciles against a separate receiving record during staged rollout`() {
    val sourceId = repository.save(incoming(transferred = true)).id!!
    val created = webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "RECEPTION", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(
        CreatePropertyContainerRequest(
          prisonerNumber = "A1234BC",
          prisonId = "MDI",
          containerType = ContainerType.STANDARD,
          sealNumber = "NEWSEAL",
          previousSealNumber = "5678",
        ),
      )
      .exchange().expectStatus().isCreated
      .expectBody(PropertyContainerDto::class.java).returnResult().responseBody!!

    assertThat(repository.count()).isEqualTo(2)
    assertThat(created.id).isNotEqualTo(sourceId)
    val source = repository.findById(sourceId).orElseThrow()
    assertThat(source.prisonId).isEqualTo("LEI")
    assertThat(source.removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    assertThat(source.latestTransferEvent()!!.relatedContainerId).isEqualTo(created.id)
    assertThat(repository.findById(created.id).orElseThrow().events.single().eventType).isEqualTo(PropertyEventType.CREATED_SEALED)
    receive(sourceId).expectStatus().isEqualTo(409)
    assertThat(repository.findById(sourceId).orElseThrow().events).noneMatch { it.eventType == PropertyEventType.RECEIVED }
  }

  @Test
  fun `receipt accepts a transfer sent to a different destination`() {
    val id = repository.save(incoming(transferred = true)).id!!
    receive(id, request().copy(receivingPrisonId = "IWI", internalLocationId = null))
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.prisonId").isEqualTo("IWI")
      .jsonPath("$.currentStatus").isEqualTo("STORED")
    assertThat(repository.findById(id).orElseThrow().receivingPrisonId).isNull()
  }

  @Test
  fun `receipt leaves an existing disposal date effective`() {
    val id = repository.save(incoming().apply { proposedDisposalDate = LocalDate.now().minusDays(1) }).id!!
    receive(id)
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.currentStatus").isEqualTo("DISPOSAL_REQUIRED")
    assertThat(repository.findById(id).orElseThrow().events.last().eventType).isEqualTo(PropertyEventType.RECEIVED)
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
      .jsonPath("$.locationDescription").doesNotExist()

    assertThat(prisonerSearch.allServeEvents).isEmpty()
    assertThat(prisonRegister.allServeEvents).isEmpty()
    assertThat(locations.allServeEvents).isEmpty()
  }

  @Test
  fun `receives an unreconciled transferred container and clears its removal state`() {
    val id = repository.save(incoming(transferred = true)).id!!
    receive(id).expectStatus().isOk

    val persisted = repository.findById(id).orElseThrow()
    assertThat(repository.count()).isEqualTo(1)
    assertThat(persisted.events.single { it.eventType == PropertyEventType.TRANSFERRED }.eventDateTime).isEqualTo(TRANSFERRED)
    assertThat(persisted.currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(persisted.removalOutcome).isNull()
    assertThat(persisted.removalDate).isNull()
    assertThat(persisted.receivingPrison()).isNull()
    assertThat(publishedEventsFor(id).single().changedFields)
      .containsExactly("prisonId", "location", "removalOutcome", "currentStatus", "receivingPrisonId")
  }

  @Test
  fun `receives stored property when the sender never flagged a transfer`() {
    val source = incoming().apply {
      events.removeIf { it.eventType == PropertyEventType.PRISONER_RECEIVED }
      refreshDerivedState()
    }
    receive(repository.save(source).id!!).expectStatus().isOk
    assertThat(repository.count()).isEqualTo(1)
  }

  @Test
  fun `receipt clears review required after admission while retaining the admission history`() {
    val source = incoming().apply {
      events.add(PropertyEvent(this, PropertyEventType.PRISONER_ADMITTED, CREATED.plusDays(2), "PRISONER_PROPERTY_API", fromPrisonId = "LEI", toPrisonId = "MDI"))
      refreshDerivedState()
    }
    assertThat(source.requiresReview()).isTrue()
    val id = repository.save(source).id!!
    receive(id).expectStatus().isOk
    val persisted = repository.findById(id).orElseThrow()
    assertThat(persisted.requiresReview()).isFalse()
    assertThat(persisted.currentStatusValue).isEqualTo(ContainerStatus.STORED)
    assertThat(persisted.receivingPrisonId).isNull()
    assertThat(persisted.events.count { it.eventType == PropertyEventType.PRISONER_ADMITTED }).isEqualTo(1)
  }

  @Test
  fun `receipt without a location clears the sending location and retains the legacy origin`() {
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
    val timeline = timeline()
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
  fun `receipt records a changed seal without creating another container`() {
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
  fun `rejects a repeated receipt`() {
    val id = repository.save(incoming()).id!!
    receive(id).expectStatus().isOk
    receive(id).expectStatus().isBadRequest
    assertThat(repository.findById(id).orElseThrow().events.count { it.eventType == PropertyEventType.RECEIVED }).isEqualTo(1)
    assertThat(publishedEventsFor(id)).hasSize(1)
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
  fun `rejects a transfer already reconciled by the legacy create flow`() {
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
  fun `rejects invalid full or wrong establishment locations without changing the container`() {
    val id = repository.save(incoming()).id!!
    locations.stubGetPropertyLocationNotFound(LOCATION.toString())
    receive(id).expectStatus().isBadRequest
    locations.stubGetPropertyLocation(LOCATION.toString(), propertyCapacity = 0)
    receive(id).expectStatus().isBadRequest
    locations.stubGetPropertyLocation(LOCATION.toString())
    receive(id, request().copy(receivingPrisonId = "IWI")).expectStatus().isBadRequest
    assertThat(repository.findById(id).orElseThrow().prisonId).isEqualTo("LEI")
    assertNoEventsPublished()
  }

  @Test
  fun `rejects contradictory or blank details`() {
    val id = repository.save(incoming()).id!!
    receive(id, request().copy(locationType = StorageLocationType.BRANSTON)).expectStatus().isBadRequest
    receive(id, request().copy(receivingPrisonId = " ")).expectStatus().isBadRequest
    receive(id, request().copy(sealNumber = " ")).expectStatus().isBadRequest
    receive(id, request().copy(prisonerNumber = " ")).expectStatus().isBadRequest
    receive(id, request().copy(prisonerNumber = "INVALID")).expectStatus().isBadRequest
    assertNoEventsPublished()
  }

  @Test
  fun `rejects receipt for another prisoner without changing the container`() {
    val id = repository.save(incoming(transferred = true)).id!!
    val before = repository.findById(id).orElseThrow()
    val eventIds = before.events.map { it.id }

    receive(id, request().copy(prisonerNumber = "B1234CD"))
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.userMessage").value<String> { assertThat(it).contains("does not belong to prisoner B1234CD") }

    val after = repository.findById(id).orElseThrow()
    assertThat(after.prisonerNumber).isEqualTo("A1234BC")
    assertThat(after.prisonId).isEqualTo(before.prisonId)
    assertThat(after.currentSealNumber).isEqualTo(before.currentSealNumber)
    assertThat(after.removalOutcome).isEqualTo(before.removalOutcome)
    assertThat(after.events.map { it.id }).containsExactlyInAnyOrderElementsOf(eventIds)
    assertNoEventsPublished()
  }

  @Test
  fun `requires the prisoner number in the receipt request`() {
    val id = repository.save(incoming()).id!!
    webTestClient.post().uri("/property-containers/{id}/receive", id)
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(mapOf("receivingPrisonId" to "MDI", "sealNumber" to "5678"))
      .exchange().expectStatus().isBadRequest
    assertThat(repository.findById(id).orElseThrow().prisonId).isEqualTo("LEI")
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

  private fun timeline() = webTestClient.get().uri("/property-containers/prisoner/A1234BC/events")
    .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
    .exchange()
    .expectStatus().isOk
    .expectBodyList(PrisonerTimelineItemDto::class.java)
    .returnResult().responseBody!!

  private fun request() = ReceiveContainerRequest(prisonerNumber = "A1234BC", receivingPrisonId = "MDI", sealNumber = "5678", internalLocationId = LOCATION)

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
