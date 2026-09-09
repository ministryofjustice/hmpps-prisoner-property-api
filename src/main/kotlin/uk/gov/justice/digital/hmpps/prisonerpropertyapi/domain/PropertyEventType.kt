package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * What happened to a [PropertyContainer]. The container's current seal, status and location are
 * derived from the most recent relevant event.
 */
enum class PropertyEventType(val status: ContainerStatus, val carriesSeal: Boolean = false) {
  CREATED_SEALED(ContainerStatus.STORED, carriesSeal = true),
  SEAL_CHANGED(ContainerStatus.STORED, carriesSeal = true),
  CONTAINER_TYPE_CHANGE(ContainerStatus.STORED),
  MOVED(ContainerStatus.STORED),
  PRISONER_RECEIVED(ContainerStatus.DUE_FOR_TRANSFER_OUT),

  // The person left on transfer. Recorded separately from PRISONER_RECEIVED because it is a different fact
  // with a different shape: it happens at the sending prison, hours before any reception, and carries no
  // destination - nobody knows where the person is going until they arrive. Both can appear against one
  // container for one move, and both are true: they left, and later they were received somewhere.
  PRISONER_TRANSFERRED_OUT(ContainerStatus.DUE_FOR_TRANSFER_OUT),
  PRISONER_RELEASED(ContainerStatus.DUE_FOR_RETURN),
  DIED_IN_CUSTODY(ContainerStatus.DUE_FOR_RETURN),
  TRANSFERRED(ContainerStatus.TRANSFER),
  RETURNED(ContainerStatus.RETURNED),
  DISPOSAL_REQUIRED(ContainerStatus.DISPOSAL_REQUIRED),
  DISPOSED(ContainerStatus.DISPOSED),
  COMBINED(ContainerStatus.COMBINED),
  CREATED_IN_ERROR(ContainerStatus.CREATED_IN_ERROR),

  // Removed from the establishment with no recorded reason (a NOMIS "inactive" container), and its reversal:
  // REACTIVATED brings a removed container back to active storage (STORED), re-deriving its location.
  REMOVED(ContainerStatus.REMOVED),
  REACTIVATED(ContainerStatus.STORED),
}
