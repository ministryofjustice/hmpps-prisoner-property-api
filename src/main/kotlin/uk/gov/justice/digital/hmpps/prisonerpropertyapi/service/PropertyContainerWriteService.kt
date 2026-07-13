package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import jakarta.validation.ValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
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
  private val locationsClient: LocationsClient,
) {

  /**
   * Reject an internal location that cannot hold the container being placed there (raises a 400):
   * - it is not a known non-residential location, or
   * - it cannot store property (it has no PROPERTY usage - any location type may, not only BOX), or
   * - it is full (the containers already there, excluding [excludingContainerIds], reach its capacity).
   *
   * [excludingContainerIds] are the container(s) being written, so a container is not counted against the
   * capacity of a location it is being moved into or updated within, and combine sources do not block the
   * combined container. The capacity check is a best-effort backstop - the picker only offers locations with
   * space, but concurrent writes could still race, so the final guard is here.
   */
  private fun requireValidLocation(internalLocationId: UUID?, excludingContainerIds: Set<UUID> = emptySet()) {
    if (internalLocationId == null) return
    val location = locationsClient.getLocation(internalLocationId)
      ?: throw InvalidLocationException(internalLocationId, "not found")
    if (!location.canStoreProperty()) {
      throw InvalidLocationException(internalLocationId, "cannot store property")
    }
    val used = repository.countContainersInLocation(internalLocationId, excludingContainerIds.takeIf { it.isNotEmpty() })
    if (used >= location.propertyCapacity()) {
      throw InvalidLocationException(internalLocationId, "is full")
    }
  }

  /**
   * Create a new sealed container. When [CreatePropertyContainerRequest.previousSealNumber] is supplied and
   * matches a container the same prisoner has due for transfer out at another prison, that container is the
   * property physically arriving here on transfer: it is linked to the new record and deactivated (TRANSFERRED)
   * so its seal and location are freed and no ghost record is left behind. An unmatched previous seal is
   * ignored (the add still succeeds). Returns the created container plus the events to publish after commit -
   * one for the new container, and one for the source when a transfer-in was reconciled.
   */
  @Transactional
  fun create(request: CreatePropertyContainerRequest, username: String): CreateResult {
    requireValidLocation(request.internalLocationId)

    val source = request.previousSealNumber?.let { previousSeal ->
      repository.findByPrisonerNumberAndArchivedFalse(request.prisonerNumber).firstOrNull {
        !it.isRemoved() &&
          it.prisonId != request.prisonId &&
          it.currentSealNumber == previousSeal &&
          it.currentStatus() == ContainerStatus.DUE_FOR_TRANSFER_OUT
      }
    }

    val sealInUse = source?.let { repository.existsByCurrentSealNumberAndRemovalOutcomeIsNullAndIdNot(request.sealNumber, it.id!!) }
      ?: repository.existsByCurrentSealNumberAndRemovalOutcomeIsNull(request.sealNumber)
    if (sealInUse) {
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
        relatedContainerId = source?.id,
      ),
    )
    if (request.proposedDisposalDate != null) {
      container.events.add(
        PropertyEvent(container, PropertyEventType.DISPOSAL_REQUIRED, now, username, eventDate = request.proposedDisposalDate),
      )
    }

    container.refreshDerivedState()
    val saved = repository.save(container)
    val events = mutableListOf(
      PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_CREATED, saved.id!!, request.prisonerNumber, changedFields = null),
    )

    source?.let {
      it.events.add(
        PropertyEvent(it, PropertyEventType.TRANSFERRED, now, username, eventDate = LocalDate.now(), fromPrisonId = it.prisonId, toPrisonId = request.prisonId, relatedContainerId = saved.id),
      )
      it.removalOutcome = RemovalOutcome.TRANSFERRED
      it.removalDate = LocalDate.now()
      it.refreshDerivedState()
      events += PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, it.id!!, it.prisonerNumber, listOf("removalOutcome"))
    }

    return CreateResult(PropertyContainerDto.from(saved), events)
  }

  @Transactional
  fun update(id: UUID, request: UpdatePropertyContainerRequest, username: String): WriteResult {
    requireValidLocation(request.internalLocationId, excludingContainerIds = setOf(id))

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
      container.refreshDerivedState()
      event = PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, container.prisonerNumber, changed)
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

  /**
   * Remove a container from active storage for a given [RemovalOutcome.RETURNED], [RemovalOutcome.DISPOSED],
   * [RemovalOutcome.CREATED_IN_ERROR], or [RemovalOutcome.TRANSFERRED]. Returned/disposed/created-in-error are
   * terminal (the container leaves active storage and its location and seal are freed). Transferred is a
   * hand-off: the container stays active but its holding prison is reassigned to the receiving prison
   * (see [transferTo]) so responsibility moves with the property. COMBINED is not accepted here - use combine.
   */
  @Transactional
  fun remove(id: UUID, request: RemoveContainerRequest, username: String): WriteResult {
    if (request.outcome == RemovalOutcome.COMBINED) {
      throw ValidationException("Use the combine endpoint to combine containers")
    }
    if (request.outcome == RemovalOutcome.TRANSFERRED && request.toPrisonId.isNullOrBlank()) {
      throw ValidationException("toPrisonId is required when transferring a container")
    }
    val container = loadActive(id)
    val date = request.date ?: LocalDate.now()
    if (request.outcome == RemovalOutcome.TRANSFERRED) {
      return container.transferTo(request.toPrisonId!!, username, date)
    }
    container.events.add(
      PropertyEvent(container, request.outcome.eventType, LocalDateTime.now(), username, eventDate = date, fromPrisonId = container.prisonId),
    )
    return container.removeWith(request.outcome, date)
  }

  /**
   * Combine the property of two or more source containers into a single new sealed container. The
   * sources must share one prisoner and prison (inherited by the new container) and be active; each is
   * removed from active storage (COMBINED) with a link to the new container.
   */
  @Transactional
  fun combine(request: CombineContainersRequest, username: String): CombineResult {
    val sources = request.sourceContainerIds.map { sourceId ->
      repository.findById(sourceId).orElseThrow { PropertyContainerNotFoundException(sourceId) }
    }
    if (sources.map { it.prisonerNumber }.distinct().size != 1 || sources.map { it.prisonId }.distinct().size != 1) {
      throw ValidationException("All source containers must belong to the same prisoner and prison")
    }
    sources.firstOrNull { it.isRemoved() }?.let {
      throw ValidationException("Source container has already left active storage: ${it.id}")
    }
    if (repository.existsByCurrentSealNumberAndRemovalOutcomeIsNull(request.sealNumber)) {
      throw DuplicateSealNumberException(request.sealNumber)
    }

    val prisonerNumber = sources.first().prisonerNumber
    val prisonId = sources.first().prisonId
    val now = LocalDateTime.now()
    val today = LocalDate.now()
    val locationType = request.locationType ?: request.internalLocationId?.let { StorageLocationType.INTERNAL }
    val internalLocationId = if (locationType == StorageLocationType.INTERNAL) request.internalLocationId else null
    // The source containers are being combined away, so they must not count against the target's capacity.
    requireValidLocation(internalLocationId, excludingContainerIds = sources.mapNotNull { it.id }.toSet())

    val combined = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      containerType = request.containerType,
      createdByUserId = username,
      createDateTime = now,
      currentSealNumber = request.sealNumber,
    )
    combined.events.add(
      PropertyEvent(
        container = combined,
        eventType = PropertyEventType.CREATED_SEALED,
        eventDateTime = now,
        eventUserId = username,
        sealNumber = request.sealNumber,
        toInternalLocationId = internalLocationId,
        toStorageLocationType = locationType,
        toPrisonId = prisonId,
      ),
    )
    combined.refreshDerivedState()
    val saved = repository.save(combined)

    val events = mutableListOf(
      PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_CREATED, saved.id!!, prisonerNumber, changedFields = null),
    )
    sources.forEach { source ->
      source.events.add(
        PropertyEvent(source, PropertyEventType.COMBINED, now, username, eventDate = today, relatedContainerId = saved.id),
      )
      source.removalOutcome = RemovalOutcome.COMBINED
      source.removalDate = today
      source.refreshDerivedState()
      events += PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, source.id!!, source.prisonerNumber, listOf("removalOutcome"))
    }

    return CombineResult(PropertyContainerDto.from(saved), events)
  }

  /** Move a container to an internal prison location or offsite to the Branston warehouse. */
  @Transactional
  fun move(id: UUID, request: MoveContainerRequest, username: String): WriteResult {
    if (request.locationType == StorageLocationType.INTERNAL && request.internalLocationId == null) {
      throw ValidationException("internalLocationId is required for an internal move")
    }
    if (request.locationType == StorageLocationType.BRANSTON && request.internalLocationId != null) {
      throw ValidationException("internalLocationId must not be set for a Branston move")
    }
    val targetId = if (request.locationType == StorageLocationType.INTERNAL) request.internalLocationId else null
    requireValidLocation(targetId, excludingContainerIds = setOf(id))

    val container = loadActive(id)

    if (request.locationType == container.currentLocationType() && targetId == container.currentLocation()) {
      return WriteResult(PropertyContainerDto.from(container), null)
    }

    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.MOVED,
        LocalDateTime.now(),
        username,
        fromInternalLocationId = container.currentLocation(),
        toInternalLocationId = targetId,
        toStorageLocationType = request.locationType,
      ),
    )
    container.refreshDerivedState()
    val event = PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, container.prisonerNumber, listOf("location"))
    return WriteResult(PropertyContainerDto.from(container), event)
  }

  /**
   * Handle a prisoner being received into [newPrisonId]. Every active container still recorded at a
   * different prison is flagged due for transfer out by appending a [PropertyEventType.PRISONER_RECEIVED]
   * event (the container itself stays at the sending prison until the receiving prison adds it - only its
   * derived status and history change). Idempotent: containers already due for transfer out to this same
   * destination are skipped, so repeated/duplicate receive events do nothing. Returns one
   * [PropertyDomainEventType.CONTAINER_UPDATED] event per container changed, for the caller to publish
   * after commit.
   */
  @Transactional
  fun prisonerReceived(prisonerNumber: String, newPrisonId: String): List<HmppsDomainEvent> {
    val now = LocalDateTime.now()
    return repository.findByPrisonerNumberAndArchivedFalse(prisonerNumber)
      .filter { !it.isRemoved() && it.prisonId != newPrisonId && !it.isAlreadyDueForTransferOut(newPrisonId) }
      .map { container ->
        container.events.add(
          PropertyEvent(
            container,
            PropertyEventType.PRISONER_RECEIVED,
            now,
            SYSTEM_USER,
            fromPrisonId = container.prisonId,
            toPrisonId = newPrisonId,
          ),
        )
        container.refreshDerivedState()
        PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, prisonerNumber, listOf("currentStatus"))
      }
  }

  private fun PropertyContainer.isAlreadyDueForTransferOut(newPrisonId: String): Boolean = currentStatus() == ContainerStatus.DUE_FOR_TRANSFER_OUT &&
    events.maxByOrNull { it.eventDateTime }?.toPrisonId == newPrisonId

  /**
   * Handle a prisoner being released from custody (or dying in custody). Every active container the
   * prisoner still has - at any prison - is flagged due for return by appending a
   * [PropertyEventType.PRISONER_RELEASED] event; the container stays where it is, only its derived status
   * and history change. Idempotent: containers already due for return are skipped, so the delayed and
   * potentially duplicated release events are safe no-ops. Returns one
   * [PropertyDomainEventType.CONTAINER_UPDATED] event per container changed, to publish after commit.
   */
  @Transactional
  fun prisonerReleased(prisonerNumber: String): List<HmppsDomainEvent> {
    val now = LocalDateTime.now()
    return repository.findByPrisonerNumberAndArchivedFalse(prisonerNumber)
      .filter { !it.isRemoved() && it.baseStatus() != ContainerStatus.DUE_FOR_RETURN }
      .map { container ->
        container.events.add(
          PropertyEvent(container, PropertyEventType.PRISONER_RELEASED, now, SYSTEM_USER, fromPrisonId = container.prisonId),
        )
        container.refreshDerivedState()
        PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, prisonerNumber, listOf("currentStatus"))
      }
  }

  private fun loadActive(id: UUID): PropertyContainer {
    val container = repository.findById(id).orElseThrow { PropertyContainerNotFoundException(id) }
    container.removalOutcome?.let { throw ContainerAlreadyRemovedException(id, it) }
    return container
  }

  /**
   * Transfer a container to the prisoner's new establishment. The container stays active (not removed):
   * its holding prison is reassigned to [toPrisonId] and its storage location is cleared (the receiving
   * prison assigns its own), recorded by a [PropertyEventType.TRANSFERRED] event. It then leaves the
   * sending prison's list and appears - active and editable - in the receiving prison's list.
   */
  private fun PropertyContainer.transferTo(toPrisonId: String, username: String, date: LocalDate): WriteResult {
    events.add(
      PropertyEvent(this, PropertyEventType.TRANSFERRED, LocalDateTime.now(), username, eventDate = date, fromPrisonId = prisonId, toPrisonId = toPrisonId),
    )
    prisonId = toPrisonId
    refreshDerivedState()
    val event = PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, id!!, prisonerNumber, listOf("prisonId", "location"))
    return WriteResult(PropertyContainerDto.from(this), event)
  }

  private fun PropertyContainer.removeWith(outcome: RemovalOutcome, date: LocalDate): WriteResult {
    removalOutcome = outcome
    removalDate = date
    refreshDerivedState()
    val event = PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, id!!, prisonerNumber, listOf("removalOutcome"))
    return WriteResult(PropertyContainerDto.from(this), event)
  }

  private companion object {
    /** Event user id recorded for changes driven by an external domain event rather than a member of staff. */
    private const val SYSTEM_USER = "PRISONER_PROPERTY_API"
  }
}

/**
 * The outcome of a staff create/update: the [container] to return plus the domain [event] to publish
 * *after* the transaction commits (null when an update made no change).
 */
data class WriteResult(val container: PropertyContainerDto, val event: HmppsDomainEvent?)

/**
 * The outcome of a combine: the new [container] plus the domain [events] to publish *after* the
 * transaction commits (a created event for the new container and an updated event per source).
 */
data class CombineResult(val container: PropertyContainerDto, val events: List<HmppsDomainEvent>)

/**
 * The outcome of a create: the new [container] plus the domain [events] to publish *after* the
 * transaction commits (a created event for the new container, and - when an arriving container was
 * reconciled against a due-for-transfer-out record - an updated event for that source).
 */
data class CreateResult(val container: PropertyContainerDto, val events: List<HmppsDomainEvent>)
