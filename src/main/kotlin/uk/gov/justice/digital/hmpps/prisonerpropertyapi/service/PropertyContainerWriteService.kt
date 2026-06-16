package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import jakarta.validation.ValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.DisposeContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.RemoveContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyContainerEventFactory
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyDomainEventType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Creates and updates property containers from staff actions, translating each change into the
 * event-sourced domain. The service is the transaction boundary; it *builds* the domain event to
 * raise (if any) and returns it in a [WriteResult] so the resource can publish it after commit.
 */
@Service
class PropertyContainerWriteService(
  private val repository: PropertyContainerRepository,
) {

  @Transactional
  fun create(request: CreatePropertyContainerRequest, username: String): WriteResult {
    if (repository.existsByCurrentSealNumberAndRemovalOutcomeIsNull(request.sealNumber)) {
      throw DuplicateSealNumberException(request.sealNumber)
    }
    val now = LocalDateTime.now()
    val container = PropertyContainer(
      prisonerNumber = request.prisonerNumber,
      prisonId = request.prisonId,
      containerType = request.containerType,
      createdByUserId = username,
      createDateTime = now,
      proposedDisposalDate = request.proposedDisposalDate,
      currentSealNumber = request.sealNumber,
    )
    container.events.add(
      PropertyEvent(
        container = container,
        eventType = PropertyEventType.CREATED_SEALED,
        eventDateTime = now,
        eventUserId = username,
        sealNumber = request.sealNumber,
        toInternalLocationId = request.internalLocationId,
        toStorageLocationType = request.internalLocationId?.let { StorageLocationType.INTERNAL },
        toPrisonId = request.prisonId,
      ),
    )
    if (request.proposedDisposalDate != null) {
      container.events.add(
        PropertyEvent(container, PropertyEventType.DISPOSAL_REQUIRED, now, username, eventDate = request.proposedDisposalDate),
      )
    }

    val saved = repository.save(container)
    val event = PropertyContainerEventFactory.staffEvent(PropertyDomainEventType.CONTAINER_CREATED, saved.id!!, request.prisonerNumber, changedFields = null)
    return WriteResult(PropertyContainerDto.from(saved), event)
  }

  @Transactional
  fun update(id: UUID, request: UpdatePropertyContainerRequest, username: String): WriteResult {
    val container = repository.findById(id).orElseThrow { PropertyContainerNotFoundException(id) }
    val now = LocalDateTime.now()
    val changed = mutableListOf<String>()

    if (request.sealNumber != container.currentSealNumber) {
      if (repository.existsByCurrentSealNumberAndRemovalOutcomeIsNullAndIdNot(request.sealNumber, id)) {
        throw DuplicateSealNumberException(request.sealNumber)
      }
      container.currentSealNumber = request.sealNumber
      container.events.add(PropertyEvent(container, PropertyEventType.SEAL_CHANGED, now, username, sealNumber = request.sealNumber))
      changed += "sealNumber"
    }

    if (request.containerType != container.containerType) {
      container.containerType = request.containerType
      container.events.add(PropertyEvent(container, PropertyEventType.CONTAINER_TYPE_CHANGE, now, username))
      changed += "containerType"
    }

    if (request.internalLocationId != null &&
      (container.currentLocationType() != StorageLocationType.INTERNAL || request.internalLocationId != container.currentLocation())
    ) {
      container.events.add(
        PropertyEvent(
          container,
          PropertyEventType.MOVED,
          now,
          username,
          fromInternalLocationId = container.currentLocation(),
          toInternalLocationId = request.internalLocationId,
          toStorageLocationType = StorageLocationType.INTERNAL,
        ),
      )
      changed += "location"
    }

    if (request.proposedDisposalDate != container.proposedDisposalDate) {
      container.proposedDisposalDate = request.proposedDisposalDate
      if (request.proposedDisposalDate != null) {
        container.events.add(PropertyEvent(container, PropertyEventType.DISPOSAL_REQUIRED, now, username, eventDate = request.proposedDisposalDate))
      }
      changed += "proposedDisposalDate"
    }

    var event: HmppsDomainEvent? = null
    if (changed.isNotEmpty()) {
      repository.save(container)
      event = PropertyContainerEventFactory.staffEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, container.prisonerNumber, changed)
    }
    return WriteResult(PropertyContainerDto.from(container), event)
  }

  /** Dispose of (destroy) a container, taking it out of active storage. */
  @Transactional
  fun dispose(id: UUID, request: DisposeContainerRequest, username: String): WriteResult {
    val container = loadActive(id)
    val date = request.disposalDate ?: LocalDate.now()
    container.events.add(
      PropertyEvent(container, PropertyEventType.DISPOSED, LocalDateTime.now(), username, eventDate = date, fromPrisonId = container.prisonId),
    )
    return container.removeWith(RemovalOutcome.DISPOSED, date)
  }

  /** Remove a container from active storage by returning it to the prisoner or transferring it to another prison. */
  @Transactional
  fun remove(id: UUID, request: RemoveContainerRequest, username: String): WriteResult {
    if (request.outcome != RemovalOutcome.RETURNED && request.outcome != RemovalOutcome.TRANSFERRED) {
      throw ValidationException("Removal outcome must be RETURNED or TRANSFERRED, was ${request.outcome}")
    }
    if (request.outcome == RemovalOutcome.TRANSFERRED && request.toPrisonId.isNullOrBlank()) {
      throw ValidationException("toPrisonId is required when transferring a container")
    }
    val container = loadActive(id)
    val date = request.date ?: LocalDate.now()
    container.events.add(
      PropertyEvent(container, request.outcome.eventType, LocalDateTime.now(), username, eventDate = date, fromPrisonId = container.prisonId, toPrisonId = request.toPrisonId),
    )
    return container.removeWith(request.outcome, date)
  }

  private fun loadActive(id: UUID): PropertyContainer {
    val container = repository.findById(id).orElseThrow { PropertyContainerNotFoundException(id) }
    container.removalOutcome?.let { throw ContainerAlreadyRemovedException(id, it) }
    return container
  }

  private fun PropertyContainer.removeWith(outcome: RemovalOutcome, date: LocalDate): WriteResult {
    removalOutcome = outcome
    removalDate = date
    repository.save(this)
    val event = PropertyContainerEventFactory.staffEvent(PropertyDomainEventType.CONTAINER_UPDATED, id!!, prisonerNumber, listOf("removalOutcome"))
    return WriteResult(PropertyContainerDto.from(this), event)
  }
}

/**
 * The outcome of a staff create/update: the [container] to return plus the domain [event] to publish
 * *after* the transaction commits (null when an update made no change).
 */
data class WriteResult(val container: PropertyContainerDto, val event: HmppsDomainEvent?)
