package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import java.util.UUID

/**
 * Resolved filters for the establishment-wide property list. All fields are optional; an absent field
 * does not constrain the results.
 *
 * @param statuses when empty the list hides containers that have left active storage (removed); when set
 *   it matches exactly those statuses, so a removed status can be requested explicitly.
 * @param locationIds the internal location ids a searched storage-location code resolved to. Null means no
 *   location filter; an empty list means the code matched no location, so nothing should be returned.
 * @param branstonOnly restrict to containers held offsite at Branston (takes precedence over [locationIds]).
 */
data class PrisonPropertyFilter(
  val prisonerNumber: String? = null,
  val sealNumber: String? = null,
  val containerType: ContainerType? = null,
  val statuses: List<ContainerStatus> = emptyList(),
  val locationIds: List<UUID>? = null,
  val branstonOnly: Boolean = false,
)
