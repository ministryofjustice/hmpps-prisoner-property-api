package uk.gov.justice.digital.hmpps.prisonerpropertyapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate

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

    /**
     * Only the fields the establishment views need - name, current establishment, movement, and the
     * confirmed release date that decides whether stored property is surfaced as due for return ahead of
     * release (the same rule the person view applies, so the two agree).
     */
    private val LIST_RESPONSE_FIELDS = listOf(
      "prisonerNumber",
      "firstName",
      "lastName",
      "prisonId",
      "lastMovementTypeCode",
      "confirmedReleaseDate",
    ).joinToString(",")

    /**
     * How many of a prison's population to ask for in one go. Comfortably above the largest establishment
     * (~2,100) and well under the 10,000 result window the search index enforces on offset + size, so the
     * whole roll arrives in a single request. The endpoint documents paging as unreliable - it sorts by
     * relevance score, which differs between shards - so one large page is the intended usage rather than a
     * shortcut. There is no server-side cap on this endpoint's page size.
     */
    private const val PRISON_ROLL_PAGE_SIZE = 5000
  }

  /**
   * The prisoner numbers of everyone currently at [prisonId], plus the true population size so the caller can
   * tell whether the roll was complete. Returns null when the roll could not be fetched at all - distinct
   * from an empty roll, which means the prison genuinely holds nobody.
   *
   * Used to find property that belongs to someone here but is still held at the prison they came from. Only
   * the prisoner number is requested; nothing else is needed and the endpoint warns about response size.
   */
  fun getPrisonRoll(prisonId: String): PrisonRoll? {
    log.debug("Looking up the roll for {}", prisonId)
    return try {
      val page = prisonerSearchWebClient
        .get()
        .uri { builder ->
          builder.path("/prisoner-search/prison/{prisonId}")
            // Nothing forces prisonerNumber into the response - a field left out here comes back null.
            .queryParam("responseFields", "prisonerNumber")
            .queryParam("size", PRISON_ROLL_PAGE_SIZE)
            .queryParam("page", 0)
            .build(prisonId)
        }
        // The endpoint declares consumes=application/json even though this is a GET with no body, so without
        // this header the request is treated as octet-stream and rejected with 415.
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .retrieve()
        // Deserialised into our own shape: the response is a Spring Page, which Jackson cannot construct.
        .bodyToMono<PrisonRollPage>()
        .block()
        ?: return null
      PrisonRoll(
        prisonerNumbers = page.content.mapNotNull { it.prisonerNumber }.toSet(),
        totalAtPrison = page.totalElements,
      )
    } catch (ex: WebClientResponseException) {
      log.warn("Prison roll lookup for {} failed ({}), so property owners there cannot be resolved", prisonId, ex.statusCode)
      null
    }
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

/**
 * Everyone currently at a prison. [totalAtPrison] is the population the search index reports, which is larger
 * than [prisonerNumbers] only if the roll was truncated by the page size.
 */
data class PrisonRoll(
  val prisonerNumbers: Set<String>,
  val totalAtPrison: Long,
) {
  /** Whether the roll came back short, so some of the prison's population is missing from it. */
  fun isTruncated(): Boolean = prisonerNumbers.size < totalAtPrison
}

/**
 * The parts of prisoner-search's paged response we use. It returns a Spring `Page`, which cannot be
 * deserialised directly (`PageImpl` has no constructor Jackson can call), so this maps the two fields that
 * matter and ignores the rest of the paging envelope.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class PrisonRollPage(
  val content: List<PrisonerNumberOnly> = emptyList(),
  val totalElements: Long = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PrisonerNumberOnly(val prisonerNumber: String?)

data class Prisoner(
  val prisonerNumber: String,
  val firstName: String?,
  val lastName: String?,
  val prisonId: String?,
  val prisonName: String?,
  val cellLocation: String?,
  // The prisoner's last movement type. TRN when in transit (with prisonId TRN), REL when released (prisonId OUT).
  val lastMovementTypeCode: String?,
  // Planned release dates from the sentence. Only the confirmed date (set by the establishment) drives the
  // "due for return before release" rule - the conditional (sentence-calculated) date can move, so it is
  // deliberately not used. Requested on both the single-prisoner lookup and the trimmed bulk list.
  val confirmedReleaseDate: LocalDate? = null,
  val conditionalReleaseDate: LocalDate? = null,
)
