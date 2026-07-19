package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncMappingType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerResponse
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyContainerEventFactory
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyDomainEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.sync.NomisContainerTransformer
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Ingests NOMIS property container snapshots, applying the business transform and translating each
 * snapshot into the event-sourced domain. Re-syncing an unchanged snapshot is a no-op.
 *
 * The service is the transaction boundary; it *builds* the domain event to raise (if any) and returns
 * it in a [SyncResult]. The resource publishes it after the transaction commits, so a subscriber can
 * never read back before the change is visible.
 */
@Service
class SyncPropertyContainerService(
  private val repository: PropertyContainerRepository,
  private val transformer: NomisContainerTransformer,
) {

  /** Ongoing event-driven sync of a single NOMIS change. */
  @Transactional
  fun sync(request: SyncPropertyContainerRequest): SyncResult = upsert(request, migrating = false)

  /** Initial bulk migration of a NOMIS container. */
  @Transactional
  fun migrate(request: SyncPropertyContainerRequest): SyncResult = upsert(request, migrating = true)

  private fun upsert(request: SyncPropertyContainerRequest, migrating: Boolean): SyncResult {
    val existing = request.dpsId?.let {
      repository.findById(it).orElseThrow { PropertyContainerNotFoundException(it) }
    }
    return if (existing == null) create(request, migrating) else update(existing, request, migrating)
  }

  private fun create(request: SyncPropertyContainerRequest, migrating: Boolean): SyncResult {
    val container = PropertyContainer(
      prisonerNumber = request.prisonerNumber,
      prisonId = request.prisonId,
      containerType = transformer.mapType(request.containerCode),
      createdByUserId = request.createUsername,
      createDateTime = request.createDateTime,
      proposedDisposalDate = request.proposedDisposalDate,
    )
    val disposalTime = request.modifyDateTime ?: request.createDateTime
    // NOMIS ACTIVE_FLAG='N' means the container has left the establishment, reason unknown: model it as REMOVED
    // (a reversible removal). The removal date is EXPIRY_DATE when set, but that is often absent on inactive
    // NOMIS rows, so fall back to the modify time (which matches EXPIRY_DATE on rows that carry both). An active
    // container is never removed, even if NOMIS carries a (possibly future) EXPIRY_DATE - that only drives the
    // disposal-due date below.
    val removalDate = request.expiryDate ?: disposalTime.toLocalDate()
    if (!request.active) {
      container.removalOutcome = RemovalOutcome.REMOVED
      container.removalDate = removalDate
    }

    // A removed container has left the establishment, so ignore any (often stale) NOMIS location: it must not
    // occupy a storage slot, and a later reactivation is re-allocated a fresh location rather than silently
    // re-deriving this one.
    val location = if (request.active) transformer.resolveLocation(request) else null
    val seal = transformer.resolveSeal(request.nomisPropertyContainerId, request.sealMark)
    container.currentSealNumber = seal
    container.events.add(
      PropertyEvent(
        container = container,
        eventType = PropertyEventType.CREATED_SEALED,
        eventDateTime = request.createDateTime,
        eventUserId = request.createUsername,
        sealNumber = seal,
        toInternalLocationId = location?.internalLocationId,
        toStorageLocationType = location?.type,
        toPrisonId = request.prisonId,
      ),
    )
    if (request.active && request.proposedDisposalDate != null) {
      container.events.add(disposalRequiredEvent(container, request.proposedDisposalDate, disposalTime, request.createUsername))
    }
    if (!request.active) {
      container.events.add(removedEvent(container, removalDate, disposalTime, request.createUsername))
    }

    container.refreshDerivedState()
    val saved = repository.save(container)
    // Migration is a one-off bulk load; downstream systems only care about subsequent changes, so no event is raised.
    val event = if (migrating) {
      null
    } else {
      PropertyContainerEventFactory.syncEvent(PropertyDomainEventType.CONTAINER_CREATED, saved.id!!, request.nomisPropertyContainerId, request.prisonerNumber, changedFields = null)
    }
    return SyncResult(SyncPropertyContainerResponse(saved.id!!, request.nomisPropertyContainerId, SyncMappingType.CREATED), event)
  }

  private fun update(existing: PropertyContainer, request: SyncPropertyContainerRequest, migrating: Boolean): SyncResult {
    val now = request.modifyDateTime ?: LocalDateTime.now()
    val user = request.modifyUsername ?: request.createUsername
    val changed = mutableListOf<String>()

    val incomingSeal = transformer.resolveSeal(request.nomisPropertyContainerId, request.sealMark)
    if (incomingSeal != existing.currentSealNumber) {
      existing.currentSealNumber = incomingSeal
      existing.events.add(
        PropertyEvent(existing, PropertyEventType.SEAL_CHANGED, now, user, sealNumber = incomingSeal),
      )
      changed += "sealNumber"
    }

    val incomingType = transformer.mapType(request.containerCode)
    if (incomingType != existing.containerType) {
      existing.containerType = incomingType
      existing.events.add(PropertyEvent(existing, PropertyEventType.CONTAINER_TYPE_CHANGE, now, user))
      changed += "containerType"
    }

    // Ignore the NOMIS location while the container is inactive (removed) - it must not occupy a storage slot.
    // On reactivation the snapshot is active again, so the new location is applied here before the removal is
    // cleared below.
    val incomingLocation = if (request.active) transformer.resolveLocation(request) else null
    if (incomingLocation != null &&
      (incomingLocation.type != existing.currentLocationType() || incomingLocation.internalLocationId != existing.currentLocation())
    ) {
      existing.events.add(
        PropertyEvent(
          existing,
          PropertyEventType.MOVED,
          now,
          user,
          fromInternalLocationId = existing.currentLocation(),
          toInternalLocationId = incomingLocation.internalLocationId,
          toStorageLocationType = incomingLocation.type,
        ),
      )
      changed += "location"
    }

    if (request.proposedDisposalDate != existing.proposedDisposalDate) {
      existing.proposedDisposalDate = request.proposedDisposalDate
      if (request.active && request.proposedDisposalDate != null) {
        existing.events.add(disposalRequiredEvent(existing, request.proposedDisposalDate, now, user))
      }
      changed += "proposedDisposalDate"
    }

    // Removal follows NOMIS ACTIVE_FLAG: inactive -> removed (dated by EXPIRY_DATE, or the modify time when
    // EXPIRY_DATE is absent); reactivation clears the removal and records a REACTIVATED event so the history
    // shows the container coming back to life (its location comes from the MOVED event NOMIS sends alongside
    // reactivation, or the last location event if it had one before removal).
    val resolvedRemovalDate = request.expiryDate ?: now.toLocalDate()
    val wasRemoved = existing.removalOutcome == RemovalOutcome.REMOVED
    if (!request.active) {
      if (!wasRemoved || existing.removalDate != resolvedRemovalDate) {
        if (!wasRemoved) {
          existing.events.add(removedEvent(existing, resolvedRemovalDate, now, user))
        }
        existing.removalOutcome = RemovalOutcome.REMOVED
        existing.removalDate = resolvedRemovalDate
        changed += "removalOutcome"
      }
    } else if (wasRemoved) {
      existing.removalOutcome = null
      existing.removalDate = null
      existing.events.add(PropertyEvent(existing, PropertyEventType.REACTIVATED, now, user))
      changed += "removalOutcome"
    }

    var event: HmppsDomainEvent? = null
    if (changed.isNotEmpty()) {
      existing.refreshDerivedState()
      repository.save(existing)
      if (!migrating) {
        event = PropertyContainerEventFactory.syncEvent(PropertyDomainEventType.CONTAINER_UPDATED, existing.id!!, request.nomisPropertyContainerId, request.prisonerNumber, changed)
      }
    }
    return SyncResult(SyncPropertyContainerResponse(existing.id!!, request.nomisPropertyContainerId, SyncMappingType.UPDATED), event)
  }

  private fun disposalRequiredEvent(container: PropertyContainer, date: LocalDate, time: LocalDateTime, user: String) = PropertyEvent(container, PropertyEventType.DISPOSAL_REQUIRED, time, user, eventDate = date)

  private fun removedEvent(container: PropertyContainer, date: LocalDate?, time: LocalDateTime, user: String) = PropertyEvent(container, PropertyEventType.REMOVED, time, user, eventDate = date)
}

/**
 * The outcome of a sync/migrate: the API [response] plus the domain [event] to publish *after* the
 * transaction commits (null when there is nothing to publish - a migration or an unchanged snapshot).
 */
data class SyncResult(val response: SyncPropertyContainerResponse, val event: HmppsDomainEvent?)
