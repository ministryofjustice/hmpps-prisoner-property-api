package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.NomisContainerCode
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncMappingType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.sync.NomisContainerTransformer
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class SyncPropertyContainerServiceTest {

  private val repository = mock<PropertyContainerRepository>()
  private val service = SyncPropertyContainerService(repository, NomisContainerTransformer())

  @Test
  fun `create builds a sealed container and returns a created event to publish`() {
    stubSaveAssigningId()

    val result = service.sync(request())

    assertThat(result.response.mappingType).isEqualTo(SyncMappingType.CREATED)
    assertThat(result.response.nomisPropertyContainerId).isEqualTo(123)
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.created")

    val saved = captureSaved()
    assertThat(saved.containerType).isEqualTo(ContainerType.STANDARD)
    assertThat(saved.events).singleElement().satisfies({
      assertThat(it.eventType).isEqualTo(PropertyEventType.CREATED_SEALED)
      assertThat(it.sealNumber).isEqualTo("SEAL1")
      assertThat(it.toInternalLocationId).isEqualTo(LOCATION)
    })
  }

  @Test
  fun `create uses a placeholder when the seal is missing`() {
    stubSaveAssigningId()

    service.sync(request(sealMark = null))

    assertThat(captureSaved().currentSealNumber).isEqualTo("MISSING-123")
  }

  @Test
  fun `create removes the container when inactive and keeps it visible`() {
    stubSaveAssigningId()

    service.sync(request(active = false, expiryDate = LocalDate.parse("2026-09-15")))

    val saved = captureSaved()
    assertThat(saved.removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
    assertThat(saved.removalDate).isEqualTo(LocalDate.parse("2026-09-15"))
    assertThat(saved.currentStatus()).isEqualTo(ContainerStatus.REMOVED)
    assertThat(saved.currentLocation()).isNull()
    assertThat(saved.events.map { it.eventType }).contains(PropertyEventType.REMOVED)
  }

  @Test
  fun `create inactive with no expiry date still removes with no removal date`() {
    stubSaveAssigningId()

    service.sync(request(active = false, expiryDate = null))

    val saved = captureSaved()
    assertThat(saved.removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
    assertThat(saved.removalDate).isNull()
    assertThat(saved.currentStatus()).isEqualTo(ContainerStatus.REMOVED)
  }

  @Test
  fun `deactivating an existing container removes it and reports a removed change`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.sync(request(dpsId = existing.id, active = false, expiryDate = LocalDate.parse("2026-09-15")))

    assertThat(existing.removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
    assertThat(existing.removalDate).isEqualTo(LocalDate.parse("2026-09-15"))
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.REMOVED)
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.updated")
    assertThat(result.event?.additionalInformation?.get("changedFields")).isEqualTo(listOf("removed"))
  }

  @Test
  fun `reactivating a removed container clears the removal and records a reactivated event`() {
    val existing = existingContainer()
    existing.removalOutcome = RemovalOutcome.REMOVED
    existing.removalDate = LocalDate.parse("2026-09-15")
    existing.refreshDerivedState()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.sync(request(dpsId = existing.id, active = true))

    assertThat(existing.removalOutcome).isNull()
    assertThat(existing.removalDate).isNull()
    assertThat(existing.currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.REACTIVATED)
    @Suppress("UNCHECKED_CAST")
    assertThat(result.event?.additionalInformation?.get("changedFields") as List<String>).contains("removed")
  }

  @Test
  fun `migrate returns no event to publish`() {
    stubSaveAssigningId()

    val result = service.migrate(request())

    assertThat(result.event).isNull()
  }

  @Test
  fun `create for a Branston container records the Branston location with no internal id`() {
    stubSaveAssigningId()

    service.sync(request(containerCode = NomisContainerCode.BRANSTON_STORAGE, internalLocationId = null))

    val saved = captureSaved()
    assertThat(saved.containerType).isEqualTo(ContainerType.EXCESS)
    assertThat(saved.currentLocation()).isNull()
    assertThat(saved.currentLocationType()).isEqualTo(StorageLocationType.BRANSTON)
  }

  @Test
  fun `create with a proposed disposal date that has arisen records disposal required`() {
    stubSaveAssigningId()

    // A past date so the disposal is due now (disposal is time-based).
    service.sync(request(proposedDisposalDate = LocalDate.parse("2026-01-01")))

    val saved = captureSaved()
    assertThat(saved.proposedDisposalDate).isEqualTo(LocalDate.parse("2026-01-01"))
    assertThat(saved.currentStatus()).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
    assertThat(saved.events.map { it.eventType }).contains(PropertyEventType.DISPOSAL_REQUIRED)
  }

  @Test
  fun `create with an expiry date but still active does not dispose the container`() {
    stubSaveAssigningId()

    // Regression: NOMIS may carry a (future) EXPIRY_DATE while ACTIVE_FLAG='Y' - the container must stay stored.
    service.sync(request(active = true, expiryDate = LocalDate.parse("2027-01-01"), proposedDisposalDate = LocalDate.parse("2030-01-01")))

    val saved = captureSaved()
    assertThat(saved.removalOutcome).isNull()
    assertThat(saved.removalDate).isNull()
    assertThat(saved.currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(saved.currentLocation()).isEqualTo(LOCATION)
    assertThat(saved.events.map { it.eventType }).doesNotContain(PropertyEventType.REMOVED)
  }

  @Test
  fun `re-syncing an unchanged snapshot is a no-op with no event`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.sync(request(dpsId = existing.id))

    assertThat(result.response.mappingType).isEqualTo(SyncMappingType.UPDATED)
    assertThat(result.event).isNull()
    assertThat(existing.events).hasSize(1)
    verify(repository, never()).save(any())
  }

  @Test
  fun `a changed seal appends a seal-changed event and returns an update event`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    val result = service.sync(request(dpsId = existing.id, sealMark = "SEAL2"))

    assertThat(existing.currentSealNumber).isEqualTo("SEAL2")
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.SEAL_CHANGED)
    assertThat(result.event?.eventType).isEqualTo("prison-property.container.updated")
    assertThat(result.event?.additionalInformation?.get("changedFields")).isEqualTo(listOf("sealNumber"))
  }

  @Test
  fun `a changed location appends a move event`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))
    val newLocation = UUID.fromString("33333333-3333-3333-3333-333333333333")

    service.sync(request(dpsId = existing.id, internalLocationId = newLocation))

    assertThat(existing.currentLocation()).isEqualTo(newLocation)
    val moved = existing.events.last()
    assertThat(moved.eventType).isEqualTo(PropertyEventType.MOVED)
    assertThat(moved.fromInternalLocationId).isEqualTo(LOCATION)
    assertThat(moved.toInternalLocationId).isEqualTo(newLocation)
  }

  @Test
  fun `removing an existing container appends a removed event and clears the location`() {
    val existing = existingContainer()
    whenever(repository.findById(existing.id!!)).thenReturn(Optional.of(existing))

    service.sync(request(dpsId = existing.id, active = false, expiryDate = LocalDate.parse("2026-09-15")))

    assertThat(existing.removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
    assertThat(existing.removalDate).isEqualTo(LocalDate.parse("2026-09-15"))
    assertThat(existing.currentStatus()).isEqualTo(ContainerStatus.REMOVED)
    assertThat(existing.currentLocation()).isNull()
    assertThat(existing.events.last().eventType).isEqualTo(PropertyEventType.REMOVED)
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
      createdByUserId = "USER1",
      createDateTime = CREATE_TIME,
      currentSealNumber = "SEAL1",
      id = UUID.randomUUID(),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, CREATE_TIME, "USER1", sealNumber = "SEAL1", toInternalLocationId = LOCATION),
    )
    return container
  }

  private fun request(
    dpsId: UUID? = null,
    sealMark: String? = "SEAL1",
    internalLocationId: UUID? = LOCATION,
    containerCode: NomisContainerCode = NomisContainerCode.BULK,
    proposedDisposalDate: LocalDate? = null,
    expiryDate: LocalDate? = null,
    active: Boolean = true,
  ) = SyncPropertyContainerRequest(
    nomisPropertyContainerId = 123,
    dpsId = dpsId,
    prisonerNumber = "A1234BC",
    prisonId = "LEI",
    containerCode = containerCode,
    internalLocationId = internalLocationId,
    sealMark = sealMark,
    proposedDisposalDate = proposedDisposalDate,
    expiryDate = expiryDate,
    createDateTime = CREATE_TIME,
    createUsername = "USER1",
    modifyDateTime = MODIFY_TIME,
    modifyUsername = "USER2",
    active = active,
  )

  private companion object {
    private val CREATE_TIME: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val MODIFY_TIME: LocalDateTime = LocalDateTime.parse("2026-02-01T09:00:00")
    private val LOCATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
  }
}
