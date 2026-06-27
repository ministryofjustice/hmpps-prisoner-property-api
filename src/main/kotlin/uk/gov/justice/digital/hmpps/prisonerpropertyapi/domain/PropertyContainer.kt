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
  @Column(name = "prisoner_number", nullable = false)
  val prisonerNumber: String,

  @Column(name = "prison_id", nullable = false)
  val prisonId: String,

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
   * Whether the container is archived (NOMIS ACTIVE_FLAG = 'N'). Archived containers are retained but
   * hidden from normal reads, surfaced only when fetched explicitly by id (e.g. a future archive screen).
   */
  @Column(name = "archived", nullable = false)
  var archived: Boolean = false,

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
   * The current status. A removal outcome takes precedence over the latest event so that a later
   * correction (e.g. a seal fix) does not "un-remove" the container; otherwise a proposed disposal
   * date shows DISPOSAL_REQUIRED, else it derives from the most recent event (defaulting to STORED
   * before any event).
   */
  fun currentStatus(): ContainerStatus = when {
    removalOutcome != null -> removalOutcome!!.status
    proposedDisposalDate != null -> ContainerStatus.DISPOSAL_REQUIRED
    else -> latestEvent()?.eventType?.status ?: ContainerStatus.STORED
  }

  /**
   * The current internal location id, from the most recent location-bearing event. Null when the
   * container is offsite at Branston (no internal id), has no recorded location, or has been removed
   * (its location history is retained on the events).
   */
  fun currentLocation(): UUID? = if (isRemoved()) null else latestLocationEvent()?.toInternalLocationId

  /**
   * The current storage location type (internal prison location vs the offsite Branston warehouse),
   * from the most recent location-bearing event. Null when there is no recorded location or the
   * container has been removed. Falls back to [StorageLocationType.INTERNAL] for older events that
   * recorded an internal location id without an explicit type.
   */
  fun currentLocationType(): StorageLocationType? = if (isRemoved()) {
    null
  } else {
    latestLocationEvent()?.let {
      it.toStorageLocationType ?: it.toInternalLocationId?.let { StorageLocationType.INTERNAL }
    }
  }

  /**
   * Refresh the denormalised [currentStatusValue]/[currentInternalLocationId]/[currentStorageLocationType]
   * mirrors from the authoritative event-derived state. Must be called by every write path after appending
   * events or changing the removal outcome, so the establishment-wide list query stays in step.
   */
  fun refreshDerivedState() {
    currentStatusValue = currentStatus()
    currentInternalLocationId = currentLocation()
    currentStorageLocationType = currentLocationType()
  }

  private fun latestEvent(): PropertyEvent? = events.maxByOrNull { it.eventDateTime }

  private fun latestLocationEvent(): PropertyEvent? = events
    .filter { it.toInternalLocationId != null || it.toStorageLocationType != null }
    .maxByOrNull { it.eventDateTime }
}
