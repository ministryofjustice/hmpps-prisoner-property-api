package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PropertyItemRepository : JpaRepository<PropertyItem, UUID> {
  fun findByPrisonerNumberOrderByCreatedAtDesc(prisonerNumber: String): List<PropertyItem>
}
