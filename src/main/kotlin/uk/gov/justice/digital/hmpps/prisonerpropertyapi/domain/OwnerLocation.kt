package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * Where the owner of a container is, relative to the prison holding it - the one thing outside the container's
 * own events that decides what its status should read. Resolved from prisoner-search; see
 * `ContainerStatusResolver.ownerLocation`.
 *
 * A live container's status largely follows its owner, because the property has to end up wherever the person
 * does: once they are released it is due back to them, once they have moved on it has to follow them, and while
 * they are here it is simply stored. That makes the owner's location authoritative over the container's
 * persisted status, which is only as good as the movement events that happened to reach us - NOMIS-migrated
 * property often has none, and a later move or reseal resets the status to STORED.
 */
enum class OwnerLocation(
  /** The status this location gives a live container, or null to leave the container's own status alone. */
  private val forcedStatus: ContainerStatus?,
  /** Statuses this location does *not* override, because the container's own record is the better authority. */
  private val preservedStatuses: Set<ContainerStatus> = emptySet(),
) {
  /** Released, or being released imminently: their property is due back to them. */
  RETURNING(ContainerStatus.DUE_FOR_RETURN),

  /** In another establishment, or in transit to one: their property needs to follow them. */
  ELSEWHERE(ContainerStatus.DUE_FOR_TRANSFER_OUT),

  /**
   * In the establishment holding the container: their property is not going anywhere, so it is stored - which
   * also clears a stale "follow the person" flag left by a move they have since returned from.
   *
   * It does *not* clear a recorded due-for-return, though. That is written by a real release or death event, and
   * prisoner-search is an eventually-consistent cache fed by the same movements - so a lag between the two would
   * otherwise flip correctly flagged property back to stored, which is exactly the miss this whole rule exists
   * to prevent. Left standing, the worst case is staff seeing "due for return" for someone still here and
   * checking; cleared, the worst case is property for a released person quietly going unnoticed.
   */
  HERE(ContainerStatus.STORED, preservedStatuses = setOf(ContainerStatus.DUE_FOR_RETURN)),

  /** Could not be resolved (prisoner-search unavailable, or no current prison): the container decides. */
  UNKNOWN(null),
  ;

  /**
   * The status a live container reads when its owner is here and its own record says [baseStatus]. The single
   * mapping from owner location to status: the person view, the establishment list, the summary counts and the
   * list's status filter all derive from this one function, so they cannot disagree.
   */
  fun statusFor(baseStatus: ContainerStatus): ContainerStatus = if (baseStatus in preservedStatuses) baseStatus else forcedStatus ?: baseStatus

  /**
   * The persisted statuses that read as [status] for an owner in this location - i.e. what the establishment
   * list's status filter has to match on. The inverse of [statusFor], so the filter and the rows cannot drift.
   */
  fun persistedStatusesReadingAs(status: ContainerStatus): Set<ContainerStatus> = LIVE_STATUSES.filterTo(mutableSetOf()) { statusFor(it) == status }

  companion object {
    /**
     * The statuses a container still in storage can hold, and so also the ones it can be shown as once its
     * owner's location is applied. Anything else - the removal outcomes, and the time-based DISPOSAL_REQUIRED -
     * is decided by the container alone, with no owner lookup needed.
     */
    val LIVE_STATUSES: Set<ContainerStatus> = setOf(
      ContainerStatus.STORED,
      ContainerStatus.DUE_FOR_RETURN,
      ContainerStatus.DUE_FOR_TRANSFER_OUT,
    )
  }
}
