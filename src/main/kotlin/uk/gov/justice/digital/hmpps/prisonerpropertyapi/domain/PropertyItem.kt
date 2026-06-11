package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

enum class PropertyStatus { HELD, RETURNED, DISPOSED }

@Entity
@Table(name = "property_item")
class PropertyItem(
  @Id
  val id: UUID = UUID.randomUUID(),

  @Column(name = "prisoner_number", nullable = false)
  val prisonerNumber: String,

  @Column(nullable = false)
  var description: String,

  @Column(nullable = false)
  var location: String,

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  var status: PropertyStatus = PropertyStatus.HELD,

  @Column(name = "created_at", nullable = false)
  val createdAt: LocalDateTime = LocalDateTime.now(),

  @Column(name = "updated_at", nullable = false)
  var updatedAt: LocalDateTime = LocalDateTime.now(),
)
