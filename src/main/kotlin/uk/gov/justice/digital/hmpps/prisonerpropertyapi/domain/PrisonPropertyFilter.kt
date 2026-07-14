package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import java.util.UUID

/**
 * Resolved filters for the establishment-wide property list. All fields are optional; an absent field
 * does not constrain the results.
 *
 * @param containerTypes when set, matches any of these container types; empty leaves the type unfiltered.
 * @param statuses when empty the list hides containers that have left active storage (removed); when set
 *   it matches exactly those statuses, so a removed status can be requested explicitly.
 * @param includeRemoved when true, returned/disposed containers are also included (in addition to
 *   whatever [statuses] selects). When false, removed containers are hidden as before.
 * @param locationIds the internal location ids a searched storage-location code resolved to. Null means no
 *   location filter; an empty list means the code matched no location, so nothing should be returned.
 * @param branstonOnly restrict to containers held offsite at Branston (takes precedence over [locationIds]).
 * @param search a single free-text term matched (OR) against prisoner number, seal number and storage
 *   location. [searchLocationIds]/[searchBranston] carry the resolved storage-location part of that term.
 * @param includeTransferIn when true, additionally surface containers held at another prison that are due
 *   to be transferred *in* to this establishment (its owner was received here). Additive: it widens the
 *   held-here result rather than narrowing it. When it is the only status selection, only incoming
 *   property is returned.
 */
data class PrisonPropertyFilter(
  val prisonerNumber: String? = null,
  val sealNumber: String? = null,
  val containerTypes: List<ContainerType> = emptyList(),
  val statuses: List<ContainerStatus> = emptyList(),
  val includeRemoved: Boolean = false,
  val locationIds: List<UUID>? = null,
  val branstonOnly: Boolean = false,
  val search: String? = null,
  val searchLocationIds: List<UUID> = emptyList(),
  val searchBranston: Boolean = false,
  val includeTransferIn: Boolean = false,
)
