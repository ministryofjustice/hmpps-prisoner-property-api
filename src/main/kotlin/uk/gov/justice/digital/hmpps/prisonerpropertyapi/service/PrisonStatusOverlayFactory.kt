package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StatusOverlay

/**
 * The classification of one establishment's prisoners, plus the prisoner records it was built from so callers
 * can enrich rows without looking the same people up twice.
 *
 * [overlay] is null when the classification could not be made (see [PrisonStatusOverlayFactory.overlayFor]),
 * which every consumer reads as "keep the persisted status" - the pre-overlay behaviour.
 */
data class OwnerClassification(
  val overlay: StatusOverlay?,
  val prisoners: Map<String, Prisoner>,
) {
  companion object {
    val NONE = OwnerClassification(null, emptyMap())
  }
}

/**
 * Builds the [StatusOverlay] for an establishment: resolves the prisoners holding live property there and
 * classifies each by where they now are.
 *
 * The single place this classification is made, so the establishment list rows, its status filter and the
 * summary tiles all agree. It costs one bulk prisoner-search lookup, chunked by the client - bounded by the
 * number of prisoners holding property at one prison rather than by the container count.
 */
@Component
class PrisonStatusOverlayFactory(
  private val prisonerSearchClient: PrisonerSearchClient,
  private val statusResolver: ContainerStatusResolver,
) {

  /**
   * Classify [candidates] - the prisoners holding live property at [prisonId] - or return
   * [OwnerClassification.NONE] when there is nothing to classify or too much.
   *
   * The cap guards against binding a set of prisoner numbers so large that the resulting SQL is unreasonable
   * (Postgres allows 65,535 bind parameters per statement). It is far above any real establishment, so hitting
   * it means something is wrong rather than merely big - hence the ERROR. The consequence is that the views
   * fall back to the persisted status together, not that the read fails.
   */
  fun overlayFor(prisonId: String, candidates: Collection<String>): OwnerClassification {
    if (candidates.isEmpty()) return OwnerClassification.NONE
    if (candidates.size > MAX_CANDIDATES) {
      log.error(
        "Not classifying property owners at {}: {} candidates exceeds the cap of {}; " +
          "statuses and counts will fall back to the persisted status",
        prisonId,
        candidates.size,
        MAX_CANDIDATES,
      )
      return OwnerClassification.NONE
    }

    val distinct = candidates.toSet()
    val prisoners = prisonerSearchClient.getPrisoners(distinct)
    if (prisoners.size < distinct.size) {
      // A prisoner-search chunk failing degrades to an empty list rather than an error, so an incomplete
      // result is silent otherwise. The unresolved prisoners keep their property's persisted status, which is
      // the right fallback, but a persistently short result means the views are quietly under-reporting.
      log.warn(
        "Classified {} of {} property owners at {}; the rest keep their persisted status",
        prisoners.size,
        distinct.size,
        prisonId,
      )
    }

    return OwnerClassification(
      overlay = StatusOverlay(distinct.associateWith { statusResolver.ownerLocation(prisoners[it], prisonId) }),
      prisoners = prisoners,
    )
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)

    /** Above this many prisoners the classification is abandoned rather than bound into a query. */
    const val MAX_CANDIDATES = 20_000
  }
}
