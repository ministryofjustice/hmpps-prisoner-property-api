package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * Filters the establishment property list by where the property's owner currently is, relative to the
 * viewed establishment. A prisoner's current establishment comes from prisoner-search (not the property
 * DB), so this is applied in memory after a batch lookup - see PropertyContainerService.getPrisonProperty.
 */
enum class PersonLocation {
  /** People currently held in the viewed establishment. */
  IN_ESTABLISHMENT,

  /** People no longer in the viewed establishment (moved on, released or in transit). */
  LEFT_ESTABLISHMENT,
  ;

  /** Whether a prisoner whose current prison is [currentPrisonId] belongs in this bucket for [viewedPrisonId]. */
  fun matches(currentPrisonId: String?, viewedPrisonId: String): Boolean {
    val here = currentPrisonId == viewedPrisonId
    return when (this) {
      IN_ESTABLISHMENT -> here
      LEFT_ESTABLISHMENT -> !here
    }
  }
}
