package uk.gov.justice.digital.hmpps.prisonerpropertyapi.client

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Calls prisoner-search to look up a prisoner's name and current location.
 */
@Component
class PrisonerSearchClient(
  @param:Qualifier("prisonerSearchWebClient") private val prisonerSearchWebClient: WebClient,
) {

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  /**
   * Look up a single prisoner by prisoner (NOMS) number. Returns null if not found.
   */
  fun getPrisoner(prisonerNumber: String): Prisoner? {
    log.debug("Looking up prisoner {}", prisonerNumber)
    try {
      return prisonerSearchWebClient
        .get()
        .uri("/prisoner/{prisonerNumber}", prisonerNumber)
        .retrieve()
        .bodyToMono<Prisoner>()
        .block()
    } catch (ex: WebClientResponseException) {
      if (ex.statusCode == HttpStatus.NOT_FOUND) {
        return null
      }
      throw ex
    }
  }
}

data class Prisoner(
  val prisonerNumber: String,
  val firstName: String?,
  val lastName: String?,
  val prisonId: String?,
  val prisonName: String?,
  val cellLocation: String?,
)
