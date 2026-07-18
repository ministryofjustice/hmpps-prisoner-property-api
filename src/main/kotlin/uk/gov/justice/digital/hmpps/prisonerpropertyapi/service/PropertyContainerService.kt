package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonApiClient
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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.MovementKind
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonPropertySummaryDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyGroupDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertySummaryDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerTimelineItemDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyEventDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertySystem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.TimelineItemType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Service
class PropertyContainerService(
  private val repository: PropertyContainerRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val prisonRegisterClient: PrisonRegisterClient,
  private val locationsClient: LocationsClient,
  private val prisonApiClient: PrisonApiClient,
  private val activeAgenciesService: ActiveAgenciesService,
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
    val containers = repository.findByPrisonerNumber(prisonerNumber)
      .filter { statuses.isEmpty() || it.currentStatus() in statuses }
      .sortedWith(compareBy { it.lastUpdated() })
      .let { if (sortDirection.isDescending) it.reversed() else it }

    if (containers.isEmpty()) return emptyList()

    val prisoner = prisonerSearchClient.getPrisoner(prisonerNumber)
    val prisonNames = prisonRegisterClient.getPrisonNames()
    val locations = locationsClient.getLocations(containers.mapNotNull { it.currentLocation() })

    // Surface stored property as due for return from a day before the prisoner's confirmed release date, so
    // staff can prepare it ahead of release. Uses the confirmed date only (not the sentence-calculated one,
    // which can move) and stops once actually released, when the real release event has already flagged it.
    // A read-time display hint on this person view only - it does not change the stored status or the
    // establishment list/summary counts.
    val dueForReturnSoon = prisoner != null &&
      prisoner.movementStatus() != PrisonerMovementStatus.RELEASED &&
      prisoner.confirmedReleaseDate?.let { !it.isAfter(LocalDate.now().plusDays(1)) } == true

    return containers.map { container ->
      val dto = PrisonerPropertyContainerDto.from(
        container = container,
        prisonerName = prisoner.fullName(),
        prisonName = prisonNames[container.prisonId],
        prisonerCurrentPrisonId = prisoner?.prisonId,
        prisonerCurrentPrisonName = prisoner?.prisonId?.let { prisonNames[it] },
        prisonerMovementStatus = prisoner.movementStatus(),
        locationDescription = container.currentLocation()?.let { locations[it]?.displayName() },
        inPrisonersCurrentPrison = prisoner?.prisonId == container.prisonId,
      )
      if (dueForReturnSoon && dto.currentStatus == ContainerStatus.STORED) {
        dto.copy(currentStatus = ContainerStatus.DUE_FOR_RETURN)
      } else {
        dto
      }
    }
  }

  /**
   * A single prisoner's property totals for the profile "Property containers" tile. Counts are over the
   * prisoner's active (not removed) containers, split by where they are held relative to their current
   * establishment (from prisoner-search; null when released or in transit). Derived in code from the
   * container set - it is small per prisoner - so no dedicated count query is needed. [hasEverHadProperty]
   * covers every recorded container (including ones since returned/disposed/transferred) so the tile can
   * tell "never had property" apart from "no longer has any".
   */
  @Transactional(readOnly = true)
  fun getPrisonerPropertySummary(prisonerNumber: String): PrisonerPropertySummaryDto {
    val containers = repository.findByPrisonerNumber(prisonerNumber)
    val prisoner = prisonerSearchClient.getPrisoner(prisonerNumber)
    // The prisoner's real establishment: prisoner-search reports TRN/OUT while in transit or released - treat
    // those as no current establishment, so all their property counts as held "in other establishments".
    val currentEstablishmentId = prisoner?.prisonId?.takeUnless { it == TRANSIT_PRISON_ID || it == RELEASED_PRISON_ID }

    val active = containers.filterNot { it.isRemoved() }
    val (here, elsewhere) = active.partition { currentEstablishmentId != null && it.prisonId == currentEstablishmentId }

    return PrisonerPropertySummaryDto(
      currentEstablishmentId = currentEstablishmentId,
      currentEstablishmentName = currentEstablishmentId?.let { prisonRegisterClient.getPrisonNames()[it] },
      heldInCurrentEstablishment = here.size,
      heldInOtherEstablishments = elsewhere.size,
      dueForTransferIn = elsewhere.count { currentEstablishmentId != null && it.receivingPrisonId == currentEstablishmentId },
      dueForTransferOut = here.count { it.currentStatus() == ContainerStatus.DUE_FOR_TRANSFER_OUT },
      overdueForDisposal = active.count { it.isDisposalDue() },
      overdueForReturn = active.count { it.currentStatus() == ContainerStatus.DUE_FOR_RETURN },
      hasEverHadProperty = containers.isNotEmpty(),
    )
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
    includeTransferIn: Boolean = false,
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
      includeTransferIn = includeTransferIn,
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
   * A prisoner's whole-property history: every event across all of their containers, interleaved
   * newest first, plus a de-duplicated "arrived at ..." item for each prison the prisoner moved into and a
   * "property management started in DPS at ..." marker for each establishment they have held property at that is
   * switched on in DPS. Prison and location ids are resolved to names, and each container event carries the seal
   * number and acting establishment as at that point in the container's history - both carried forward through
   * the events, so a transfer that reassigns the container's current prison does not relabel its earlier events.
   * Returns an empty list if the prisoner has no property.
   */
  @Transactional(readOnly = true)
  fun getPrisonerTimeline(prisonerNumber: String): List<PrisonerTimelineItemDto> {
    val containers = repository.findByPrisonerNumber(prisonerNumber)
    val prisonNames = prisonRegisterClient.getPrisonNames()
    val prisoner = prisonerSearchClient.getPrisoner(prisonerNumber)
    val prisonerName = prisoner.fullName()
    val locations = if (containers.isEmpty()) emptyMap() else locationsClient.getLocations(containers.mapNotNull { it.currentLocation() })

    val containerItems = containers.flatMap { container ->
      val locationDescription = container.currentLocation()?.let { locations[it]?.displayName() }
      var sealAsOfEvent: String? = null
      // The prison holding the container as at each event, walked forward in time (like sealAsOfEvent): a
      // CREATED_SEALED records the origin (toPrisonId) and each TRANSFERRED moves it on, so a historical event
      // keeps the establishment it actually happened at even after a transfer reassigns the container's current
      // prisonId. Falls back to the current prisonId only when no event carries a prison (never-transferred or
      // legacy data, where current == origin).
      var heldPrisonId: String? = null
      container.events.sortedBy { it.eventDateTime }.map { event ->
        event.sealNumber?.let { sealAsOfEvent = it }
        (if (event.eventType == PropertyEventType.CREATED_SEALED) event.toPrisonId else event.fromPrisonId)?.let { heldPrisonId = it }
        val actingEstablishmentName = prisonNames[heldPrisonId ?: container.prisonId]
        if (event.eventType == PropertyEventType.TRANSFERRED) event.toPrisonId?.let { heldPrisonId = it }
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

    // When each active establishment was switched on in DPS: used both to label arrivals DPS-vs-NOMIS and to
    // build the DPS-first-used markers. Fetched once and shared by both.
    val rolloutDates = activeAgenciesService.getActiveAgencyRolloutDates()

    // Admission / transfer-in items are assembled at read time from prison-api's movement history, so they show
    // even for a prisoner with no property. Best-effort: a prison-api failure yields no movement items rather
    // than failing the whole timeline.
    val movementItems = buildMovementItems(prisonerNumber, prisonerName, prisonNames, rolloutDates)

    // A forward-looking "scheduled for release" marker, derived from the prisoner's release dates: prefer the
    // confirmed date, fall back to the conditional (sentence-calculated) one. Only meaningful when there is
    // property to return, and suppressed once the prisoner has actually been released (the real release is
    // already recorded as a PRISONER_RELEASED event).
    val releaseDate = prisoner?.confirmedReleaseDate ?: prisoner?.conditionalReleaseDate
    val scheduledItems = if (containers.isNotEmpty() && releaseDate != null && prisoner.movementStatus() != PrisonerMovementStatus.RELEASED) {
      listOf(PrisonerTimelineItemDto.scheduledForRelease(prisonerNumber, prisonerName, releaseDate))
    } else {
      emptyList()
    }

    // One "property management started in DPS" marker per establishment the prisoner has held property at,
    // derived at read time from when that prison was switched on in DPS.
    val dpsRolloutItems = buildDpsRolloutItems(containers, prisonNames, rolloutDates)

    // Newest first; at the same instant a movement sits above the container events it triggered.
    return (containerItems + movementItems + scheduledItems + dpsRolloutItems).sortedWith(
      compareByDescending<PrisonerTimelineItemDto> { it.eventDateTime }
        .thenByDescending { it.itemType == TimelineItemType.PRISONER_MOVEMENT },
    )
  }

  /**
   * A "property management started in DPS at ..." item for each establishment the prisoner has held property
   * at (from their container history) that is currently switched on in DPS, placed at that prison's rollout
   * date. An establishment-level fact derived at read time from the active-agency rows - no stored event.
   */
  private fun buildDpsRolloutItems(
    containers: List<PropertyContainer>,
    prisonNames: Map<String, String>,
    rolloutDates: Map<String, LocalDateTime>,
  ): List<PrisonerTimelineItemDto> {
    if (containers.isEmpty()) return emptyList()
    val heldPrisonIds = containers.flatMapTo(mutableSetOf()) { container ->
      container.events.flatMap { listOfNotNull(it.fromPrisonId, it.toPrisonId) } + container.prisonId
    }
    return heldPrisonIds.mapNotNull { prisonId ->
      rolloutDates[prisonId]?.let { rolloutAt ->
        PrisonerTimelineItemDto.dpsFirstUsed(prisonId, prisonNames[prisonId], rolloutAt)
      }
    }
  }

  /**
   * Admission and transfer-in movement items from prison-api's prison-timeline. Per booking, each ADM movement
   * becomes an "admitted to" item and each transfer a "transferred in to" item; TAP (temporary-absence returns)
   * are skipped. Each arrival is labelled with the receiving establishment's property system at that date (see
   * [propertySystemAt]). Degrades to an empty list on any prison-api failure so the timeline never fails.
   */
  private fun buildMovementItems(
    prisonerNumber: String,
    prisonerName: String?,
    prisonNames: Map<String, String>,
    rolloutDates: Map<String, LocalDateTime>,
  ): List<PrisonerTimelineItemDto> {
    val summary = runCatching { prisonApiClient.getPrisonTimeline(prisonerNumber) }
      .onFailure { log.warn("prison-api timeline lookup failed for {}, omitting movement items", prisonerNumber, it) }
      .getOrNull() ?: return emptyList()

    return summary.prisonPeriod.flatMap { period ->
      val admissions = period.movementDates
        .filter { it.inwardType == ADMISSION_MOVEMENT_TYPE && it.admittedIntoPrisonId != null && it.dateInToPrison != null }
        .map {
          PrisonerTimelineItemDto.prisonerMovement(
            kind = MovementKind.ADMISSION,
            prisonerNumber = prisonerNumber,
            prisonerName = prisonerName,
            dateInToPrison = it.dateInToPrison!!,
            toPrisonId = it.admittedIntoPrisonId!!,
            toPrisonName = prisonNames[it.admittedIntoPrisonId],
            propertySystem = propertySystemAt(it.admittedIntoPrisonId, it.dateInToPrison, rolloutDates),
          )
        }
      val transfersIn = period.transfers
        .filter { it.toPrisonId != null && it.dateInToPrison != null }
        .map {
          PrisonerTimelineItemDto.prisonerMovement(
            kind = MovementKind.TRANSFER_IN,
            prisonerNumber = prisonerNumber,
            prisonerName = prisonerName,
            dateInToPrison = it.dateInToPrison!!,
            toPrisonId = it.toPrisonId!!,
            toPrisonName = prisonNames[it.toPrisonId],
            propertySystem = propertySystemAt(it.toPrisonId, it.dateInToPrison, rolloutDates),
          )
        }
      admissions + transfersIn
    }
  }

  /**
   * The property system in force at [prisonId] on [dateInToPrison]: DPS if the prison was switched on in DPS
   * on or before that date (rollout date on/before the arrival), NOMIS otherwise (arrived before rollout, or
   * the prison is not on DPS). Relies on prisons not being switched off once on - see the DPS/NOMIS-variant
   * decision - so the single rollout date is a reliable DPS-since boundary.
   */
  private fun propertySystemAt(
    prisonId: String,
    dateInToPrison: LocalDateTime,
    rolloutDates: Map<String, LocalDateTime>,
  ): PropertySystem = rolloutDates[prisonId]
    ?.takeIf { !dateInToPrison.isBefore(it) }
    ?.let { PropertySystem.DPS }
    ?: PropertySystem.NOMIS

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
    private val log = org.slf4j.LoggerFactory.getLogger(this::class.java)

    /** The storage-location search term that means "held offsite at Branston" rather than an internal code. */
    const val BRANSTON_SEARCH_TERM = "BRANSTON"

    /** prison-api SignificantMovement.inwardType for a genuine admission into custody (vs TAP, a temporary-absence return). */
    const val ADMISSION_MOVEMENT_TYPE = "ADM"

    /** prisoner-search prisonId + lastMovementTypeCode values that mean the prisoner is in transit between prisons. */
    const val TRANSIT_PRISON_ID = "TRN"
    const val TRANSIT_MOVEMENT_TYPE = "TRN"

    /** prisoner-search prisonId + lastMovementTypeCode values that mean the prisoner has been released. */
    const val RELEASED_PRISON_ID = "OUT"
    const val RELEASED_MOVEMENT_TYPE = "REL"
  }
}
