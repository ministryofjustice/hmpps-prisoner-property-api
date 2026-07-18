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

  // Removed from the establishment with no recorded reason - the reversible state a NOMIS "inactive"
  // (ACTIVE_FLAG='N') container maps to, where we can't tell whether it was returned, disposed or transferred.
  REMOVED,
}
