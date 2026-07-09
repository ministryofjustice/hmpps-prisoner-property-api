package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.CacheConfiguration.Companion.ACTIVE_AGENCIES_CACHE_NAME
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgency
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgencyRepository
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull

/**
 * The set of agencies (prisons) the property service is switched on for. The active list is cached
 * because it is read on every `/info` poll; writes evict the local cache and a scheduled evict in
 * [uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.CacheConfiguration] keeps other pods in step.
 */
@Service
class ActiveAgenciesService(
  private val repository: ActiveAgencyRepository,
) {

  @Cacheable(ACTIVE_AGENCIES_CACHE_NAME)
  fun getActiveAgencies(): List<String> = repository.findAllByActiveTrue().map { it.agencyId }.sorted()

  fun isActive(agencyId: String): Boolean = repository.findById(agencyId).getOrNull()?.active == true

  @Transactional
  @CacheEvict(value = [ACTIVE_AGENCIES_CACHE_NAME], allEntries = true)
  fun setActive(agencyId: String, active: Boolean, username: String): ActiveAgency {
    val agency = repository.findById(agencyId).getOrNull()
      ?.apply {
        this.active = active
        this.updatedAt = LocalDateTime.now()
        this.updatedBy = username
      }
      ?: ActiveAgency(agencyId = agencyId, active = active, updatedAt = LocalDateTime.now(), updatedBy = username)
    return repository.save(agency)
  }
}
