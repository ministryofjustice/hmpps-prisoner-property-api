package uk.gov.justice.digital.hmpps.prisonerpropertyapi.client

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.config.CacheConfiguration

/**
 * Calls prison-register for the list of prisons. The list is small (~185 records) and rarely changes,
 * so prison id -> name lookups are cached (see [CacheConfiguration.PRISON_NAMES_CACHE_NAME]).
 * The /prisons endpoint is unauthenticated, so this uses the health web client.
 */
@Component
class PrisonRegisterClient(
  @param:Qualifier("prisonRegisterWebClient") private val prisonRegisterWebClient: WebClient,
) {

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  /**
   * All prisons as a map of prison id -> prison name (including closed/inactive establishments, so
   * names always resolve for e.g. a transfer to a now-closed prison). Cached for 24 hours.
   */
  @Cacheable(CacheConfiguration.PRISON_NAMES_CACHE_NAME)
  fun getPrisonNames(): Map<String, String> {
    log.debug("Looking up all prison names")
    return fetchPrisons().associate { it.prisonId to it.prisonName }
  }

  /**
   * The ids of the active (operational) prisons, used to keep closed/non-operational agencies out of
   * the rollout admin list. Cached alongside [getPrisonNames] (distinct key), 24 hours.
   */
  @Cacheable(CacheConfiguration.PRISON_NAMES_CACHE_NAME, key = "'activeIds'")
  fun getActivePrisonIds(): Set<String> {
    log.debug("Looking up active prison ids")
    return fetchPrisons().filter { it.active }.map { it.prisonId }.toSet()
  }

  private fun fetchPrisons(): List<PrisonDto> = prisonRegisterWebClient
    .get()
    .uri("/prisons")
    .retrieve()
    .bodyToMono(object : ParameterizedTypeReference<List<PrisonDto>>() {})
    .block() ?: emptyList()
}

data class PrisonDto(
  val prisonId: String,
  val prisonName: String,
  val active: Boolean,
)
