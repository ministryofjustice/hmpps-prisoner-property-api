package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import jakarta.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.DisposeContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.RemoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyContainerRequest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class PropertyContainerWriteServiceTest {

  private val repository = mock<PropertyContainerRepository>()
  private val service = PropertyContainerWriteService(repository)

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
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.created")
    assertThat(result.event?.prisonerNumber).isEqualTo("A1234BC")
  }

  @Test
  fun `create with a proposed disposal date records disposal required`() {
    stubSaveAssigningId()

    service.create(createRequest(proposedDisposalDate = LocalDate.parse("2026-09-01")), "A_USER")

    val saved = captureSaved()
    assertThat(saved.proposedDisposalDate).isEqualTo(LocalDate.parse("2026-09-01"))
    assertThat(saved.currentStatus()).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
    assertThat(saved.events.map { it.eventType }).contains(PropertyEventType.DISPOSAL_REQUIRED)
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
  fun `remove transferring records a TRANSFERRED outcome with the from and to prisons`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    service.remove(existing.id!!, RemoveContainerRequest(outcome = RemovalOutcome.TRANSFERRED, toPrisonId = "MDI"), "A_USER")

    assertThat(existing.removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    val event = existing.events.last()
    assertThat(event.eventType).isEqualTo(PropertyEventType.TRANSFERRED)
    assertThat(event.fromPrisonId).isEqualTo("LEI")
    assertThat(event.toPrisonId).isEqualTo("MDI")
  }

  @Test
  fun `remove transferring without a destination prison throws`() {
    assertThatThrownBy { service.remove(UUID.randomUUID(), RemoveContainerRequest(outcome = RemovalOutcome.TRANSFERRED), "A_USER") }
      .isInstanceOf(ValidationException::class.java)
    verify(repository, never()).save(any())
  }

  @Test
  fun `remove with a DISPOSED outcome is rejected`() {
    assertThatThrownBy { service.remove(UUID.randomUUID(), RemoveContainerRequest(outcome = RemovalOutcome.DISPOSED), "A_USER") }
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

  private fun createRequest(proposedDisposalDate: LocalDate? = null) = CreatePropertyContainerRequest(
    prisonerNumber = "A1234BC",
    prisonId = "LEI",
    containerType = ContainerType.STANDARD,
    sealNumber = "SEAL1",
    internalLocationId = LOCATION,
    proposedDisposalDate = proposedDisposalDate,
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
