package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.NamedAttributeNode
import jakarta.persistence.NamedEntityGraph
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.helper.GeneratedUuidV7
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A sealed container of a prisoner's property. Its current seal number, status and location are
 * derived from the most recent relevant [PropertyEvent] and are not persisted.
 */
@Entity
@Table(name = "property_container")
@NamedEntityGraph(name = "PropertyContainer.withEvents", attributeNodes = [NamedAttributeNode("events")])
class PropertyContainer(
  /**
   * The prisoner this container belongs to. A `var` because a NOMIS prisoner-number merge reassigns it:
   * when two numbers for the same person are merged, the retired one is deleted from NOMIS and everything
   * held against it moves to the survivor. Nothing else ever changes it.
   */
  @Column(name = "prisoner_number", nullable = false)
  var prisonerNumber: String,

  @Column(name = "prison_id", nullable = false)
  var prisonId: String,

  @Enumerated(EnumType.STRING)
  @Column(name = "container_type", nullable = false)
  var containerType: ContainerType,

  @Column(name = "created_by_user_id", nullable = false)
  val createdByUserId: String,

  @Column(name = "create_datetime", nullable = false)
  val createDateTime: LocalDateTime = LocalDateTime.now(),

  @Column(name = "proposed_disposal_date")
  var proposedDisposalDate: LocalDate? = null,

  @Enumerated(EnumType.STRING)
  @Column(name = "removal_outcome")
  var removalOutcome: RemovalOutcome? = null,

  @Column(name = "removal_date")
  var removalDate: LocalDate? = null,

  @Column(name = "current_seal_number")
  var currentSealNumber: String? = null,

  /**
   * Denormalised mirrors of the derived [currentStatus]/[currentLocation]/[currentLocationType], refreshed
   * via [refreshDerivedState] on every write. They exist so the establishment-wide list can filter and
   * paginate without loading each container's events; the derived methods remain authoritative.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "current_status", nullable = false)
  var currentStatusValue: ContainerStatus = ContainerStatus.STORED,

  @Column(name = "current_internal_location_id")
  var currentInternalLocationId: UUID? = null,

  @Enumerated(EnumType.STRING)
  @Column(name = "current_storage_location_type")
  var currentStorageLocationType: StorageLocationType? = null,

  /**
   * Denormalised destination prison for a container that is due to transfer out: the establishment its
   * owner has moved to (and so the prison it is due to be transferred *in* to). Mirrors [receivingPrison];
   * null unless the container is currently due for transfer out. Lets the establishment-wide list surface
   * incoming property (held elsewhere, destined here) without loading events.
   */
  @Column(name = "receiving_prison_id")
  var receivingPrisonId: String? = null,

  @OneToMany(mappedBy = "container", cascade = [CascadeType.ALL], orphanRemoval = true)
  val events: MutableList<PropertyEvent> = mutableListOf(),

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  var id: UUID? = null,
) {
  /** Whether the container has left active storage (disposed, returned, transferred, combined). */
  fun isRemoved(): Boolean = removalOutcome != null

  /**
   * Whether the container has a proposed disposal date that has now arisen (today or earlier) and is
   * still in active storage. Disposal is time-based, so this is derived from [proposedDisposalDate] vs
   * today - never denormalised - and drives the DISPOSAL_REQUIRED overlay wherever status is shown.
   */
  fun isDisposalDue(): Boolean = removalOutcome == null && proposedDisposalDate?.let { !it.isAfter(LocalDate.now()) } == true

  /**
   * The current status. A removal outcome takes precedence over the latest event so that a later
   * correction (e.g. a seal fix) does not "un-remove" the container; otherwise DISPOSAL_REQUIRED once
   * the proposed disposal date has arisen (see [isDisposalDue]), else it derives from the most recent
   * event (defaulting to STORED before any event). A live (non-removed) container never shows TRANSFER;
   * a transfer-out removes the container (outcome TRANSFERRED) and the receiving prison holds a separate record.
   */
  fun currentStatus(): ContainerStatus = when {
    removalOutcome != null -> removalOutcome!!.status
    isDisposalDue() -> ContainerStatus.DISPOSAL_REQUIRED
    else -> baseEventStatus()
  }

  /**
   * The status excluding the time-based disposal overlay - the value denormalised into
   * [currentStatusValue] so the establishment-list column stays time-stable (disposal is re-derived from
   * [proposedDisposalDate] at read time). A removal outcome takes precedence; otherwise the latest
   * non-disposal event's status.
   */
  fun baseStatus(): ContainerStatus = removalOutcome?.status ?: baseEventStatus()

  /**
   * The status from the most recent non-disposal event (disposal is derived from the date, not the event).
   * Events are appended chronologically, so when two share a timestamp - e.g. a REMOVED and the REACTIVATED
   * that reverses it in one sync snapshot - the later-appended one wins.
   */
  private fun baseEventStatus(): ContainerStatus = events
    .filter { it.eventType != PropertyEventType.DISPOSAL_REQUIRED }
    .reduceOrNull { latest, event -> if (event.eventDateTime >= latest.eventDateTime) event else latest }
    ?.eventType?.status?.takeUnless { it == ContainerStatus.TRANSFER } ?: ContainerStatus.STORED

  /**
   * The prison this container is due to be transferred in to (denormalised into [receivingPrisonId] for
   * the establishment list), or null when it is not incoming anywhere. Two cases carry a destination:
   *  - due for transfer out: the owner has moved but the property is still here - the destination is on
   *    the latest event (the PRISONER_RECEIVED that flagged it);
   *  - transferred out but not yet reconciled: the sending prison has marked it TRANSFERRED, but the
   *    receiving prison has not yet logged the arrival (the TRANSFERRED event has no related container) -
   *    the destination is on that TRANSFERRED event, so it still shows as awaiting at the receiving prison.
   * Once reconciled (the TRANSFERRED event gains a related container id) it is null again.
   */
  fun receivingPrison(): String? = when {
    baseStatus() == ContainerStatus.DUE_FOR_TRANSFER_OUT -> events.maxByOrNull { it.eventDateTime }?.toPrisonId
    removalOutcome == RemovalOutcome.TRANSFERRED ->
      latestTransferEvent()?.takeIf { it.relatedContainerId == null }?.toPrisonId
    else -> null
  }

  /** The most recent TRANSFERRED event, used to derive the destination and reconciled state of a transfer. */
  fun latestTransferEvent(): PropertyEvent? = events
    .filter { it.eventType == PropertyEventType.TRANSFERRED }
    .maxByOrNull { it.eventDateTime }

  /**
   * The current internal location id, from the most recent location-affecting event. Null when the
   * container is offsite at Branston (no internal id), has no recorded location, has been removed, or
   * has just been transferred to another prison (a [PropertyEventType.TRANSFERRED] clears the location
   * so the receiving prison assigns its own). Location history is retained on the events.
   */
  fun currentLocation(): UUID? = if (isRemoved()) null else latestLocationEvent()?.takeUnless { it.eventType == PropertyEventType.TRANSFERRED }?.toInternalLocationId

  /**
   * The current storage location type (internal prison location vs the offsite Branston warehouse),
   * from the most recent location-affecting event. Null when there is no recorded location, the
   * container has been removed, or it has just been transferred to another prison. Falls back to
   * [StorageLocationType.INTERNAL] for older events that recorded an internal location id without an
   * explicit type.
   */
  fun currentLocationType(): StorageLocationType? = if (isRemoved()) {
    null
  } else {
    latestLocationEvent()?.takeUnless { it.eventType == PropertyEventType.TRANSFERRED }?.let {
      it.toStorageLocationType ?: it.toInternalLocationId?.let { StorageLocationType.INTERNAL }
    }
  }

  /**
   * Refresh the denormalised [currentStatusValue]/[currentInternalLocationId]/[currentStorageLocationType]
   * mirrors from the authoritative event-derived state. Must be called by every write path after appending
   * events or changing the removal outcome, so the establishment-wide list query stays in step.
   */
  fun refreshDerivedState() {
    currentStatusValue = baseStatus()
    currentInternalLocationId = currentLocation()
    currentStorageLocationType = currentLocationType()
    receivingPrisonId = receivingPrison()
  }

  private fun latestLocationEvent(): PropertyEvent? = events
    .filter { it.affectsLocation() }
    .maxByOrNull { it.eventDateTime }
}
