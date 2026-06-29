package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
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
    val predicates = mutableListOf(
      cb.equal(root.get<String>("prisonId"), prisonId),
      cb.isFalse(root.get("archived")),
    )
    filter.prisonerNumber?.let { predicates += cb.equal(root.get<String>("prisonerNumber"), it) }
    filter.sealNumber?.let { predicates += cb.equal(root.get<String>("currentSealNumber"), it) }
    filter.containerType?.let { predicates += cb.equal(root.get<ContainerType>("containerType"), it) }

    // No status filter hides containers that have left active storage; an explicit filter matches exactly.
    if (filter.statuses.isEmpty()) {
      predicates += cb.isNull(root.get<RemovalOutcome>("removalOutcome"))
    } else {
      predicates += root.get<ContainerStatus>("currentStatusValue").`in`(filter.statuses)
    }

    when {
      filter.branstonOnly -> predicates += cb.equal(root.get<StorageLocationType>("currentStorageLocationType"), StorageLocationType.BRANSTON)
      filter.locationIds != null ->
        predicates += if (filter.locationIds.isEmpty()) cb.disjunction() else root.get<UUID>("currentInternalLocationId").`in`(filter.locationIds)
    }
    return predicates
  }
}
