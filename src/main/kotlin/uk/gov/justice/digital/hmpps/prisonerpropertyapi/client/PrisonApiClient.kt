package uk.gov.justice.digital.hmpps.prisonerpropertyapi.client

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDateTime

/**
 * Calls prison-api for a prisoner's movement history, used to synthesise the "admitted / transferred in"
 * timeline items at read time. The prison-timeline endpoint requires the VIEW_PRISONER_DATA role on the
 * system client; until that is granted it returns 403, which is treated the same as any other failure -
 * the caller degrades to no movement items rather than failing the timeline.
 */
@Component
class PrisonApiClient(
  @param:Qualifier("prisonApiWebClient") private val prisonApiWebClient: WebClient,
) {

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  /**
   * The prisoner's prison-timeline (admissions, releases and transfers, per booking). Returns null when the
   * prisoner is unknown (404) and degrades gracefully on any other error (e.g. a 403 before the
   * VIEW_PRISONER_DATA role is granted, or prison-api being unavailable): logs a warning and returns null so
   * the property timeline still renders without the movement items.
   */
  fun getPrisonTimeline(prisonerNumber: String): PrisonerInPrisonSummary? = try {
    prisonApiWebClient
      .get()
      .uri("/api/offenders/{prisonerNumber}/prison-timeline", prisonerNumber)
      .retrieve()
      .bodyToMono<PrisonerInPrisonSummary>()
      .block()
  } catch (ex: WebClientResponseException) {
    if (ex.statusCode == HttpStatus.NOT_FOUND) {
      null
    } else {
      log.warn("prison-api prison-timeline lookup failed ({}), omitting movement items", ex.statusCode)
      null
    }
  }
}

/** The subset of prison-api's PrisonerInPrisonSummary we consume. Unmapped fields are ignored. */
data class PrisonerInPrisonSummary(
  val prisonerNumber: String,
  val prisonPeriod: List<PrisonPeriod> = emptyList(),
)

data class PrisonPeriod(
  val movementDates: List<SignificantMovement> = emptyList(),
  val transfers: List<TransferDetail> = emptyList(),
)

data class SignificantMovement(
  // The movement type into prison: ADM (admission) or TAP (return from temporary absence).
  val inwardType: String?,
  val dateInToPrison: LocalDateTime?,
  val admittedIntoPrisonId: String?,
)

data class TransferDetail(
  val dateInToPrison: LocalDateTime?,
  val toPrisonId: String?,
)
