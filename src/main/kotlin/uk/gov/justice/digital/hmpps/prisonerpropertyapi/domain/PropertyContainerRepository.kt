package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PropertyContainerRepository : JpaRepository<PropertyContainer, UUID> {
  fun findByPrisonerNumber(prisonerNumber: String): List<PropertyContainer>
}
