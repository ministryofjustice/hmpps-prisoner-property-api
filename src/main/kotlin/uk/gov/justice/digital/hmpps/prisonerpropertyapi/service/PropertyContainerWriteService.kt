package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import com.microsoft.applicationinsights.TelemetryClient
import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.ContainerState
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyContainerEventFactory
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyDomainEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyTelemetry
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.changedFieldsSince
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Creates and updates property containers from staff actions, translating each change into the
 * event-sourced domain. The service is the transaction boundary; it *builds* the domain event to
 * raise (if any) and returns it in a [WriteResult] so the resource can publish it after commit.
 *
 * Every path that changes an existing container snapshots its [ContainerState] before mutating and derives
 * the event's `changedFields` from the difference (see [changedFieldsSince]) rather than naming the fields
 * by hand. A write that turns out to change nothing observable therefore raises no event at all.
 */
@Service
class PropertyContainerWriteService(
  private val repository: PropertyContainerRepository,
  private val locationsClient: LocationsClient,
  private val telemetryClient: TelemetryClient,
) {

  /**
   * Reject an internal location that cannot hold the container being placed there (raises a 400):
   * - it cannot store property (it is unknown, or has no PROPERTY usage - any location type may, not only
   *   BOX; locations-inside-prison resolves both to "not a property storage location"), or
   * - it is full (the containers already there, excluding [excludingContainerIds], reach its capacity).
   *
   * [excludingContainerIds] are the container(s) being written, so a container is not counted against the
   * capacity of a location it is being moved into or updated within, and combine sources do not block the
   * combined container. The capacity check is a best-effort backstop - the picker only offers locations with
   * space, but concurrent writes could still race, so the final guard is here.
   */
  private fun requireValidLocation(internalLocationId: UUID?, excludingContainerIds: Set<UUID> = emptySet()) {
    if (internalLocationId == null) return
    val location = locationsClient.getPropertyLocation(internalLocationId)
      ?: throw InvalidLocationException(internalLocationId, "is not a property storage location")
    val used = repository.countContainersInLocation(internalLocationId, excludingContainerIds.takeIf { it.isNotEmpty() })
    if (used >= (location.capacity ?: 0)) {
      throw InvalidLocationException(internalLocationId, "is full")
    }
  }

  /**
   * The storage location type to record for a create/move, from the [requested] type and whether an
   * [internalLocationId] was given: an internal location always implies INTERNAL; an explicit BRANSTON (with no
   * internal location) means offsite; anything else leaves the type unset. BRANSTON with an internal location is
   * contradictory and rejected.
   */
  private fun resolveLocationType(requested: StorageLocationType?, internalLocationId: UUID?): StorageLocationType? {
    if (requested == StorageLocationType.BRANSTON && internalLocationId != null) {
      throw ValidationException("internalLocationId must not be set for Branston storage")
    }
    return when {
      internalLocationId != null -> StorageLocationType.INTERNAL
      requested == StorageLocationType.BRANSTON -> StorageLocationType.BRANSTON
      else -> null
    }
  }

  /**
   * Whether two prison ids name the same establishment. Compared trimmed and case-insensitively because prison
   * ids reach us unnormalised - RemoveContainerRequest.toPrisonId is only checked for blankness, so a stray
   * " MDI" or "mdi" is stored verbatim on the transfer event and would otherwise never match again.
   */
  private fun String?.equalsPrisonId(other: String?): Boolean = this?.trim().equals(other?.trim(), ignoreCase = true)

  /**
   * Whether this container has been transferred out but not yet logged anywhere - i.e. it is somewhere between
   * two establishments and still needs a record making at whichever one it turns up at.
   *
   * Deliberately does *not* require the arrival to be at the destination the sending prison named. That
   * destination is the sender's expectation, recorded when they marked it transferred out; the person may have
   * moved on again since, or it may simply have been wrong. Staff standing over the box are the better
   * evidence. Requiring it meant a box sent to one prison and arriving at another could not be logged at all,
   * while the sender's record showed it in transit indefinitely.
   *
   * Still requires the transfer to be unreconciled: once its event names a related container, the arrival has
   * been logged and the box must not be matched a second time.
   */
  private fun PropertyContainer.isAwaitingArrival(): Boolean = removalOutcome == RemovalOutcome.TRANSFERRED && latestTransferEvent()?.relatedContainerId == null

  /**
   * Create a new sealed container. When [CreatePropertyContainerRequest.previousSealNumber] is supplied it names
   * the record the property was held under at the prison it has come from: that container is the same physical
   * property, so it is linked to this new record and deactivated (TRANSFERRED), freeing its seal and location
   * and leaving no ghost record behind. Both records' histories name the other's seal, so the match is
   * visible rather than implied.
   *
   * A previous seal that matches nothing is rejected, not ignored - silently creating the container is how one
   * box ended up recorded twice, once here and once still awaiting transfer at the sending prison.
   *
   * Returns the created container plus the events to publish after commit - one for the new container, and one
   * for the source when a transfer-in was reconciled.
   */
  @Transactional
  fun create(request: CreatePropertyContainerRequest, username: String): CreateResult {
    requireValidLocation(request.internalLocationId)
    // Where the container is stored: an internal prison location (given by internalLocationId), or offsite at
    // Branston (BRANSTON, no internal location - used for excess property held offsite). INTERNAL is implied by
    // an internalLocationId; a null type with no location is left unset (unknown location).
    val locationType = resolveLocationType(request.locationType, request.internalLocationId)

    // The property physically arriving here on transfer: the prisoner's container at another prison held under
    // the previous seal. Any container still in storage there qualifies - plenty of prisons never record the
    // transfer out, so requiring them to have flagged it first meant the common case never matched and a second
    // record was created for the same box. Staff typing that seal are asserting the property arrived here, so
    // deactivating the sending record is the intent. A container already transferred out but not yet logged
    // anywhere also matches: this add is what reconciles it.
    val source = request.previousSealNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { previousSeal ->
      repository.findByPrisonerNumber(request.prisonerNumber).firstOrNull { candidate ->
        !candidate.prisonId.equalsPrisonId(request.prisonId) &&
          // Case- and whitespace-insensitive, like the establishment seal search: staff should not have to
          // reproduce the exact form the sending prison recorded.
          candidate.currentSealNumber?.trim().equals(previousSeal, ignoreCase = true) &&
          (!candidate.isRemoved() || candidate.isAwaitingArrival())
      } ?: throw PreviousSealNumberNotFoundException(previousSeal)
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
        toStorageLocationType = locationType,
        toPrisonId = request.prisonId,
        relatedContainerId = source?.id,
        // The seal it arrived under, so the history reads "matched to previous seal X" rather than leaving
        // staff to guess whether the two records were joined up.
        relatedContainerSealNumber = source?.currentSealNumber,
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
      val before = ContainerState.of(it)
      if (it.isRemoved()) {
        // Already transferred out by the sending prison: reconcile by linking its TRANSFERRED event to this
        // new record, so it stops surfacing as awaiting at this prison. It is already removed - don't re-remove.
        // The existing event is amended rather than a new one appended, so the history keeps the real
        // transfer date and simply gains the seal it was matched to.
        it.latestTransferEvent()?.apply {
          relatedContainerId = saved.id
          relatedContainerSealNumber = request.sealNumber
        }
        it.refreshDerivedState()
      } else {
        // Still held at the sending prison: mark it transferred out and linked to this new record in one step.
        it.events.add(
          PropertyEvent(
            it,
            PropertyEventType.TRANSFERRED,
            now,
            username,
            eventDate = LocalDate.now(),
            fromPrisonId = it.prisonId,
            toPrisonId = request.prisonId,
            relatedContainerId = saved.id,
            relatedContainerSealNumber = request.sealNumber,
          ),
        )
        it.removalOutcome = RemovalOutcome.TRANSFERRED
        it.removalDate = LocalDate.now()
        it.refreshDerivedState()
      }
      events += PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, it.id!!, it.prisonerNumber, it.changedFieldsSince(before))
    }

    return CreateResult(PropertyContainerDto.from(saved), events)
  }

  @Transactional
  fun update(id: UUID, request: UpdatePropertyContainerRequest, username: String): WriteResult {
    val container = repository.findById(id).orElseThrow { PropertyContainerNotFoundException(id) }
    val now = LocalDateTime.now()
    val before = ContainerState.of(container)

    if (request.locationType == StorageLocationType.BRANSTON && request.internalLocationId != null) {
      throw ValidationException("internalLocationId must not be set for Branston storage")
    }

    // Only a genuine move needs the target location validated. Re-checking a location the container is
    // already in would block edits to its seal/type/disposal date when that location is no longer a valid
    // property store - e.g. migrated data, or a designation removed or changed after the container was
    // placed there - trapping the container so it could not even be edited to move it out.
    val movingToInternal = request.internalLocationId != null &&
      (container.currentLocationType() != StorageLocationType.INTERNAL || request.internalLocationId != container.currentLocation())
    // A move offsite to Branston: requested explicitly and not already there. Excess property held offsite has
    // no internal location, so this clears any internal location it was in.
    val movingToBranston = request.locationType == StorageLocationType.BRANSTON &&
      container.currentLocationType() != StorageLocationType.BRANSTON
    if (movingToInternal) {
      requireValidLocation(request.internalLocationId, excludingContainerIds = setOf(id))
    }

    if (request.sealNumber != container.currentSealNumber) {
      if (repository.existsByCurrentSealNumberAndRemovalOutcomeIsNullAndIdNot(request.sealNumber, id)) {
        throw DuplicateSealNumberException(request.sealNumber)
      }
      container.currentSealNumber = request.sealNumber
      container.events.add(PropertyEvent(container, PropertyEventType.SEAL_CHANGED, now, username, sealNumber = request.sealNumber))
    }

    if (request.containerType != container.containerType) {
      container.containerType = request.containerType
      container.events.add(PropertyEvent(container, PropertyEventType.CONTAINER_TYPE_CHANGE, now, username))
    }

    if (movingToInternal) {
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
    } else if (movingToBranston) {
      container.events.add(
        PropertyEvent(
          container,
          PropertyEventType.MOVED,
          now,
          username,
          fromInternalLocationId = container.currentLocation(),
          toStorageLocationType = StorageLocationType.BRANSTON,
        ),
      )
    }

    if (request.proposedDisposalDate != container.proposedDisposalDate) {
      container.proposedDisposalDate = request.proposedDisposalDate
      if (request.proposedDisposalDate != null) {
        container.events.add(PropertyEvent(container, PropertyEventType.DISPOSAL_REQUIRED, now, username, eventDate = request.proposedDisposalDate))
      }
    }

    container.refreshDerivedState()
    val changed = container.changedFieldsSince(before)
    val event = changed.takeIf { it.isNotEmpty() }?.let {
      PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, container.prisonerNumber, it)
    }
    return WriteResult(PropertyContainerDto.from(container), event)
  }

  /** Dispose of (destroy) a container, taking it out of active storage. */
  @Transactional
  fun dispose(id: UUID, request: DisposeContainerRequest, username: String): WriteResult {
    val container = loadActive(id)
    val date = request.disposalDate ?: LocalDate.now()
    val before = ContainerState.of(container)
    container.events.add(
      PropertyEvent(container, PropertyEventType.DISPOSED, LocalDateTime.now(), username, eventDate = date, fromPrisonId = container.prisonId),
    )
    return container.removeWith(RemovalOutcome.DISPOSED, date, before)
  }

  /**
   * Remove a container from active storage for a given [RemovalOutcome.RETURNED], [RemovalOutcome.DISPOSED],
   * [RemovalOutcome.CREATED_IN_ERROR], or [RemovalOutcome.TRANSFERRED]. Returned/disposed/created-in-error are
   * terminal (the container leaves active storage and its location and seal are freed). Transferred also
   * removes it from the sending prison (see [transferTo]) but records the destination and is later
   * reconciled against the record the receiving prison creates. COMBINED is not accepted here - use combine.
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
    val before = ContainerState.of(container)
    container.events.add(
      PropertyEvent(container, request.outcome.eventType, LocalDateTime.now(), username, eventDate = date, fromPrisonId = container.prisonId),
    )
    return container.removeWith(request.outcome, date, before)
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
      val before = ContainerState.of(source)
      source.events.add(
        PropertyEvent(source, PropertyEventType.COMBINED, now, username, eventDate = today, relatedContainerId = saved.id, relatedContainerSealNumber = saved.currentSealNumber),
      )
      source.removalOutcome = RemovalOutcome.COMBINED
      source.removalDate = today
      source.refreshDerivedState()
      events += PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, source.id!!, source.prisonerNumber, source.changedFieldsSince(before))
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

    val before = ContainerState.of(container)
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
    val event = PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, container.prisonerNumber, container.changedFieldsSince(before))
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
    return repository.findByPrisonerNumber(prisonerNumber)
      .filter { !it.isRemoved() && it.prisonId != newPrisonId && !it.isAlreadyDueForTransferOut(newPrisonId) }
      .map { container ->
        val before = ContainerState.of(container)
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
        PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, prisonerNumber, container.changedFieldsSince(before))
      }
  }

  private fun PropertyContainer.isAlreadyDueForTransferOut(newPrisonId: String): Boolean = currentStatus() == ContainerStatus.DUE_FOR_TRANSFER_OUT &&
    events.maxByOrNull { it.eventDateTime }?.toPrisonId == newPrisonId

  /**
   * Handle a NOMIS prisoner-number merge: everything held under [removedPrisonerNumber] moves to
   * [retainedPrisonerNumber].
   *
   * NOMIS merges two prisoner numbers when the same person is held under both - typically because
   * reception booked a returning prisoner in under a new number. The *oldest* number survives; the newer
   * one is deleted outright and never exists again. Containers are independent per person and nothing
   * constrains a prisoner to one, so both sets simply coexist under the survivor - there is no conflict to
   * resolve. **Removed containers move too**: their history belongs to the person, not to active storage.
   *
   * No [PropertyEvent] is appended, deliberately. Every [PropertyEventType] carries a [ContainerStatus] as
   * a constant of the enum, so there is no way to express "an event whose status is whatever the container
   * already has" - a merge event would overwrite the derived status, silently un-flagging property that is
   * due for return. Filtering a new type out of [PropertyContainer.baseEventStatus] would not be enough
   * either: [PropertyContainer.receivingPrison] and [isAlreadyDueForTransferOut] both read the latest event
   * *unfiltered*, so a merge event would null `receivingPrisonId` (the container vanishes from the
   * receiving prison's incoming list) and break the received handler's idempotency guard. The merge is
   * therefore a column change, and the record of it is the outbound domain event's `removedNomsNumber`.
   *
   * Idempotent for free: a redelivery finds nothing under the removed number and returns an empty list.
   */
  @Transactional
  fun prisonerMerged(retainedPrisonerNumber: String, removedPrisonerNumber: String): List<HmppsDomainEvent> {
    val containers = repository.findByPrisonerNumber(removedPrisonerNumber)
    if (containers.isEmpty()) {
      // The retired number held no property - the common case, and also every redelivery of a merge we
      // have already handled. Silent otherwise, and indistinguishable from the handler never running.
      telemetryClient.trackEvent(
        PropertyTelemetry.MERGE_NO_OP,
        mapOf("NOMS-MERGE-FROM" to removedPrisonerNumber, "NOMS-MERGE-TO" to retainedPrisonerNumber),
        null,
      )
      return emptyList()
    }

    val events = containers.map { container ->
      val before = ContainerState.of(container)
      container.prisonerNumber = retainedPrisonerNumber
      container.refreshDerivedState()
      PropertyContainerEventFactory.changeEvent(
        PropertyDomainEventType.CONTAINER_UPDATED,
        container.id!!,
        retainedPrisonerNumber,
        container.changedFieldsSince(before),
        mapOf("removedNomsNumber" to removedPrisonerNumber),
      )
    }

    log.info("Prisoner merge {} -> {} moved {} container(s)", removedPrisonerNumber, retainedPrisonerNumber, containers.size)
    // A merge is a bulk reassignment driven by NOMIS with no staff member behind it, so the per-container
    // events do not on their own say "a merge happened" - only that a lot of containers changed owner at
    // once. This is the record of the merge itself, and how many containers it moved.
    telemetryClient.trackEvent(
      PropertyTelemetry.MERGE,
      mapOf(
        "NOMS-MERGE-FROM" to removedPrisonerNumber,
        "NOMS-MERGE-TO" to retainedPrisonerNumber,
        "containersMoved" to containers.size.toString(),
      ),
      null,
    )
    return events
  }

  /**
   * Handle a prisoner being released from custody. Every active container the prisoner still has - at any
   * prison - is flagged due for return; see [flagDueForReturn].
   */
  @Transactional
  fun prisonerReleased(prisonerNumber: String): List<HmppsDomainEvent> = flagDueForReturn(prisonerNumber, PropertyEventType.PRISONER_RELEASED)

  /**
   * Handle a prisoner dying in custody. Handled like a release - the property is still due for return -
   * but recorded with a distinct [PropertyEventType.DIED_IN_CUSTODY] event so the history reads correctly.
   */
  @Transactional
  fun prisonerDied(prisonerNumber: String): List<HmppsDomainEvent> = flagDueForReturn(prisonerNumber, PropertyEventType.DIED_IN_CUSTODY)

  /**
   * Flag every active container the prisoner still has - at any prison - as due for return by appending
   * [eventType]; the container stays where it is, only its derived status and history change. Idempotent:
   * containers already due for return are skipped, so the delayed and potentially duplicated release
   * events are safe no-ops. Returns one [PropertyDomainEventType.CONTAINER_UPDATED] event per container
   * changed, to publish after commit.
   */
  private fun flagDueForReturn(prisonerNumber: String, eventType: PropertyEventType): List<HmppsDomainEvent> {
    val now = LocalDateTime.now()
    return repository.findByPrisonerNumber(prisonerNumber)
      .filter { !it.isRemoved() && it.baseStatus() != ContainerStatus.DUE_FOR_RETURN }
      .map { container ->
        val before = ContainerState.of(container)
        container.events.add(
          PropertyEvent(container, eventType, now, SYSTEM_USER, fromPrisonId = container.prisonId),
        )
        container.refreshDerivedState()
        PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, container.id!!, prisonerNumber, container.changedFieldsSince(before))
      }
  }

  private fun loadActive(id: UUID): PropertyContainer {
    val container = repository.findById(id).orElseThrow { PropertyContainerNotFoundException(id) }
    container.removalOutcome?.let { throw ContainerAlreadyRemovedException(id, it) }
    return container
  }

  /**
   * Transfer a container out to the prisoner's new establishment (two-record model). The container is
   * marked removed with outcome [RemovalOutcome.TRANSFERRED] at the sending prison - its prison is NOT
   * reassigned - so it leaves the sending list and shows in the person's returned/transferred history.
   * The receiving prison creates the destination record when it logs the arrival (add + seal-match, see
   * [create]), which reconciles this one by linking it. Until then the TRANSFERRED event carries no
   * related container, so this container still surfaces as awaiting at [toPrisonId] (see
   * [PropertyContainer.receivingPrison]).
   */
  private fun PropertyContainer.transferTo(toPrisonId: String, username: String, date: LocalDate): WriteResult {
    val before = ContainerState.of(this)
    events.add(
      PropertyEvent(this, PropertyEventType.TRANSFERRED, LocalDateTime.now(), username, eventDate = date, fromPrisonId = prisonId, toPrisonId = toPrisonId),
    )
    removalOutcome = RemovalOutcome.TRANSFERRED
    removalDate = date
    refreshDerivedState()
    val event = PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, id!!, prisonerNumber, changedFieldsSince(before))
    return WriteResult(PropertyContainerDto.from(this), event)
  }

  /**
   * [before] must be snapshotted by the caller *before* it appends its removal event: appending alone
   * already moves the derived status (it is read off the latest event), so a snapshot taken here would
   * miss the status change and report only the outcome - the under-reporting this diff exists to prevent.
   */
  private fun PropertyContainer.removeWith(outcome: RemovalOutcome, date: LocalDate, before: ContainerState): WriteResult {
    removalOutcome = outcome
    removalDate = date
    refreshDerivedState()
    val event = PropertyContainerEventFactory.changeEvent(PropertyDomainEventType.CONTAINER_UPDATED, id!!, prisonerNumber, changedFieldsSince(before))
    return WriteResult(PropertyContainerDto.from(this), event)
  }

  private companion object {
    /** Event user id recorded for changes driven by an external domain event rather than a member of staff. */
    private const val SYSTEM_USER = "PRISONER_PROPERTY_API"
    private val log = LoggerFactory.getLogger(PropertyContainerWriteService::class.java)
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
