package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Criteria-API implementation of the establishment-wide list queries. Both queries share the same
 * [predicates], so the page of prisoners and the containers fetched for them apply identical filters.
 */
class PropertyContainerRepositoryImpl(
  @PersistenceContext private val entityManager: EntityManager,
) : PropertyContainerRepositoryCustom {

  override fun findPrisonerNumbersPage(prisonId: String, filter: PrisonPropertyFilter, pageable: Pageable): Page<String> {
    val cb = entityManager.criteriaBuilder

    val query = cb.createQuery(String::class.java)
    val root = query.from(PropertyContainer::class.java)
    query.select(root.get("prisonerNumber"))
      .distinct(true)
      .where(*predicates(cb, root, prisonId, filter).toTypedArray())
      .orderBy(cb.asc(root.get<String>("prisonerNumber")))
    val content = entityManager.createQuery(query)
      .setFirstResult(pageable.offset.toInt())
      .setMaxResults(pageable.pageSize)
      .resultList

    val countQuery = cb.createQuery(Long::class.java)
    val countRoot = countQuery.from(PropertyContainer::class.java)
    countQuery.select(cb.countDistinct(countRoot.get<String>("prisonerNumber")))
      .where(*predicates(cb, countRoot, prisonId, filter).toTypedArray())
    val total = entityManager.createQuery(countQuery).singleResult

    return PageImpl(content, pageable, total)
  }

  override fun findPrisonerNumbers(prisonId: String, filter: PrisonPropertyFilter): List<String> {
    val cb = entityManager.criteriaBuilder
    val query = cb.createQuery(String::class.java)
    val root = query.from(PropertyContainer::class.java)
    query.select(root.get("prisonerNumber"))
      .distinct(true)
      .where(*predicates(cb, root, prisonId, filter).toTypedArray())
      .orderBy(cb.asc(root.get<String>("prisonerNumber")))
    return entityManager.createQuery(query).resultList
  }

  override fun findContainers(prisonId: String, filter: PrisonPropertyFilter, prisonerNumbers: List<String>): List<PropertyContainer> {
    if (prisonerNumbers.isEmpty()) return emptyList()
    val cb = entityManager.criteriaBuilder
    val query = cb.createQuery(PropertyContainer::class.java)
    val root = query.from(PropertyContainer::class.java)
    val predicates = predicates(cb, root, prisonId, filter).toMutableList()
    predicates += root.get<String>("prisonerNumber").`in`(prisonerNumbers)
    query.select(root)
      .where(*predicates.toTypedArray())
      .orderBy(cb.asc(root.get<String>("prisonerNumber")), cb.asc(root.get<LocalDateTime>("createDateTime")))
    return entityManager.createQuery(query).resultList
  }

  private fun predicates(cb: CriteriaBuilder, root: Root<PropertyContainer>, prisonId: String, filter: PrisonPropertyFilter): List<Predicate> {
    // Filters that apply to every row regardless of scope (held here vs due to transfer in).
    val predicates = mutableListOf<Predicate>()
    filter.prisonerNumber?.let { predicates += cb.equal(root.get<String>("prisonerNumber"), it) }
    // Seal numbers are matched case-insensitively (staff shouldn't have to reproduce the exact case).
    filter.sealNumber?.let { predicates += cb.equal(cb.lower(root.get<String>("currentSealNumber")), it.lowercase()) }
    if (filter.containerTypes.isNotEmpty()) predicates += root.get<ContainerType>("containerType").`in`(filter.containerTypes)

    // Free-text search matches (OR) prisoner number, seal number, or the term's resolved storage location.
    filter.search?.let { term ->
      val matches = mutableListOf(
        cb.equal(root.get<String>("prisonerNumber"), term.uppercase()),
        cb.equal(cb.lower(root.get<String>("currentSealNumber")), term.lowercase()),
      )
      if (filter.searchBranston) matches += cb.equal(root.get<StorageLocationType>("currentStorageLocationType"), StorageLocationType.BRANSTON)
      if (filter.searchLocationIds.isNotEmpty()) matches += root.get<UUID>("currentInternalLocationId").`in`(filter.searchLocationIds)
      predicates += cb.or(*matches.toTypedArray())
    }

    // Scope: containers physically held at this prison (with the status/location filters), optionally OR'd
    // with containers held elsewhere that are due to transfer *in* here. When "due for transfer in" is the
    // only status selection, the held-here scope drops out so the list shows only incoming property.
    val scopes = mutableListOf<Predicate>()
    val heldHereSelected = filter.statuses.isNotEmpty() || filter.includeRemoved || !filter.includeTransferIn
    if (heldHereSelected) scopes += heldHereScope(cb, root, prisonId, filter)
    if (filter.includeTransferIn) scopes += incomingScope(cb, root, prisonId, filter)
    predicates += cb.or(*scopes.toTypedArray())

    return predicates
  }

  /**
   * Predicate for containers physically held at [prisonId], with the status and storage-location filters
   * applied. No status filter hides containers that have left active storage; an explicit filter matches
   * exactly. includeRemoved additionally surfaces removed/returned/disposed containers alongside either
   * selection (transferred, combined and created-in-error stay hidden - they live in the person view).
   *
   * The statuses split three ways, mirroring the precedence in `ContainerStatusResolver`:
   *  - the removal outcomes match the denormalised column directly - a removed container's status is its own
   *    business and no owner movement changes it;
   *  - DISPOSAL_REQUIRED is time-based rather than denormalised, so it matches on the proposed disposal date
   *    having arisen;
   *  - the live statuses (stored / due for return / due for transfer out) depend on where the owner now is,
   *    so they go through [effectivelyLive].
   * Because the three are mutually exclusive, filtering by the status shown on screen returns exactly the rows
   * showing it.
   */
  private fun heldHereScope(cb: CriteriaBuilder, root: Root<PropertyContainer>, prisonId: String, filter: PrisonPropertyFilter): Predicate {
    val parts = mutableListOf<Predicate>(cb.equal(root.get<String>("prisonId"), prisonId))

    val noLongerHeld = root.get<RemovalOutcome>("removalOutcome")
      .`in`(RemovalOutcome.REMOVED, RemovalOutcome.RETURNED, RemovalOutcome.DISPOSED)
    val statusPredicate = if (filter.statuses.isEmpty()) {
      // No status filter: membership does not depend on status, so no owner classification is needed.
      cb.isNull(root.get<RemovalOutcome>("removalOutcome"))
    } else {
      val statusParts = mutableListOf<Predicate>()
      val persistedOnly = filter.statuses.filter { it != ContainerStatus.DISPOSAL_REQUIRED && it !in OwnerLocation.LIVE_STATUSES }
      if (persistedOnly.isNotEmpty()) statusParts += root.get<ContainerStatus>("currentStatusValue").`in`(persistedOnly)
      if (filter.statuses.contains(ContainerStatus.DISPOSAL_REQUIRED)) statusParts += disposalDue(cb, root)
      filter.statuses.filter { it in OwnerLocation.LIVE_STATUSES }
        .forEach { statusParts += effectivelyLive(cb, root, it, filter.statusOverlay) }
      cb.or(*statusParts.toTypedArray())
    }
    parts += if (filter.includeRemoved) cb.or(statusPredicate, noLongerHeld) else statusPredicate

    when {
      filter.branstonOnly -> parts += cb.equal(root.get<StorageLocationType>("currentStorageLocationType"), StorageLocationType.BRANSTON)
      filter.locationIds != null ->
        parts += if (filter.locationIds.isEmpty()) cb.disjunction() else root.get<UUID>("currentInternalLocationId").`in`(filter.locationIds)
    }
    return cb.and(*parts.toTypedArray())
  }

  /**
   * Predicate for property held elsewhere that is needed at [prisonId] - what the establishment's "due for
   * transfer in" list shows. Two ways a container qualifies, OR'd:
   *
   *  - **the container says so**: its recorded destination is here, either because the owner's reception was
   *    logged against it or because the sending prison has already transferred it out to us; or
   *  - **its owner says so**: the property is still in storage at another prison and its owner is now on this
   *    prison's roll, so it has to follow them.
   *
   * The second is the one that catches everything else. A container's recorded destination is only as good as
   * the movement events that reached it - NOMIS-migrated property has none at all, and ordinary sync traffic
   * (a move, a reseal) resets the derived status and clears the destination again. The owner's location does
   * not decay, so it is the more reliable signal.
   *
   * This mirrors the person page, which lists any live container held at another establishment as incoming
   * while its owner is here. The two must agree: the same box seen from a prisoner and from the establishment
   * is still the same box.
   *
   * With no roll (prisoner-search unavailable) only the recorded destinations match, which is where this
   * started - the list narrows rather than failing.
   */
  private fun incomingScope(cb: CriteriaBuilder, root: Root<PropertyContainer>, prisonId: String, filter: PrisonPropertyFilter): Predicate {
    val parts = mutableListOf(cb.equal(root.get<String>("receivingPrisonId"), prisonId))
    filter.incomingPrisonerNumbers?.takeIf { it.isNotEmpty() }?.let { roll ->
      parts += cb.and(
        cb.notEqual(root.get<String>("prisonId"), prisonId),
        // Only property still in storage there. Removed property is not ours to receive - and the one removed
        // case that is, an unreconciled transfer heading here, is already covered by the clause above.
        cb.isNull(root.get<RemovalOutcome>("removalOutcome")),
        root.get<String>("prisonerNumber").`in`(roll),
      )
    }
    return cb.or(*parts.toTypedArray())
  }

  /** Still in storage and past its proposed disposal date - the time-based DISPOSAL_REQUIRED status. */
  private fun disposalDue(cb: CriteriaBuilder, root: Root<PropertyContainer>): Predicate = cb.and(
    cb.isNull(root.get<RemovalOutcome>("removalOutcome")),
    cb.lessThanOrEqualTo(root.get<LocalDate>("proposedDisposalDate"), LocalDate.now()),
  )

  /**
   * Containers still in storage, not due for disposal, whose *effective* status is [status] - i.e. what the
   * views actually show, not what the status column happens to hold.
   *
   * A live container's status depends on where its owner is, so matching a status means matching each group of
   * owners together with the persisted statuses that read as [status] for that group. Both halves come from
   * `OwnerLocation.persistedStatusesReadingAs`, the inverse of the rule the rows use, so the filter cannot drift
   * from what is on screen. Containers relabelled *away* from [status] fall out without a second clause -
   * filtering by STORED simply never matches the persisted statuses of an owner who has been released.
   *
   * With no [overlay] (unclassified, or prisoner-search unavailable) this falls back to the persisted status,
   * which is how every surface degrades together rather than inconsistently.
   */
  private fun effectivelyLive(
    cb: CriteriaBuilder,
    root: Root<PropertyContainer>,
    status: ContainerStatus,
    overlay: StatusOverlay?,
  ): Predicate {
    val live = cb.and(
      cb.isNull(root.get<RemovalOutcome>("removalOutcome")),
      cb.or(
        cb.isNull(root.get<LocalDate>("proposedDisposalDate")),
        cb.greaterThan(root.get<LocalDate>("proposedDisposalDate"), LocalDate.now()),
      ),
    )
    if (overlay == null) return cb.and(live, cb.equal(root.get<ContainerStatus>("currentStatusValue"), status))

    val prisonerNumber = root.get<String>("prisonerNumber")
    val matches = overlay.matchesFor(status).map { (prisoners, persistedStatuses) ->
      cb.and(prisonerNumber.`in`(prisoners), root.get<ContainerStatus>("currentStatusValue").`in`(persistedStatuses))
    }
    return if (matches.isEmpty()) cb.disjunction() else cb.and(live, cb.or(*matches.toTypedArray()))
  }
}
