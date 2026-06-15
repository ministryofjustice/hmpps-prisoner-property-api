package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * Where a container is stored: an [INTERNAL] location within the prison (a locations-inside-prison-api
 * location id) or the offsite [BRANSTON] warehouse, which has no internal location id of its own.
 */
enum class StorageLocationType {
  INTERNAL,
  BRANSTON,
}
