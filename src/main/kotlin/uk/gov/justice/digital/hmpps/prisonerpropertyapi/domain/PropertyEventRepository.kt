package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PropertyEventRepository : JpaRepository<PropertyEvent, UUID> {
  fun findByContainerIdOrderByEventDateTimeDesc(containerId: UUID): List<PropertyEvent>
}
