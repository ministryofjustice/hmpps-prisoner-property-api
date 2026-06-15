package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface PropertyContainerRepository : JpaRepository<PropertyContainer, UUID> {
  // Fetch the events in the same query when loading a single container - its current seal, status and
  // location are derived from them, so a lazy load would otherwise mean an extra query (N+1).
  @EntityGraph("PropertyContainer.withEvents")
  override fun findById(id: UUID): Optional<PropertyContainer>

  fun findByPrisonerNumber(prisonerNumber: String): List<PropertyContainer>
  fun findByPrisonId(prisonId: String): List<PropertyContainer>
}
