package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * The current status of a [PropertyContainer]. Derived from its most recent [PropertyEvent] -
 * never persisted.
 */
enum class ContainerStatus {
  STORED,
  DISPOSAL_REQUIRED,
  DISPOSED,
  RETURNED,
  TRANSFER,
  COMBINED,
}
