package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.helper.GeneratedUuidV7
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** A single thing that happened to a [PropertyContainer] - see [PropertyEventType]. */
@Entity
@Table(name = "property_event")
class PropertyEvent(
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "property_container_id", nullable = false)
  val container: PropertyContainer,

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false)
  val eventType: PropertyEventType,

  @Column(name = "event_datetime", nullable = false)
  val eventDateTime: LocalDateTime,

  @Column(name = "event_user_id", nullable = false)
  val eventUserId: String,

  @Column(name = "seal_number")
  val sealNumber: String? = null,

  @Column(name = "from_internal_location_id")
  val fromInternalLocationId: UUID? = null,

  @Column(name = "to_internal_location_id")
  val toInternalLocationId: UUID? = null,

  @Enumerated(EnumType.STRING)
  @Column(name = "to_storage_location_type")
  val toStorageLocationType: StorageLocationType? = null,

  @Column(name = "from_prison_id")
  val fromPrisonId: String? = null,

  @Column(name = "to_prison_id")
  val toPrisonId: String? = null,

  @Column(name = "event_date")
  val eventDate: LocalDate? = null,

  @Column(name = "related_container_id")
  val relatedContainerId: UUID? = null,

  // The seal number of the related (combined-into) container as at this event, snapshotted so the history
  // names it by the seal it had at combine time rather than any later reseal.
  @Column(name = "related_container_seal_number")
  val relatedContainerSealNumber: String? = null,

  // Snapshot of the container's type at the moment of the event, so the history stays a self-contained,
  // audit-durable record. Defaulted from the container (Kotlin allows a default to reference an earlier
  // param), so every call site captures the type automatically; for a type change the container's type is
  // already updated before the event is appended, so the event records the new type.
  @Enumerated(EnumType.STRING)
  @Column(name = "container_type", nullable = false)
  val containerType: ContainerType = container.containerType,

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  open var id: UUID? = null,
)
