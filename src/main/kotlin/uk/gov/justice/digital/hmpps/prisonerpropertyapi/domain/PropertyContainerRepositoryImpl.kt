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
    filter.sealNumber?.let { predicates += cb.equal(root.get<String>("currentSealNumber"), it) }
    if (filter.containerTypes.isNotEmpty()) predicates += root.get<ContainerType>("containerType").`in`(filter.containerTypes)

    // Free-text search matches (OR) prisoner number, seal number, or the term's resolved storage location.
    filter.search?.let { term ->
      val matches = mutableListOf(
        cb.equal(root.get<String>("prisonerNumber"), term.uppercase()),
        cb.equal(root.get<String>("currentSealNumber"), term),
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
    if (filter.includeTransferIn) scopes += cb.equal(root.get<String>("receivingPrisonId"), prisonId)
    predicates += cb.or(*scopes.toTypedArray())

    return predicates
  }

  /**
   * Predicate for containers physically held at [prisonId], with the status and storage-location filters
   * applied. No status filter hides containers that have left active storage; an explicit filter matches
   * exactly. includeRemoved additionally surfaces returned/disposed containers alongside either selection.
   * DISPOSAL_REQUIRED is time-based (not held in the denormalised column), so it matches on the proposed
   * disposal date having arisen rather than on currentStatusValue.
   */
  private fun heldHereScope(cb: CriteriaBuilder, root: Root<PropertyContainer>, prisonId: String, filter: PrisonPropertyFilter): Predicate {
    val parts = mutableListOf<Predicate>(cb.equal(root.get<String>("prisonId"), prisonId))

    val returnedOrDisposed = root.get<RemovalOutcome>("removalOutcome").`in`(RemovalOutcome.RETURNED, RemovalOutcome.DISPOSED)
    val statusPredicate = if (filter.statuses.isEmpty()) {
      cb.isNull(root.get<RemovalOutcome>("removalOutcome"))
    } else {
      val statusParts = mutableListOf<Predicate>()
      val nonDisposal = filter.statuses.filter { it != ContainerStatus.DISPOSAL_REQUIRED }
      if (nonDisposal.isNotEmpty()) statusParts += root.get<ContainerStatus>("currentStatusValue").`in`(nonDisposal)
      if (filter.statuses.contains(ContainerStatus.DISPOSAL_REQUIRED)) {
        statusParts += cb.and(
          cb.isNull(root.get<RemovalOutcome>("removalOutcome")),
          cb.lessThanOrEqualTo(root.get<LocalDate>("proposedDisposalDate"), LocalDate.now()),
        )
      }
      cb.or(*statusParts.toTypedArray())
    }
    parts += if (filter.includeRemoved) cb.or(statusPredicate, returnedOrDisposed) else statusPredicate

    when {
      filter.branstonOnly -> parts += cb.equal(root.get<StorageLocationType>("currentStorageLocationType"), StorageLocationType.BRANSTON)
      filter.locationIds != null ->
        parts += if (filter.locationIds.isEmpty()) cb.disjunction() else root.get<UUID>("currentInternalLocationId").`in`(filter.locationIds)
    }
    return cb.and(*parts.toTypedArray())
  }
}
