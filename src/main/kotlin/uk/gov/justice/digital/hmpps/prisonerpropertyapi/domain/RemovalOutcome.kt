package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * Why a [PropertyContainer] left active storage. Maintained on the container as a single current value (with
 * the matching [PropertyEventType] recorded in history), and drives the derived [ContainerStatus]. While set,
 * the container is no longer active: its location is cleared and its seal is freed for re-use. Most outcomes
 * are terminal; [REMOVED] is reversible (a container can be reactivated), and [TRANSFERRED] reassigns the
 * container to another prison where it stays live.
 */
enum class RemovalOutcome(val status: ContainerStatus, val eventType: PropertyEventType) {
  DISPOSED(ContainerStatus.DISPOSED, PropertyEventType.DISPOSED),
  RETURNED(ContainerStatus.RETURNED, PropertyEventType.RETURNED),
  TRANSFERRED(ContainerStatus.TRANSFER, PropertyEventType.TRANSFERRED),
  COMBINED(ContainerStatus.COMBINED, PropertyEventType.COMBINED),
  CREATED_IN_ERROR(ContainerStatus.CREATED_IN_ERROR, PropertyEventType.CREATED_IN_ERROR),

  // Removed from the establishment, reason unknown - what a NOMIS "inactive" container maps to. Reversible:
  // clearing the outcome and appending a REACTIVATED event brings the container back to active storage.
  REMOVED(ContainerStatus.REMOVED, PropertyEventType.REMOVED),
}
