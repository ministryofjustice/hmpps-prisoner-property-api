package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * How the prisoners holding live property at one establishment classify by [OwnerLocation] - i.e. which of
 * them have property that reads "due for return", "due for transfer out" or plain "stored" whatever the
 * container's persisted status column says.
 *
 * This exists so the establishment list's *status filter* can apply the same rule as the rows and the summary
 * counts. A container's effective status depends on its owner, which lives in prisoner-search rather than the
 * property database, so the filter cannot be pure SQL. But the dependency is only on the *prisoner*, so
 * classifying the prison's prisoners up front turns it back into a SQL predicate - a set membership on
 * prisoner_number - which keeps paging in the database where it belongs.
 *
 * Built by `PrisonStatusOverlayFactory`, which derives it from `ContainerStatusResolver.ownerLocation`; that
 * shared call is what makes the rows, the counts and the filter provably consistent rather than merely alike.
 */
data class StatusOverlay(
  /** Owner location per prisoner number. Absent (or [OwnerLocation.UNKNOWN]) means "keep the persisted status". */
  private val locations: Map<String, OwnerLocation>,
) {
  /** The prisoners grouped by where they are, which is the granularity the status filter binds. */
  private val byLocation: Map<OwnerLocation, Set<String>> = locations.entries
    .groupBy({ it.value }, { it.key })
    .mapValues { (_, numbers) -> numbers.toSet() }

  /** The prisoners this overlay classified - those holding live property at the establishment. */
  val candidates: Set<String> get() = locations.keys

  /**
   * For each group of prisoners, which persisted statuses their property reads as [status] - everything the
   * establishment list's status filter needs, expressed as set membership so it can go into SQL.
   *
   * Each entry means "for these prisoners, a container whose own status is one of these reads as [status]".
   * Groups that can never read as [status] are left out, so an empty result means nothing can match.
   */
  fun matchesFor(status: ContainerStatus): List<Pair<Set<String>, Set<ContainerStatus>>> = byLocation
    .map { (location, prisoners) -> prisoners to location.persistedStatusesReadingAs(status) }
    .filter { (prisoners, statuses) -> prisoners.isNotEmpty() && statuses.isNotEmpty() }

  /**
   * The status a container owned by [prisonerNumber] whose own status is [baseStatus] reads as - the same rule
   * [matchesFor] encodes, for the count paths that aggregate rather than select.
   */
  fun effectiveStatusOf(prisonerNumber: String, baseStatus: ContainerStatus): ContainerStatus = (locations[prisonerNumber] ?: OwnerLocation.UNKNOWN).statusFor(baseStatus)

  /**
   * Prisoners who could not be resolved (prisoner-search unavailable, or no current prison). Their property
   * keeps its persisted status, so the views degrade to their pre-overlay behaviour rather than to nothing.
   */
  val unresolved: Set<String> get() = byLocation[OwnerLocation.UNKNOWN].orEmpty()
}
