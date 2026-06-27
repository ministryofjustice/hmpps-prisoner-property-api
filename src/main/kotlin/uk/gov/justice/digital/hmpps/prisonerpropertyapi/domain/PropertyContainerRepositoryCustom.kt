package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * Custom queries backing the establishment-wide property list. The list is paged *by prisoner* (so a
 * prisoner's containers are never split across a page boundary) and filtered on the denormalised
 * derived columns, avoiding any per-container event loading.
 */
interface PropertyContainerRepositoryCustom {

  /** A page of distinct prisoner numbers (ordered) that have at least one container matching [filter]. */
  fun findPrisonerNumbersPage(prisonId: String, filter: PrisonPropertyFilter, pageable: Pageable): Page<String>

  /** The containers matching [filter] for the given prisoners, ordered by prisoner then creation time. */
  fun findContainers(prisonId: String, filter: PrisonPropertyFilter, prisonerNumbers: List<String>): List<PropertyContainer>
}
