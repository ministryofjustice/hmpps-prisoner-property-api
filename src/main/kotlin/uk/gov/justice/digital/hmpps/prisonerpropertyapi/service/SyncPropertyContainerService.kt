package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncMappingType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerResponse
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyDomainEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.sync.NomisContainerTransformer
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

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
      disposedDate = request.expiryDate,
    )
    val disposalTime = request.modifyDateTime ?: request.createDateTime

    val location = transformer.resolveLocation(request)
    container.events.add(
      PropertyEvent(
        container = container,
        eventType = PropertyEventType.CREATED_SEALED,
        eventDateTime = request.createDateTime,
        eventUserId = request.createUsername,
        sealNumber = transformer.resolveSeal(request.nomisPropertyContainerId, request.sealMark),
        toInternalLocationId = location?.internalLocationId,
        toStorageLocationType = location?.type,
        toPrisonId = request.prisonId,
      ),
    )
    if (request.expiryDate == null && request.proposedDisposalDate != null) {
      container.events.add(disposalRequiredEvent(container, request.proposedDisposalDate, disposalTime, request.createUsername))
    }
    if (request.expiryDate != null) {
      container.events.add(disposedEvent(container, request.expiryDate, disposalTime, request.createUsername))
    }

    val saved = repository.save(container)
    // Migration is a one-off bulk load; downstream systems only care about subsequent changes, so no event is raised.
    val event = if (migrating) {
      null
    } else {
      buildEvent(PropertyDomainEventType.CONTAINER_CREATED, saved.id!!, request.nomisPropertyContainerId, request.prisonerNumber, changedFields = null)
    }
    return SyncResult(SyncPropertyContainerResponse(saved.id!!, request.nomisPropertyContainerId, SyncMappingType.CREATED), event)
  }

  private fun update(existing: PropertyContainer, request: SyncPropertyContainerRequest, migrating: Boolean): SyncResult {
    val now = request.modifyDateTime ?: LocalDateTime.now()
    val user = request.modifyUsername ?: request.createUsername
    val changed = mutableListOf<String>()

    val incomingSeal = transformer.resolveSeal(request.nomisPropertyContainerId, request.sealMark)
    if (incomingSeal != existing.currentSealNumber()) {
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

    val incomingLocation = transformer.resolveLocation(request)
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
      if (request.proposedDisposalDate != null && request.expiryDate == null) {
        existing.events.add(disposalRequiredEvent(existing, request.proposedDisposalDate, now, user))
      }
      changed += "proposedDisposalDate"
    }

    if (request.expiryDate != existing.disposedDate) {
      existing.disposedDate = request.expiryDate
      if (request.expiryDate != null) {
        existing.events.add(disposedEvent(existing, request.expiryDate, now, user))
      }
      changed += "disposedDate"
    }

    var event: HmppsDomainEvent? = null
    if (changed.isNotEmpty()) {
      repository.save(existing)
      if (!migrating) {
        event = buildEvent(PropertyDomainEventType.CONTAINER_UPDATED, existing.id!!, request.nomisPropertyContainerId, request.prisonerNumber, changed)
      }
    }
    return SyncResult(SyncPropertyContainerResponse(existing.id!!, request.nomisPropertyContainerId, SyncMappingType.UPDATED), event)
  }

  private fun disposalRequiredEvent(container: PropertyContainer, date: LocalDate, time: LocalDateTime, user: String) = PropertyEvent(container, PropertyEventType.DISPOSAL_REQUIRED, time, user, eventDate = date)

  private fun disposedEvent(container: PropertyContainer, date: LocalDate, time: LocalDateTime, user: String) = PropertyEvent(container, PropertyEventType.DISPOSED, time, user, eventDate = date)

  private fun buildEvent(eventType: PropertyDomainEventType, dpsId: UUID, nomisId: Long, prisonerNumber: String, changedFields: List<String>?): HmppsDomainEvent = HmppsDomainEvent(
    eventType = eventType.value,
    description = "A prisoner property container was synchronised from NOMIS",
    prisonerNumber = prisonerNumber,
    additionalInformation = buildMap {
      put("dpsId", dpsId.toString())
      put("nomisPropertyContainerId", nomisId)
      changedFields?.let { put("changedFields", it) }
    },
  )
}

/**
 * The outcome of a sync/migrate: the API [response] plus the domain [event] to publish *after* the
 * transaction commits (null when there is nothing to publish - a migration or an unchanged snapshot).
 */
data class SyncResult(val response: SyncPropertyContainerResponse, val event: HmppsDomainEvent?)
