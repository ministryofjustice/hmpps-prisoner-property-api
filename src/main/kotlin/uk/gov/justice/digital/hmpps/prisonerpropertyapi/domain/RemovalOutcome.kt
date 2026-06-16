package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * Why a [PropertyContainer] left active storage - a terminal state. Maintained on the container as a
 * single current value (with the matching [PropertyEventType] recorded in history), and drives the
 * derived [ContainerStatus]. Once set, the container is no longer active: its location is cleared and
 * its seal is freed for re-use.
 */
enum class RemovalOutcome(val status: ContainerStatus, val eventType: PropertyEventType) {
  DISPOSED(ContainerStatus.DISPOSED, PropertyEventType.DISPOSED),
  RETURNED(ContainerStatus.RETURNED, PropertyEventType.RETURNED),
  TRANSFERRED(ContainerStatus.TRANSFER, PropertyEventType.TRANSFERRED),
}
