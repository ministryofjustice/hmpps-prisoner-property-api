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

  @Column(name = "disposed_date")
  var disposedDate: LocalDate? = null,

  @OneToMany(mappedBy = "container", cascade = [CascadeType.ALL], orphanRemoval = true)
  val events: MutableList<PropertyEvent> = mutableListOf(),

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  var id: UUID? = null,
) {
  /** The most recent seal number, from the latest seal-bearing event. */
  fun currentSealNumber(): String? = events
    .filter { it.eventType.carriesSeal }
    .maxByOrNull { it.eventDateTime }
    ?.sealNumber

  /**
   * The current status. A recorded disposal date takes precedence over the latest event so that a
   * later correction (e.g. a seal fix) does not "un-dispose" the container; otherwise it derives
   * from the most recent event (defaulting to STORED before any event).
   */
  fun currentStatus(): ContainerStatus = when {
    disposedDate != null -> ContainerStatus.DISPOSED
    proposedDisposalDate != null -> ContainerStatus.DISPOSAL_REQUIRED
    else -> latestEvent()?.eventType?.status ?: ContainerStatus.STORED
  }

  /**
   * The current internal location id, from the most recent location-bearing event. Null when the
   * container is offsite at Branston (no internal id), has no recorded location, or is disposed (its
   * location history is retained on the events).
   */
  fun currentLocation(): UUID? = if (disposedDate != null) null else latestLocationEvent()?.toInternalLocationId

  /**
   * The current storage location type (internal prison location vs the offsite Branston warehouse),
   * from the most recent location-bearing event. Null when there is no recorded location or the
   * container is disposed. Falls back to [StorageLocationType.INTERNAL] for older events that recorded
   * an internal location id without an explicit type.
   */
  fun currentLocationType(): StorageLocationType? = if (disposedDate != null) {
    null
  } else {
    latestLocationEvent()?.let {
      it.toStorageLocationType ?: it.toInternalLocationId?.let { StorageLocationType.INTERNAL }
    }
  }

  private fun latestEvent(): PropertyEvent? = events.maxByOrNull { it.eventDateTime }

  private fun latestLocationEvent(): PropertyEvent? = events
    .filter { it.toInternalLocationId != null || it.toStorageLocationType != null }
    .maxByOrNull { it.eventDateTime }
}
