package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * The current status of a [PropertyContainer]. Derived from its most recent [PropertyEvent] -
 * never persisted.
 */
enum class ContainerStatus {
  STORED,
  DUE_FOR_TRANSFER_OUT,
  DUE_FOR_RETURN,
  DISPOSAL_REQUIRED,
  DISPOSED,
  RETURNED,
  TRANSFER,
  COMBINED,
  CREATED_IN_ERROR,

  // The prisoner was admitted to custody while this property was still held from before. Nobody can be sure where
  // it is now, so it stays in this status until a member of staff confirms its location or removes it. Takes
  // precedence over a disposal date that has arisen and over where the owner now is.
  REVIEW_REQUIRED,

  // Removed from the establishment with no recorded reason - the reversible state a NOMIS "inactive"
  // (ACTIVE_FLAG='N') container maps to, where we can't tell whether it was returned, disposed or transferred.
  REMOVED,
}
