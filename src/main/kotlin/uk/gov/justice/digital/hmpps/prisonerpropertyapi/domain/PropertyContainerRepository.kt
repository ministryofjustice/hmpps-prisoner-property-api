package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

interface PropertyContainerRepository :
  JpaRepository<PropertyContainer, UUID>,
  PropertyContainerRepositoryCustom {
  // Fetch the events in the same query when loading a single container - its current seal, status and
  // location are derived from them, so a lazy load would otherwise mean an extra query (N+1).
  @EntityGraph("PropertyContainer.withEvents")
  override fun findById(id: UUID): Optional<PropertyContainer>

  fun findByPrisonerNumber(prisonerNumber: String): List<PropertyContainer>

  /**
   * How many containers are currently held in each internal location of a prison, read straight from the
   * denormalised current_internal_location_id (null once a container is removed or held offsite), so only
   * containers physically present in an internal box are counted - no events are loaded.
   */
  @Query(
    "select c.currentInternalLocationId as locationId, count(c) as count " +
      "from PropertyContainer c " +
      "where c.prisonId = :prisonId and c.currentInternalLocationId is not null " +
      "group by c.currentInternalLocationId",
  )
  fun countContainersByLocation(@Param("prisonId") prisonId: String): List<LocationContainerCount>

  /**
   * How many containers are currently held in one internal location, optionally excluding some by id (the
   * container(s) being written, so a write does not count itself against the location's capacity). Reads the
   * denormalised current_internal_location_id, so only containers physically present there are counted.
   */
  @Query(
    "select count(c) from PropertyContainer c " +
      "where c.currentInternalLocationId = :locationId " +
      "and (:excludedIds is null or c.id not in :excludedIds)",
  )
  fun countContainersInLocation(
    @Param("locationId") locationId: UUID,
    @Param("excludedIds") excludedIds: Collection<UUID>?,
  ): Long

  /**
   * The prisoners holding live (not removed) property at a prison. These are exactly the people whose location
   * can change what their property's status reads, so this is the candidate set the establishment's owner
   * classification is built over - see StatusOverlay. Served by the (prison_id, prisoner_number) index.
   */
  @Query("select distinct c.prisonerNumber from PropertyContainer c where c.prisonId = :prisonId and c.removalOutcome is null")
  fun findActivePrisonerNumbers(@Param("prisonId") prisonId: String): List<String>

  /**
   * How many containers a prison holds for each prisoner in each persisted status, counting only containers
   * still in storage and not yet due for disposal - i.e. the ones whose displayed status depends on where their
   * owner is. Feeds the establishment summary counts: grouping by prisoner as well as status is what lets the
   * counts apply the owner classification, so a tile agrees with the rows the matching filter returns.
   *
   * Excluding containers whose disposal date has arisen keeps the buckets exclusive - disposal takes precedence,
   * so those are counted by [countDueForDisposal] alone rather than in two tiles at once.
   */
  @Query(
    "select c.prisonerNumber as prisonerNumber, c.currentStatusValue as status, count(c) as count " +
      "from PropertyContainer c " +
      "where c.prisonId = :prisonId and c.removalOutcome is null " +
      "and (c.proposedDisposalDate is null or c.proposedDisposalDate > :today) " +
      "group by c.prisonerNumber, c.currentStatusValue",
  )
  fun countActiveByPrisonerAndStatus(
    @Param("prisonId") prisonId: String,
    @Param("today") today: LocalDate,
  ): List<PrisonerStatusContainerCount>

  /**
   * How many active (not removed) containers a prison holds whose proposed disposal date has now arisen
   * (today or earlier) - i.e. are due for disposal. Disposal is time-based, so this is queried on the date
   * rather than the denormalised status.
   */
  @Query(
    "select count(c) from PropertyContainer c " +
      "where c.prisonId = :prisonId and c.removalOutcome is null " +
      "and c.proposedDisposalDate is not null and c.proposedDisposalDate <= :today",
  )
  fun countDueForDisposal(@Param("prisonId") prisonId: String, @Param("today") today: LocalDate): Long

  /** Whether any active (not removed) container already holds this seal number - used to enforce staff seal uniqueness. */
  fun existsByCurrentSealNumberAndRemovalOutcomeIsNull(currentSealNumber: String): Boolean

  /** As above, excluding a given container (so a container amending its own seal does not clash with itself). */
  fun existsByCurrentSealNumberAndRemovalOutcomeIsNullAndIdNot(currentSealNumber: String, id: UUID): Boolean
}

/** Projection for [PropertyContainerRepository.countContainersByLocation]: a location id and its container count. */
interface LocationContainerCount {
  val locationId: UUID
  val count: Long
}

/**
 * Projection for [PropertyContainerRepository.countActiveByPrisonerAndStatus]: a prisoner, one of their
 * persisted container statuses, and how many of their containers hold it.
 */
interface PrisonerStatusContainerCount {
  val prisonerNumber: String
  val status: ContainerStatus
  val count: Long
}
