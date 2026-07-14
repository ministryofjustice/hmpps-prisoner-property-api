package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PropertyLocation
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PersonLocation
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonPropertyFilter
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonerMovementStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonPropertySummaryDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyGroupDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerTimelineItemDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyEventDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.TimelineItemType
import java.time.LocalDate
import java.util.UUID

@Service
class PropertyContainerService(
  private val repository: PropertyContainerRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val prisonRegisterClient: PrisonRegisterClient,
  private val locationsClient: LocationsClient,
) {

  /**
   * The property containers held for a prisoner, enriched with the prisoner, prison and location names.
   * Optionally filtered to the given [statuses] (empty = all) and sorted by when each container was last
   * updated in the given [sortDirection].
   */
  @Transactional(readOnly = true)
  fun getByPrisonerNumber(
    prisonerNumber: String,
    statuses: List<ContainerStatus> = emptyList(),
    sortDirection: Sort.Direction = Sort.Direction.DESC,
  ): List<PrisonerPropertyContainerDto> {
    val containers = repository.findByPrisonerNumberAndArchivedFalse(prisonerNumber)
      .filter { statuses.isEmpty() || it.currentStatus() in statuses }
      .sortedWith(compareBy { it.lastUpdated() })
      .let { if (sortDirection.isDescending) it.reversed() else it }

    if (containers.isEmpty()) return emptyList()

    val prisoner = prisonerSearchClient.getPrisoner(prisonerNumber)
    val prisonNames = prisonRegisterClient.getPrisonNames()
    val locations = locationsClient.getLocations(containers.mapNotNull { it.currentLocation() })

    return containers.map { container ->
      PrisonerPropertyContainerDto.from(
        container = container,
        prisonerName = prisoner.fullName(),
        prisonName = prisonNames[container.prisonId],
        prisonerCurrentPrisonId = prisoner?.prisonId,
        prisonerCurrentPrisonName = prisoner?.prisonId?.let { prisonNames[it] },
        prisonerMovementStatus = prisoner.movementStatus(),
        locationDescription = container.currentLocation()?.let { locations[it]?.displayName() },
        inPrisonersCurrentPrison = prisoner?.prisonId == container.prisonId,
      )
    }
  }

  /**
   * Whole-prison property totals for the establishment summary tiles. "Stored on-site" is every container
   * physically in an internal location here (summing the per-location counts); "available storage spaces" is
   * the remaining capacity across the prison's property locations - the sum over each location of its capacity
   * minus the containers it currently holds (never negative), so a location with capacity 10 holding 8 leaves
   * 2 spaces. "Due to transfer out" comes from the denormalised status; "due to be disposed" is queried on the
   * proposed disposal date having arisen (disposal is time-based, not denormalised). "Due to be returned"
   * counts containers flagged due for return after the prisoner's release.
   */
  @Transactional(readOnly = true)
  fun getPrisonPropertySummary(prisonId: String): PrisonPropertySummaryDto {
    val counts = repository.countContainersByStatus(prisonId).associate { it.status to it.count }
    val countsByLocation = repository.countContainersByLocation(prisonId).associate { it.locationId to it.count.toInt() }
    val storedOnSite = countsByLocation.values.sum()
    val availableStorageSpaces = locationsClient.getPropertyLocations(prisonId).sumOf { location ->
      ((location.capacity ?: 0) - (countsByLocation[location.id] ?: 0)).coerceAtLeast(0)
    }
    return PrisonPropertySummaryDto(
      availableStorageSpaces = availableStorageSpaces,
      storedOnSite = storedOnSite,
      dueToTransferOut = counts.count(ContainerStatus.DUE_FOR_TRANSFER_OUT),
      dueToBeReturned = counts.count(ContainerStatus.DUE_FOR_RETURN),
      dueToBeDisposed = repository.countDueForDisposal(prisonId, LocalDate.now()).toInt(),
    )
  }

  /**
   * The establishment-wide property list: a page of prisoners (with all their matching containers), filtered
   * by [filter] and the searched [storageLocation] (a location code, or "BRANSTON" for offsite), enriched with
   * prisoner, prison and location names. Paging is by prisoner so a prisoner's containers are never split
   * across a page boundary; [Page.getTotalElements] is the number of matching prisoners. Reads the denormalised
   * status/location columns, so no container events are loaded.
   */
  @Transactional(readOnly = true)
  fun getPrisonProperty(
    prisonId: String,
    prisonerNumber: String? = null,
    sealNumber: String? = null,
    containerTypes: List<ContainerType> = emptyList(),
    statuses: List<ContainerStatus> = emptyList(),
    storageLocation: String? = null,
    includeRemoved: Boolean = false,
    search: String? = null,
    personLocation: PersonLocation? = null,
    pageable: Pageable,
  ): Page<PrisonerPropertyGroupDto> {
    val branstonOnly = storageLocation.equals(BRANSTON_SEARCH_TERM, ignoreCase = true)
    val searchBranston = search.equals(BRANSTON_SEARCH_TERM, ignoreCase = true)
    // Resolve the storage-location parts of both the exact filter and the free-text search up front, so a
    // single lookup of the prison's box locations covers whichever non-Branston terms are present.
    val needStorageLookup = (storageLocation != null && !branstonOnly) || (search != null && !searchBranston)
    val propertyLocations = if (needStorageLookup) locationsClient.getPropertyLocations(prisonId) else emptyList()
    val locationIds = if (storageLocation != null && !branstonOnly) resolveLocationIds(propertyLocations, storageLocation) else null
    val searchLocationIds = if (search != null && !searchBranston) resolveLocationIds(propertyLocations, search) else emptyList()
    val filter = PrisonPropertyFilter(
      prisonerNumber = prisonerNumber,
      sealNumber = sealNumber,
      containerTypes = containerTypes,
      statuses = statuses,
      includeRemoved = includeRemoved,
      locationIds = locationIds,
      branstonOnly = branstonOnly,
      search = search,
      searchLocationIds = searchLocationIds,
      searchBranston = searchBranston,
    )

    if (personLocation == null) {
      // Common path: the property DB paginates by prisoner, and prisoner-search is only called for the page.
      val prisonerPage = repository.findPrisonerNumbersPage(prisonId, filter, pageable)
      if (prisonerPage.isEmpty) return PageImpl(emptyList(), pageable, prisonerPage.totalElements)
      val prisoners = prisonerSearchClient.getPrisoners(prisonerPage.content)
      return buildGroupsPage(prisonId, filter, prisonerPage.content, prisonerPage.totalElements, pageable, prisoners)
    }

    // Person-location filter: a prisoner's current establishment comes from prisoner-search, not the DB, so
    // load every matching prisoner, bucket by current establishment, then paginate in memory.
    val allNumbers = repository.findPrisonerNumbers(prisonId, filter)
    if (allNumbers.isEmpty()) return PageImpl(emptyList(), pageable, 0)
    val prisoners = prisonerSearchClient.getPrisoners(allNumbers)
    val matching = allNumbers.filter { personLocation.matches(prisoners[it]?.prisonId, prisonId) }
    val pageNumbers = matching.drop(pageable.offset.toInt()).take(pageable.pageSize)
    return buildGroupsPage(prisonId, filter, pageNumbers, matching.size.toLong(), pageable, prisoners)
  }

  /**
   * Build a page of [PrisonerPropertyGroupDto] for [pageNumbers] (the prisoners on this page), enriching each
   * container with the supplied [prisoners] map plus prison and location names. [total] is the number of
   * matching prisoners across all pages.
   */
  private fun buildGroupsPage(
    prisonId: String,
    filter: PrisonPropertyFilter,
    pageNumbers: List<String>,
    total: Long,
    pageable: Pageable,
    prisoners: Map<String, Prisoner>,
  ): Page<PrisonerPropertyGroupDto> {
    if (pageNumbers.isEmpty()) return PageImpl(emptyList(), pageable, total)
    val containersByPrisoner = repository.findContainers(prisonId, filter, pageNumbers).groupBy { it.prisonerNumber }
    val prisonNames = prisonRegisterClient.getPrisonNames()
    val locations = locationsClient.getLocations(containersByPrisoner.values.flatten().mapNotNull { it.currentInternalLocationId })

    val groups = pageNumbers.map { number ->
      val prisoner = prisoners[number]
      PrisonerPropertyGroupDto(
        prisonerNumber = number,
        prisonerName = prisoner.fullName(),
        prisonerCurrentPrisonId = prisoner?.prisonId,
        prisonerCurrentPrisonName = prisoner?.prisonId?.let { prisonNames[it] },
        prisonerMovementStatus = prisoner.movementStatus(),
        containers = (containersByPrisoner[number] ?: emptyList()).map { container ->
          PrisonerPropertyContainerDto.fromColumns(
            container = container,
            prisonerName = prisoner.fullName(),
            prisonName = prisonNames[container.prisonId],
            prisonerCurrentPrisonId = prisoner?.prisonId,
            prisonerCurrentPrisonName = prisoner?.prisonId?.let { prisonNames[it] },
            prisonerMovementStatus = prisoner.movementStatus(),
            locationDescription = container.currentInternalLocationId?.let { locations[it]?.displayName() },
            inPrisonersCurrentPrison = prisoner?.prisonId == container.prisonId,
          )
        },
      )
    }
    return PageImpl(groups, pageable, total)
  }

  @Transactional(readOnly = true)
  fun getById(id: UUID): PropertyContainerDto = repository.findById(id)
    .map(PropertyContainerDto::from)
    .orElseThrow { PropertyContainerNotFoundException(id) }

  /** A container's full history, newest event first. Throws [PropertyContainerNotFoundException] if the container does not exist. */
  @Transactional(readOnly = true)
  fun getEvents(id: UUID): List<PropertyEventDto> {
    val container = repository.findById(id).orElseThrow { PropertyContainerNotFoundException(id) }
    val prisonNames = prisonRegisterClient.getPrisonNames()
    return container.events
      .sortedByDescending { it.eventDateTime }
      .map { PropertyEventDto.from(it, prisonNames) }
  }

  /**
   * A prisoner's whole-property history: every event across all of their (non-archived) containers, interleaved
   * newest first, plus a de-duplicated "arrived at ..." item for each prison the prisoner moved into. Prison and
   * location ids are resolved to names, and each container event carries the seal number and acting establishment
   * as at that point in the container's history (a container never changes prison, so its acting establishment is
   * the prison holding it; the seal is carried forward from the container's seal events). Returns an empty list if
   * the prisoner has no property.
   */
  @Transactional(readOnly = true)
  fun getPrisonerTimeline(prisonerNumber: String): List<PrisonerTimelineItemDto> {
    val containers = repository.findByPrisonerNumberAndArchivedFalse(prisonerNumber)
    if (containers.isEmpty()) return emptyList()

    val prisonNames = prisonRegisterClient.getPrisonNames()
    val prisoner = prisonerSearchClient.getPrisoner(prisonerNumber)
    val prisonerName = prisoner.fullName()
    val locations = locationsClient.getLocations(containers.mapNotNull { it.currentLocation() })

    val containerItems = containers.flatMap { container ->
      val actingEstablishmentName = prisonNames[container.prisonId]
      val locationDescription = container.currentLocation()?.let { locations[it]?.displayName() }
      var sealAsOfEvent: String? = null
      container.events.sortedBy { it.eventDateTime }.map { event ->
        event.sealNumber?.let { sealAsOfEvent = it }
        PrisonerTimelineItemDto.containerEvent(
          event = event,
          container = container,
          sealAsOfEvent = sealAsOfEvent,
          actingEstablishmentName = actingEstablishmentName,
          fromPrisonName = event.fromPrisonId?.let { prisonNames[it] },
          toPrisonName = event.toPrisonId?.let { prisonNames[it] },
          containerLocationDescription = locationDescription,
        )
      }
    }

    // One "arrived at ..." item per prison move, de-duplicated across the containers that each recorded it.
    val movementItems = containers.flatMap { it.events }
      .filter { it.eventType == PropertyEventType.PRISONER_RECEIVED && it.toPrisonId != null }
      .distinctBy { it.toPrisonId to it.eventDateTime }
      .map { event ->
        PrisonerTimelineItemDto.prisonerMovement(
          event = event,
          prisonerName = prisonerName,
          toPrisonName = event.toPrisonId?.let { prisonNames[it] },
        )
      }

    // A forward-looking "scheduled for release" marker, derived from the prisoner's release dates: prefer the
    // confirmed date, fall back to the conditional (sentence-calculated) one. Suppressed once the prisoner has
    // actually been released (the real release is already recorded as a PRISONER_RELEASED event).
    val releaseDate = prisoner?.confirmedReleaseDate ?: prisoner?.conditionalReleaseDate
    val scheduledItems = if (releaseDate != null && prisoner.movementStatus() != PrisonerMovementStatus.RELEASED) {
      listOf(PrisonerTimelineItemDto.scheduledForRelease(prisonerNumber, prisonerName, releaseDate))
    } else {
      emptyList()
    }

    // Newest first; at the same instant a movement sits above the container events it triggered.
    return (containerItems + movementItems + scheduledItems).sortedWith(
      compareByDescending<PrisonerTimelineItemDto> { it.eventDateTime }
        .thenByDescending { it.itemType == TimelineItemType.PRISONER_MOVEMENT },
    )
  }

  /** When the container was last touched - the most recent event, falling back to its creation time. */
  private fun PropertyContainer.lastUpdated() = events.maxOfOrNull { it.eventDateTime } ?: createDateTime

  private fun Prisoner?.fullName(): String? = this?.let {
    listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null }
  }

  /**
   * The prisoner's movement status, or null if the prisoner could not be resolved: in transit between prisons
   * (prisonId TRN, lastMovementTypeCode TRN), released (prisonId OUT, lastMovementTypeCode REL), else held in an
   * establishment.
   */
  private fun Prisoner?.movementStatus(): PrisonerMovementStatus? = this?.let {
    when {
      prisonId == TRANSIT_PRISON_ID && lastMovementTypeCode == TRANSIT_MOVEMENT_TYPE -> PrisonerMovementStatus.IN_TRANSIT
      prisonId == RELEASED_PRISON_ID && lastMovementTypeCode == RELEASED_MOVEMENT_TYPE -> PrisonerMovementStatus.RELEASED
      else -> PrisonerMovementStatus.IN_ESTABLISHMENT
    }
  }

  /** The count for a status from a [countContainersByStatus] map, as an Int (0 when absent). */
  private fun Map<ContainerStatus, Long>.count(status: ContainerStatus): Int = this[status]?.toInt() ?: 0

  /** The ids of the given property locations whose code, local name or path hierarchy match [term] (case-insensitive). */
  private fun resolveLocationIds(propertyLocations: List<PropertyLocation>, term: String): List<UUID> = propertyLocations
    .filter {
      it.code.equals(term, ignoreCase = true) ||
        it.localName.equals(term, ignoreCase = true) ||
        it.pathHierarchy.equals(term, ignoreCase = true)
    }
    .map { it.id }

  private companion object {
    /** The storage-location search term that means "held offsite at Branston" rather than an internal code. */
    const val BRANSTON_SEARCH_TERM = "BRANSTON"

    /** prisoner-search prisonId + lastMovementTypeCode values that mean the prisoner is in transit between prisons. */
    const val TRANSIT_PRISON_ID = "TRN"
    const val TRANSIT_MOVEMENT_TYPE = "TRN"

    /** prisoner-search prisonId + lastMovementTypeCode values that mean the prisoner has been released. */
    const val RELEASED_PRISON_ID = "OUT"
    const val RELEASED_MOVEMENT_TYPE = "REL"
  }
}
