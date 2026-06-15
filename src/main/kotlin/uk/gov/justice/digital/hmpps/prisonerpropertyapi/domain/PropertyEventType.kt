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
  TRANSFERRED(ContainerStatus.TRANSFER),
  RETURNED(ContainerStatus.RETURNED),
  DISPOSAL_REQUIRED(ContainerStatus.DISPOSAL_REQUIRED),
  DISPOSED(ContainerStatus.DISPOSED),
}
