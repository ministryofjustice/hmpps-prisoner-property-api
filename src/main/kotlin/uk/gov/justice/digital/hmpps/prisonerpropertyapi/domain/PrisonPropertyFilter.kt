package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import java.util.UUID

/**
 * Resolved filters for the establishment-wide property list. All fields are optional; an absent field
 * does not constrain the results.
 *
 * @param containerTypes when set, matches any of these container types; empty leaves the type unfiltered.
 * @param statuses when empty the list hides containers that have left active storage (removed); when set
 *   it matches exactly those statuses, so a removed status can be requested explicitly.
 * @param includeRemoved when true, removed/returned/disposed containers are also included (in addition to
 *   whatever [statuses] selects). Transferred, combined and created-in-error are deliberately left out:
 *   they are no longer this establishment's property or are bookkeeping rows. When false, containers that
 *   have left active storage are hidden.
 * @param locationIds the internal location ids a searched storage-location code resolved to. Null means no
 *   location filter; an empty list means the code matched no location, so nothing should be returned.
 * @param branstonOnly restrict to containers held offsite at Branston (takes precedence over [locationIds]).
 * @param search a single free-text term matched (OR) against prisoner number, seal number and storage
 *   location. [searchLocationIds]/[searchBranston] carry the resolved storage-location part of that term.
 * @param includeTransferIn when true, additionally surface containers held at another prison that are due
 *   to be transferred *in* to this establishment (its owner was received here). Additive: it widens the
 *   held-here result rather than narrowing it. When it is the only status selection, only incoming
 *   property is returned.
 * @param statusOverlay the establishment's owner classification, needed whenever [statuses] asks for a status
 *   a container still in storage can hold (see [OwnerLocation.LIVE_STATUSES]), since those depend on where the
 *   owner now is. Null means unclassified, and the status filter then matches the persisted status column
 *   alone - the behaviour before the overlay existed, and the fallback when prisoner-search is unavailable.
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
  val statusOverlay: StatusOverlay? = null,
) {
  /** Whether any requested status depends on the owner's location, and so needs a [statusOverlay] to resolve. */
  fun needsStatusOverlay(): Boolean = statuses.any { it in OwnerLocation.LIVE_STATUSES }
}
