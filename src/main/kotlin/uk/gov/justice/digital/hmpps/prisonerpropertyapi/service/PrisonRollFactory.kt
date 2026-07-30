package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient

/**
 * Who is currently at an establishment.
 *
 * The establishment list needs this to answer "what property is coming here": property that belongs to
 * someone here but is still held at the prison they came from. The property database cannot answer it - it
 * only knows about people who already have property here - so the roll comes from prisoner-search.
 *
 * Deliberately separate from [PrisonStatusOverlayFactory], which resolves the opposite direction (where the
 * owners of property *here* have gone) and fails differently.
 */
@Component
class PrisonRollFactory(
  private val prisonerSearchClient: PrisonerSearchClient,
) {

  /**
   * The prisoner numbers of everyone at [prisonId], or null when the roll could not be resolved at all.
   *
   * Null and empty mean different things and callers must keep them apart: null is "we do not know who is
   * here", which falls back to the transfer destinations recorded on the containers themselves; empty is "we
   * know, and nobody is here", which correctly surfaces no incoming property.
   *
   * A roll cut short by the page size is used anyway, unlike [PrisonStatusOverlayFactory]'s cap, which
   * abandons the whole classification. The two differ because the consequences differ: a partial roll only
   * omits rows, which is what falling back does more of, whereas a partial classification would *mislabel*
   * property that is present. It is still logged as an error - a prison roll that large means something is
   * wrong rather than merely big.
   */
  fun prisonersAt(prisonId: String): Set<String>? {
    val roll = prisonerSearchClient.getPrisonRoll(prisonId) ?: return null
    if (roll.isTruncated()) {
      log.error(
        "Prison roll for {} came back short ({} of {}), so some incoming property will not be listed",
        prisonId,
        roll.prisonerNumbers.size,
        roll.totalAtPrison,
      )
    }
    return roll.prisonerNumbers
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
