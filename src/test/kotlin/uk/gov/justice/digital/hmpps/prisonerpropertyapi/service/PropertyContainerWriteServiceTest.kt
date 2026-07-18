package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import jakarta.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PropertyLocation
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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.MoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.RemoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyContainerRequest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class PropertyContainerWriteServiceTest {

  private val repository = mock<PropertyContainerRepository>()
  private val locationsClient = mock<LocationsClient>()
  private val service = PropertyContainerWriteService(repository, locationsClient)

  @BeforeEach
  fun stubLocationsResolveByDefault() {
    whenever(locationsClient.getPropertyLocation(any())).thenAnswer { invocation ->
      val id = invocation.arguments[0] as UUID
      PropertyLocation(
        id = id,
        prisonId = "LEI",
        code = "PROP",
        pathHierarchy = "RECP-PROP",
        localName = "Reception Property Store",
        locationType = "BOX",
        capacity = 100,
      )
    }
  }

  @Test
  fun `create persists a sealed container with the authenticated user and returns a created event`() {
    stubSaveAssigningId()

    val result = service.create(createRequest(), "A_USER")

    val saved = captureSaved()
    assertThat(saved.prisonerNumber).isEqualTo("A1234BC")
    assertThat(saved.containerType).isEqualTo(ContainerType.STANDARD)
    assertThat(saved.createdByUserId).isEqualTo("A_USER")
    assertThat(saved.currentSealNumber).isEqualTo("SEAL1")
    assertThat(saved.currentLocation()).isEqualTo(LOCATION)
    assertThat(saved.currentLocationType()).isEqualTo(StorageLocationType.INTERNAL)
    assertThat(saved.events).singleElement().extracting { it.eventType }.isEqualTo(PropertyEventType.CREATED_SEALED)

    assertThat(result.container.createdByUserId).isEqualTo("A_USER")
    assertThat(result.events).singleElement().satisfies({
      assertThat(it.eventType).isEqualTo("prison-property.container.created")
      assertThat(it.prisonerNumber).isEqualTo("A1234BC")
    })
  }

  @Test
  fun `create with a future proposed disposal date stays stored and records the disposal event`() {
    stubSaveAssigningId()
    val future = LocalDate.now().plusMonths(1)

    service.create(createRequest(proposedDisposalDate = future), "A_USER")

    val saved = captureSaved()
    assertThat(saved.proposedDisposalDate).isEqualTo(future)
    // Disposal is time-based: the date has not yet arisen, so the container is still STORED, though the
    // disposal event is recorded for history.
    assertThat(saved.currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(saved.events.map { it.eventType }).contains(PropertyEventType.DISPOSAL_REQUIRED)
  }

  @Test
  fun `create with a proposed disposal date that has already arisen is due for disposal`() {
    stubSaveAssigningId()

    service.create(createRequest(proposedDisposalDate = LocalDate.now().minusDays(1)), "A_USER")

    assertThat(captureSaved().currentStatus()).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
  }

  @Test
  fun `update changes the seal and returns an update event listing the change`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.update(existing.id!!, updateRequest(sealNumber = "SEAL2"), "A_USER")

    assertThat(existing.currentSealNumber).isEqualTo("SEAL2")
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.SEAL_CHANGED)
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.updated")
    assertThat(result.event?.additionalInformation?.get("changedFields")).isEqualTo(listOf("sealNumber"))
  }

  @Test
  fun `update changing the type and location appends the matching events`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))
    val newLocation = UUID.fromString("33333333-3333-3333-3333-333333333333")

    service.update(existing.id!!, updateRequest(containerType = ContainerType.VALUABLES, internalLocationId = newLocation), "A_USER")

    assertThat(existing.containerType).isEqualTo(ContainerType.VALUABLES)
    assertThat(existing.currentLocation()).isEqualTo(newLocation)
    assertThat(existing.events.map { it.eventType }).contains(PropertyEventType.CONTAINER_TYPE_CHANGE, PropertyEventType.MOVED)
  }

  @Test
  fun `update to a field other than location does not re-validate the container's current location`() {
    // The container's current location no longer resolves as a property store (e.g. migrated data or a
    // designation removed after placement). Editing its seal must still work - the location is unchanged,
    // so it is never looked up (the default stub would resolve it, but it should not be called at all).
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.update(existing.id!!, updateRequest(sealNumber = "SEAL2", internalLocationId = LOCATION), "A_USER")

    assertThat(existing.currentSealNumber).isEqualTo("SEAL2")
    assertThat(result.event?.additionalInformation?.get("changedFields")).isEqualTo(listOf("sealNumber"))
    // the unchanged location must not be validated on this edit
    verify(locationsClient, never()).getPropertyLocation(any())
  }

  @Test
  fun `update with no changes does not save and returns no event`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.update(existing.id!!, updateRequest(), "A_USER")

    assertThat(result.event).isNull()
    assertThat(existing.events).hasSize(1)
    verify(repository, never()).save(any())
  }

  @Test
  fun `update of an unknown container throws not found`() {
    val id = UUID.randomUUID()
    whenever(repository.findById(id)).thenReturn(Optional.empty())

    assertThatThrownBy { service.update(id, updateRequest(), "A_USER") }
      .isInstanceOf(PropertyContainerNotFoundException::class.java)
  }

  @Test
  fun `create rejects a seal already held by an active container`() {
    whenever(repository.existsByCurrentSealNumberAndRemovalOutcomeIsNull("SEAL1")).thenReturn(true)

    assertThatThrownBy { service.create(createRequest(), "A_USER") }
      .isInstanceOf(DuplicateSealNumberException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `create rejects an internal location that is not a property store`() {
    // locations-inside-prison returns null for both an unknown id and a location with no PROPERTY usage.
    whenever(locationsClient.getPropertyLocation(LOCATION)).thenReturn(null)

    assertThatThrownBy { service.create(createRequest(), "A_USER") }
      .isInstanceOf(InvalidLocationException::class.java)
      .hasMessageContaining("is not a property storage location")
    verify(repository, never()).save(any())
  }

  @Test
  fun `create rejects an internal location that is full`() {
    // The location has capacity 2 and already holds 2 containers, so there is no room for another.
    whenever(locationsClient.getPropertyLocation(LOCATION)).thenReturn(
      PropertyLocation(id = LOCATION, prisonId = "LEI", code = "PROP", pathHierarchy = "RECP-PROP", localName = "Reception Store", locationType = "BOX", capacity = 2),
    )
    whenever(repository.countContainersInLocation(LOCATION, null)).thenReturn(2)

    assertThatThrownBy { service.create(createRequest(), "A_USER") }
      .isInstanceOf(InvalidLocationException::class.java)
      .hasMessageContaining("is full")
    verify(repository, never()).save(any())
  }

  @Test
  fun `update rejects an internal location that does not exist`() {
    val existing = existingContainer()
    val newLocation = UUID.fromString("33333333-3333-3333-3333-333333333333")
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))
    whenever(locationsClient.getPropertyLocation(newLocation)).thenReturn(null)

    assertThatThrownBy { service.update(existing.id!!, updateRequest(internalLocationId = newLocation), "A_USER") }
      .isInstanceOf(InvalidLocationException::class.java)
  }

  @Test
  fun `move rejects an internal location that does not exist`() {
    val newLocation = UUID.fromString("33333333-3333-3333-3333-333333333333")
    whenever(locationsClient.getPropertyLocation(newLocation)).thenReturn(null)

    assertThatThrownBy { service.move(UUID.randomUUID(), MoveContainerRequest(StorageLocationType.INTERNAL, internalLocationId = newLocation), "A_USER") }
      .isInstanceOf(InvalidLocationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `combine rejects an internal location that does not exist`() {
    val a = sourceContainer("A1234BC", "SEALA")
    val b = sourceContainer("A1234BC", "SEALB")
    whenever(repository.findById(a.id!!)).thenReturn(Optional.of(a))
    whenever(repository.findById(b.id!!)).thenReturn(Optional.of(b))
    whenever(locationsClient.getPropertyLocation(LOCATION)).thenReturn(null)

    assertThatThrownBy {
      service.combine(CombineContainersRequest(listOf(a.id!!, b.id!!), ContainerType.STANDARD, "NEWSEAL", internalLocationId = LOCATION), "A_USER")
    }.isInstanceOf(InvalidLocationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `create reconciles a matching due-for-transfer-out container arriving from another prison`() {
    stubSaveAssigningId()
    val source = dueForTransferOut("LEI", "OLDSEAL", toPrisonId = "MDI")
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(source))

    val result = service.create(createRequest(prisonId = "MDI", sealNumber = "NEWSEAL", previousSealNumber = "OLDSEAL"), "A_USER")

    // the new container is created at the receiving prison and linked back to the source
    assertThat(result.container.prisonId).isEqualTo("MDI")
    assertThat(result.container.currentSealNumber).isEqualTo("NEWSEAL")
    // only the new container is persisted explicitly; the source's changes flush via dirty checking
    val captor = argumentCaptor<PropertyContainer>()
    verify(repository).save(captor.capture())
    val newContainer = captor.allValues.first { it.prisonId == "MDI" }
    assertThat(newContainer.events.single { it.eventType == PropertyEventType.CREATED_SEALED }.relatedContainerId).isEqualTo(source.id)

    // the source container is deactivated as transferred to the receiving prison, linked to the new record
    assertThat(source.removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    assertThat(source.removalDate).isEqualTo(LocalDate.now())
    val transferred = source.events.last()
    assertThat(transferred.eventType).isEqualTo(PropertyEventType.TRANSFERRED)
    assertThat(transferred.fromPrisonId).isEqualTo("LEI")
    assertThat(transferred.toPrisonId).isEqualTo("MDI")
    assertThat(transferred.relatedContainerId).isEqualTo(newContainer.id)

    // both the created (new) and updated (source) events are returned to publish
    assertThat(result.events.map { it.eventType })
      .containsExactly("prison-property.container.created", "prison-property.container.updated")
  }

  @Test
  fun `create with the same arriving seal succeeds because the reconciled source frees its seal`() {
    stubSaveAssigningId()
    val source = dueForTransferOut("LEI", "SAMESEAL", toPrisonId = "MDI")
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(source))
    // the seal is still held by the source at this point; the create must exclude the source it is reconciling
    whenever(repository.existsByCurrentSealNumberAndRemovalOutcomeIsNull("SAMESEAL")).thenReturn(true)

    val result = service.create(createRequest(prisonId = "MDI", sealNumber = "SAMESEAL", previousSealNumber = "SAMESEAL"), "A_USER")

    assertThat(result.container.currentSealNumber).isEqualTo("SAMESEAL")
    assertThat(source.isRemoved()).isTrue()
  }

  @Test
  fun `create ignores a previous seal that matches no due-for-transfer-out container`() {
    stubSaveAssigningId()
    val storedElsewhere = containerAt("MDI", "OLDSEAL") // active elsewhere but not due for transfer out
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(storedElsewhere))

    val result = service.create(createRequest(prisonId = "LEI", sealNumber = "NEWSEAL", previousSealNumber = "OLDSEAL"), "A_USER")

    assertThat(result.events).singleElement().extracting { it.eventType }.isEqualTo("prison-property.container.created")
    assertThat(storedElsewhere.isRemoved()).isFalse()
  }

  @Test
  fun `update rejects amending the seal to one held by another active container`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))
    whenever(repository.existsByCurrentSealNumberAndRemovalOutcomeIsNullAndIdNot("SEAL2", existing.id!!)).thenReturn(true)

    assertThatThrownBy { service.update(existing.id!!, updateRequest(sealNumber = "SEAL2"), "A_USER") }
      .isInstanceOf(DuplicateSealNumberException::class.java)
  }

  @Test
  fun `update keeping the same seal does not check uniqueness`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    service.update(existing.id!!, updateRequest(), "A_USER")

    verify(repository, never()).existsByCurrentSealNumberAndRemovalOutcomeIsNullAndIdNot(any(), any())
  }

  @Test
  fun `dispose records a disposed outcome, clears the location and returns an updated event`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.dispose(existing.id!!, DisposeContainerRequest(disposalDate = LocalDate.parse("2026-09-15")), "A_USER")

    assertThat(existing.removalOutcome).isEqualTo(RemovalOutcome.DISPOSED)
    assertThat(existing.removalDate).isEqualTo(LocalDate.parse("2026-09-15"))
    assertThat(existing.currentStatus()).isEqualTo(ContainerStatus.DISPOSED)
    assertThat(existing.currentLocation()).isNull()
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.DISPOSED)
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.updated")
    assertThat(result.event?.additionalInformation?.get("changedFields")).isEqualTo(listOf("removalOutcome"))
  }

  @Test
  fun `dispose defaults the disposal date to today when omitted`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    service.dispose(existing.id!!, DisposeContainerRequest(), "A_USER")

    assertThat(existing.removalDate).isEqualTo(LocalDate.now())
  }

  @Test
  fun `dispose of an already-removed container throws`() {
    val existing = existingContainer().apply { removalOutcome = RemovalOutcome.RETURNED }
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    assertThatThrownBy { service.dispose(existing.id!!, DisposeContainerRequest(), "A_USER") }
      .isInstanceOf(ContainerAlreadyRemovedException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `dispose of an unknown container throws not found`() {
    val id = UUID.randomUUID()
    whenever(repository.findById(id)).thenReturn(Optional.empty())

    assertThatThrownBy { service.dispose(id, DisposeContainerRequest(), "A_USER") }
      .isInstanceOf(PropertyContainerNotFoundException::class.java)
  }

  @Test
  fun `remove returning to the prisoner records a RETURNED outcome and event`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.remove(existing.id!!, RemoveContainerRequest(outcome = RemovalOutcome.RETURNED), "A_USER")

    assertThat(existing.removalOutcome).isEqualTo(RemovalOutcome.RETURNED)
    assertThat(existing.currentStatus()).isEqualTo(ContainerStatus.RETURNED)
    assertThat(existing.currentLocation()).isNull()
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.RETURNED)
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.updated")
  }

  @Test
  fun `remove transferring reassigns the container to the receiving prison and keeps it active`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.remove(existing.id!!, RemoveContainerRequest(outcome = RemovalOutcome.TRANSFERRED, toPrisonId = "MDI"), "A_USER")

    // reassigned to the receiving prison, still active, with its location cleared
    assertThat(existing.prisonId).isEqualTo("MDI")
    assertThat(existing.removalOutcome).isNull()
    assertThat(existing.currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(existing.currentLocation()).isNull()
    val event = existing.events.last()
    assertThat(event.eventType).isEqualTo(PropertyEventType.TRANSFERRED)
    assertThat(event.fromPrisonId).isEqualTo("LEI")
    assertThat(event.toPrisonId).isEqualTo("MDI")
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.updated")
    assertThat(result.event?.additionalInformation?.get("changedFields")).isEqualTo(listOf("prisonId", "location"))
  }

  @Test
  fun `remove transferring without a destination prison throws`() {
    assertThatThrownBy { service.remove(UUID.randomUUID(), RemoveContainerRequest(outcome = RemovalOutcome.TRANSFERRED), "A_USER") }
      .isInstanceOf(ValidationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `remove with a DISPOSED outcome records a disposed removal and clears the location`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    service.remove(existing.id!!, RemoveContainerRequest(outcome = RemovalOutcome.DISPOSED), "A_USER")

    assertThat(existing.removalOutcome).isEqualTo(RemovalOutcome.DISPOSED)
    assertThat(existing.currentStatus()).isEqualTo(ContainerStatus.DISPOSED)
    assertThat(existing.currentLocation()).isNull()
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.DISPOSED)
  }

  @Test
  fun `remove recording the record was created in error takes the container out of active storage`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    service.remove(existing.id!!, RemoveContainerRequest(outcome = RemovalOutcome.CREATED_IN_ERROR), "A_USER")

    assertThat(existing.removalOutcome).isEqualTo(RemovalOutcome.CREATED_IN_ERROR)
    assertThat(existing.currentStatus()).isEqualTo(ContainerStatus.CREATED_IN_ERROR)
    assertThat(existing.currentLocation()).isNull()
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.CREATED_IN_ERROR)
  }

  @Test
  fun `remove with a COMBINED outcome is rejected`() {
    assertThatThrownBy { service.remove(UUID.randomUUID(), RemoveContainerRequest(outcome = RemovalOutcome.COMBINED), "A_USER") }
      .isInstanceOf(ValidationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `remove of an already-removed container throws`() {
    val existing = existingContainer().apply { removalOutcome = RemovalOutcome.DISPOSED }
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    assertThatThrownBy { service.remove(existing.id!!, RemoveContainerRequest(outcome = RemovalOutcome.RETURNED), "A_USER") }
      .isInstanceOf(ContainerAlreadyRemovedException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `move to Branston records a Branston move with no internal id`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.move(existing.id!!, MoveContainerRequest(locationType = StorageLocationType.BRANSTON), "A_USER")

    assertThat(existing.currentLocationType()).isEqualTo(StorageLocationType.BRANSTON)
    assertThat(existing.currentLocation()).isNull()
    val moved = existing.events.last()
    assertThat(moved.eventType).isEqualTo(PropertyEventType.MOVED)
    assertThat(moved.fromInternalLocationId).isEqualTo(LOCATION)
    assertThat(moved.toInternalLocationId).isNull()
    assertThat(moved.toStorageLocationType).isEqualTo(StorageLocationType.BRANSTON)
    assertThat(result.event?.additionalInformation?.get("changedFields")).isEqualTo(listOf("location"))
  }

  @Test
  fun `move to an internal location records an internal move`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))
    val newLocation = UUID.fromString("33333333-3333-3333-3333-333333333333")

    service.move(existing.id!!, MoveContainerRequest(locationType = StorageLocationType.INTERNAL, internalLocationId = newLocation), "A_USER")

    assertThat(existing.currentLocation()).isEqualTo(newLocation)
    assertThat(existing.currentLocationType()).isEqualTo(StorageLocationType.INTERNAL)
  }

  @Test
  fun `move to Branston with an internal id is rejected`() {
    assertThatThrownBy { service.move(UUID.randomUUID(), MoveContainerRequest(StorageLocationType.BRANSTON, internalLocationId = LOCATION), "A_USER") }
      .isInstanceOf(ValidationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `move to an internal location without an id is rejected`() {
    assertThatThrownBy { service.move(UUID.randomUUID(), MoveContainerRequest(StorageLocationType.INTERNAL), "A_USER") }
      .isInstanceOf(ValidationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `move to the current location is a no-op`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.move(existing.id!!, MoveContainerRequest(StorageLocationType.INTERNAL, internalLocationId = LOCATION), "A_USER")

    assertThat(result.event).isNull()
    assertThat(existing.events).hasSize(1)
    verify(repository, never()).save(any())
  }

  @Test
  fun `move of an already-removed container throws`() {
    val existing = existingContainer().apply { removalOutcome = RemovalOutcome.DISPOSED }
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    assertThatThrownBy { service.move(existing.id!!, MoveContainerRequest(StorageLocationType.BRANSTON), "A_USER") }
      .isInstanceOf(ContainerAlreadyRemovedException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `combine creates a new sealed container and marks the sources as combined`() {
    stubSaveAssigningId()
    val a = sourceContainer("A1234BC", "SEALA")
    val b = sourceContainer("A1234BC", "SEALB")
    whenever(repository.findById(a.id!!)).thenReturn(Optional.of(a))
    whenever(repository.findById(b.id!!)).thenReturn(Optional.of(b))

    val result = service.combine(
      CombineContainersRequest(sourceContainerIds = listOf(a.id!!, b.id!!), containerType = ContainerType.VALUABLES, sealNumber = "NEWSEAL", internalLocationId = LOCATION),
      "A_USER",
    )

    assertThat(result.container.currentSealNumber).isEqualTo("NEWSEAL")
    assertThat(result.container.containerType).isEqualTo(ContainerType.VALUABLES)
    assertThat(result.container.currentStatus).isEqualTo(ContainerStatus.STORED)
    assertThat(a.removalOutcome).isEqualTo(RemovalOutcome.COMBINED)
    assertThat(b.removalOutcome).isEqualTo(RemovalOutcome.COMBINED)
    assertThat(a.currentStatus()).isEqualTo(ContainerStatus.COMBINED)
    assertThat(a.events.last().eventType).isEqualTo(PropertyEventType.COMBINED)
    assertThat(a.events.last().relatedContainerId).isEqualTo(result.container.id)
    assertThat(result.events.map { it.eventType })
      .containsExactly("prison-property.container.created", "prison-property.container.updated", "prison-property.container.updated")
  }

  @Test
  fun `combine rejects sources belonging to different prisoners`() {
    val a = sourceContainer("A1234BC", "SEALA")
    val b = sourceContainer("B2345CD", "SEALB")
    whenever(repository.findById(a.id!!)).thenReturn(Optional.of(a))
    whenever(repository.findById(b.id!!)).thenReturn(Optional.of(b))

    assertThatThrownBy { service.combine(CombineContainersRequest(listOf(a.id!!, b.id!!), ContainerType.STANDARD, "NEWSEAL"), "A_USER") }
      .isInstanceOf(ValidationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `combine rejects a seal already held by an active container`() {
    val a = sourceContainer("A1234BC", "SEALA")
    val b = sourceContainer("A1234BC", "SEALB")
    whenever(repository.findById(a.id!!)).thenReturn(Optional.of(a))
    whenever(repository.findById(b.id!!)).thenReturn(Optional.of(b))
    whenever(repository.existsByCurrentSealNumberAndRemovalOutcomeIsNull("NEWSEAL")).thenReturn(true)

    assertThatThrownBy { service.combine(CombineContainersRequest(listOf(a.id!!, b.id!!), ContainerType.STANDARD, "NEWSEAL"), "A_USER") }
      .isInstanceOf(DuplicateSealNumberException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `combine rejects an already-removed source`() {
    val a = sourceContainer("A1234BC", "SEALA").apply { removalOutcome = RemovalOutcome.DISPOSED }
    val b = sourceContainer("A1234BC", "SEALB")
    whenever(repository.findById(a.id!!)).thenReturn(Optional.of(a))
    whenever(repository.findById(b.id!!)).thenReturn(Optional.of(b))

    assertThatThrownBy { service.combine(CombineContainersRequest(listOf(a.id!!, b.id!!), ContainerType.STANDARD, "NEWSEAL"), "A_USER") }
      .isInstanceOf(ValidationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `combine with an unknown source throws not found`() {
    val a = sourceContainer("A1234BC", "SEALA")
    val unknown = UUID.randomUUID()
    whenever(repository.findById(a.id!!)).thenReturn(Optional.of(a))
    whenever(repository.findById(unknown)).thenReturn(Optional.empty())

    assertThatThrownBy { service.combine(CombineContainersRequest(listOf(a.id!!, unknown), ContainerType.STANDARD, "NEWSEAL"), "A_USER") }
      .isInstanceOf(PropertyContainerNotFoundException::class.java)
  }

  @Test
  fun `prisonerReceived flags active containers at a different prison as due for transfer out`() {
    val atSendingPrison = containerAt("LEI", "SEAL1")
    val atNewPrison = containerAt("MDI", "SEAL2")
    val removedAtSendingPrison = containerAt("LEI", "SEAL3").apply { removalOutcome = RemovalOutcome.RETURNED }
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(atSendingPrison, atNewPrison, removedAtSendingPrison))

    val events = service.prisonerReceived("A1234BC", "MDI")

    assertThat(atSendingPrison.currentStatus()).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
    val received = atSendingPrison.events.last()
    assertThat(received.eventType).isEqualTo(PropertyEventType.PRISONER_RECEIVED)
    assertThat(received.fromPrisonId).isEqualTo("LEI")
    assertThat(received.toPrisonId).isEqualTo("MDI")

    // the container already at the new prison and the removed container are untouched
    assertThat(atNewPrison.events.map { it.eventType }).doesNotContain(PropertyEventType.PRISONER_RECEIVED)
    assertThat(removedAtSendingPrison.events.map { it.eventType }).doesNotContain(PropertyEventType.PRISONER_RECEIVED)

    assertThat(events).singleElement().extracting { it.eventType }.isEqualTo("prison-property.container.updated")
    assertThat(events.single().prisonerNumber).isEqualTo("A1234BC")
  }

  @Test
  fun `prisonerReceived is idempotent - a repeated receive to the same prison does nothing`() {
    val atSendingPrison = containerAt("LEI", "SEAL1")
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(atSendingPrison))

    service.prisonerReceived("A1234BC", "MDI")
    val secondCallEvents = service.prisonerReceived("A1234BC", "MDI")

    assertThat(secondCallEvents).isEmpty()
    assertThat(atSendingPrison.events.count { it.eventType == PropertyEventType.PRISONER_RECEIVED }).isEqualTo(1)
  }

  @Test
  fun `prisonerReceived with no property at another prison returns no events`() {
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(containerAt("MDI", "SEAL1")))

    assertThat(service.prisonerReceived("A1234BC", "MDI")).isEmpty()
    verify(repository, never()).save(any())
  }

  @Test
  fun `prisonerReleased flags all active containers as due for return, wherever held`() {
    val here = containerAt("LEI", "SEAL1")
    // A container already due for transfer out flips to due for return on release.
    val elsewhere = dueForTransferOut("MDI", "SEAL2", "LEI")
    val removed = containerAt("LEI", "SEAL3").apply { removalOutcome = RemovalOutcome.RETURNED }
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(here, elsewhere, removed))

    val events = service.prisonerReleased("A1234BC")

    assertThat(here.currentStatus()).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    assertThat(elsewhere.currentStatus()).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    assertThat(here.events.last().eventType).isEqualTo(PropertyEventType.PRISONER_RELEASED)
    // the removed container is untouched
    assertThat(removed.events.map { it.eventType }).doesNotContain(PropertyEventType.PRISONER_RELEASED)
    assertThat(events).hasSize(2).allSatisfy { assertThat(it.eventType).isEqualTo("prison-property.container.updated") }
  }

  @Test
  fun `prisonerReleased is idempotent - a repeat does nothing`() {
    val container = containerAt("LEI", "SEAL1")
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(container))

    service.prisonerReleased("A1234BC")
    val secondCallEvents = service.prisonerReleased("A1234BC")

    assertThat(secondCallEvents).isEmpty()
    assertThat(container.events.count { it.eventType == PropertyEventType.PRISONER_RELEASED }).isEqualTo(1)
  }

  @Test
  fun `prisonerDied flags all active containers as due for return with a distinct DIED_IN_CUSTODY event`() {
    val here = containerAt("LEI", "SEAL1")
    val removed = containerAt("LEI", "SEAL2").apply { removalOutcome = RemovalOutcome.RETURNED }
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(here, removed))

    val events = service.prisonerDied("A1234BC")

    assertThat(here.currentStatus()).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    assertThat(here.events.last().eventType).isEqualTo(PropertyEventType.DIED_IN_CUSTODY)
    // the removed container is untouched
    assertThat(removed.events.map { it.eventType }).doesNotContain(PropertyEventType.DIED_IN_CUSTODY)
    assertThat(events).hasSize(1).allSatisfy { assertThat(it.eventType).isEqualTo("prison-property.container.updated") }
  }

  @Test
  fun `prisonerDied is idempotent - a repeat does nothing`() {
    val container = containerAt("LEI", "SEAL1")
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(container))

    service.prisonerDied("A1234BC")
    val secondCallEvents = service.prisonerDied("A1234BC")

    assertThat(secondCallEvents).isEmpty()
    assertThat(container.events.count { it.eventType == PropertyEventType.DIED_IN_CUSTODY }).isEqualTo(1)
  }

  private fun containerAt(prisonId: String, seal: String): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = prisonId,
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
      currentSealNumber = seal,
      id = UUID.randomUUID(),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2026-01-01T09:00:00"), "A_USER", sealNumber = seal, toPrisonId = prisonId),
    )
    return container
  }

  /** An active container at [prisonId] flagged due for transfer out to [toPrisonId] (a PRISONER_RECEIVED event). */
  private fun dueForTransferOut(prisonId: String, seal: String, toPrisonId: String): PropertyContainer {
    val container = containerAt(prisonId, seal)
    container.events.add(
      PropertyEvent(container, PropertyEventType.PRISONER_RECEIVED, LocalDateTime.parse("2026-02-01T09:00:00"), "PRISONER_PROPERTY_API", fromPrisonId = prisonId, toPrisonId = toPrisonId),
    )
    return container
  }

  private fun sourceContainer(prisonerNumber: String, seal: String): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
      currentSealNumber = seal,
      id = UUID.randomUUID(),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2026-01-01T09:00:00"), "A_USER", sealNumber = seal, toInternalLocationId = LOCATION, toStorageLocationType = StorageLocationType.INTERNAL),
    )
    return container
  }

  private fun stubSaveAssigningId() {
    whenever(repository.save(any())).thenAnswer { invocation ->
      (invocation.arguments[0] as PropertyContainer).apply { if (id == null) id = UUID.randomUUID() }
    }
  }

  private fun captureSaved(): PropertyContainer {
    val captor = argumentCaptor<PropertyContainer>()
    verify(repository).save(captor.capture())
    return captor.firstValue
  }

  private fun existingContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
      currentSealNumber = "SEAL1",
      id = UUID.randomUUID(),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2026-01-01T09:00:00"), "A_USER", sealNumber = "SEAL1", toInternalLocationId = LOCATION, toStorageLocationType = StorageLocationType.INTERNAL),
    )
    return container
  }

  private fun createRequest(
    proposedDisposalDate: LocalDate? = null,
    prisonId: String = "LEI",
    sealNumber: String = "SEAL1",
    previousSealNumber: String? = null,
  ) = CreatePropertyContainerRequest(
    prisonerNumber = "A1234BC",
    prisonId = prisonId,
    containerType = ContainerType.STANDARD,
    sealNumber = sealNumber,
    internalLocationId = LOCATION,
    proposedDisposalDate = proposedDisposalDate,
    previousSealNumber = previousSealNumber,
  )

  private fun updateRequest(
    containerType: ContainerType = ContainerType.STANDARD,
    sealNumber: String = "SEAL1",
    internalLocationId: UUID? = LOCATION,
    proposedDisposalDate: LocalDate? = null,
  ) = UpdatePropertyContainerRequest(
    containerType = containerType,
    sealNumber = sealNumber,
    internalLocationId = internalLocationId,
    proposedDisposalDate = proposedDisposalDate,
  )

  private companion object {
    private val LOCATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
  }
}
