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

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  open var id: UUID? = null,
)
