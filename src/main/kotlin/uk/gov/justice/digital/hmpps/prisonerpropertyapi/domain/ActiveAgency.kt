package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * An agency (prison) the property service has been switched on for. A row is kept even when
 * deactivated (with [active] = false) so the toggle is idempotent and the last change is auditable.
 */
@Entity
@Table(name = "active_agency")
class ActiveAgency(
  @Id
  @Column(name = "agency_id", nullable = false)
  val agencyId: String,

  @Column(name = "active", nullable = false)
  var active: Boolean,

  @Column(name = "updated_at", nullable = false)
  var updatedAt: LocalDateTime,

  @Column(name = "updated_by", nullable = false)
  var updatedBy: String,
)
