package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * A sealed container of a prisoner's property. Its current seal number, status and location are
 * derived from the most recent relevant [PropertyEvent] and are not persisted.
 */
@Entity
@Table(name = "property_container")
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

  @OneToMany(mappedBy = "container", cascade = [CascadeType.ALL], orphanRemoval = true)
  val events: MutableList<PropertyEvent> = mutableListOf(),

  @Id
  val id: UUID = UUID.randomUUID(),
) {
  /** The most recent seal number, from the latest seal-bearing event. */
  fun currentSealNumber(): String? = events
    .filter { it.eventType.carriesSeal }
    .maxByOrNull { it.eventDateTime }
    ?.sealNumber

  /** The current status, derived from the latest event (defaults to STORED before any event). */
  fun currentStatus(): ContainerStatus = latestEvent()?.eventType?.status ?: ContainerStatus.STORED

  /** The current internal location, from the most recent event that moved the container. */
  fun currentLocation(): UUID? = events
    .filter { it.toInternalLocationId != null }
    .maxByOrNull { it.eventDateTime }
    ?.toInternalLocationId

  private fun latestEvent(): PropertyEvent? = events.maxByOrNull { it.eventDateTime }
}
