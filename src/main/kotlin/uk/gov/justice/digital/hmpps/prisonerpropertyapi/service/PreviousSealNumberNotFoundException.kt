package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

/**
 * A container was added quoting a previous seal number that matches no property the prisoner holds at another
 * establishment - so there is nothing for the new record to be matched to.
 *
 * Rejected rather than ignored: silently creating the container is how one physical box came to be recorded
 * twice, once at the receiving prison and once still awaiting transfer at the sending one.
 */
class PreviousSealNumberNotFoundException(val previousSealNumber: String) :
  RuntimeException(
    "No property container held at another establishment for this person has seal number: $previousSealNumber",
  ) {
  companion object {
    /** Lets the front end anchor the error to the previous-seal field rather than matching on the message. */
    const val ERROR_CODE = "PREVIOUS_SEAL_NUMBER_NOT_FOUND"
  }
}
