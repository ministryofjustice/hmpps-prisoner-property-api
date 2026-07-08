package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * The current status of a [PropertyContainer]. Derived from its most recent [PropertyEvent] -
 * never persisted.
 */
enum class ContainerStatus {
  STORED,
  DUE_FOR_TRANSFER_OUT,
  DISPOSAL_REQUIRED,
  DISPOSED,
  RETURNED,
  TRANSFER,
  COMBINED,
  CREATED_IN_ERROR,
}
