package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * The current status of a [PropertyContainer]. Derived from its most recent [PropertyEvent] -
 * never persisted.
 */
enum class ContainerStatus {
  STORED,
  DISPOSED,
  RETURNED,
  TRANSFER,
}
