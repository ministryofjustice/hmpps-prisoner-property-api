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

    /** The batch endpoint accepts at most this many prisoner numbers per request. */
    private const val BATCH_SIZE = 1000

    /** Only the fields the establishment list needs - name, current establishment and movement. */
    private val LIST_RESPONSE_FIELDS = listOf("prisonerNumber", "firstName", "lastName", "prisonId", "lastMovementTypeCode").joinToString(",")
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

  /**
   * Look up several prisoners at once by prisoner number, keyed by prisoner number. Numbers are
   * de-duplicated and looked up in chunks (the batch endpoint caps the request size, and the caller may
   * pass a whole prison's worth for the person-location filter); numbers that do not resolve are simply
   * absent from the result. Only the fields the list needs are requested (responseFields) to keep the
   * payload small. Degrades gracefully per chunk: a failed chunk contributes no prisoners rather than
   * failing the whole read.
   */
  fun getPrisoners(prisonerNumbers: Collection<String>): Map<String, Prisoner> = prisonerNumbers.distinct()
    .chunked(BATCH_SIZE)
    .flatMap { chunk -> fetchChunk(chunk) }
    .associateBy { it.prisonerNumber }

  private fun fetchChunk(chunk: List<String>): List<Prisoner> = try {
    prisonerSearchWebClient
      .post()
      .uri { builder -> builder.path("/prisoner-search/prisoner-numbers").queryParam("responseFields", LIST_RESPONSE_FIELDS).build() }
      .bodyValue(PrisonerNumbers(chunk))
      .retrieve()
      .bodyToMono<List<Prisoner>>()
      .block()
      ?: emptyList()
  } catch (ex: WebClientResponseException) {
    log.warn("Bulk prisoner lookup failed ({}), returning no prisoner details for the chunk", ex.statusCode)
    emptyList()
  }
}

data class PrisonerNumbers(val prisonerNumbers: List<String>)

data class Prisoner(
  val prisonerNumber: String,
  val firstName: String?,
  val lastName: String?,
  val prisonId: String?,
  val prisonName: String?,
  val cellLocation: String?,
  // The prisoner's last movement type. TRN when in transit (with prisonId TRN), REL when released (prisonId OUT).
  val lastMovementTypeCode: String?,
)
