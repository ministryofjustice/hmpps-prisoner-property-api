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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonPropertyFilter
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyGroupDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyEventDto
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
        locationDescription = container.currentLocation()?.let { locations[it]?.displayName() },
        inPrisonersCurrentPrison = prisoner?.prisonId == container.prisonId,
      )
    }
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
    containerType: ContainerType? = null,
    statuses: List<ContainerStatus> = emptyList(),
    storageLocation: String? = null,
    pageable: Pageable,
  ): Page<PrisonerPropertyGroupDto> {
    val branstonOnly = storageLocation.equals(BRANSTON_SEARCH_TERM, ignoreCase = true)
    val locationIds = if (storageLocation != null && !branstonOnly) {
      locationsClient.getLocationsByType(prisonId, BOX_LOCATION_TYPE)
        .filter {
          it.code.equals(storageLocation, ignoreCase = true) ||
            it.localName.equals(storageLocation, ignoreCase = true) ||
            it.pathHierarchy.equals(storageLocation, ignoreCase = true)
        }
        .map { it.id }
    } else {
      null
    }
    val filter = PrisonPropertyFilter(prisonerNumber, sealNumber, containerType, statuses, locationIds, branstonOnly)

    val prisonerPage = repository.findPrisonerNumbersPage(prisonId, filter, pageable)
    if (prisonerPage.isEmpty) return PageImpl(emptyList(), pageable, prisonerPage.totalElements)

    val containersByPrisoner = repository.findContainers(prisonId, filter, prisonerPage.content).groupBy { it.prisonerNumber }
    val prisoners = prisonerSearchClient.getPrisoners(prisonerPage.content)
    val prisonNames = prisonRegisterClient.getPrisonNames()
    val locations = locationsClient.getLocations(containersByPrisoner.values.flatten().mapNotNull { it.currentInternalLocationId })

    val groups = prisonerPage.content.map { number ->
      val prisoner = prisoners[number]
      PrisonerPropertyGroupDto(
        prisonerNumber = number,
        prisonerName = prisoner.fullName(),
        prisonerCurrentPrisonId = prisoner?.prisonId,
        prisonerCurrentPrisonName = prisoner?.prisonId?.let { prisonNames[it] },
        containers = (containersByPrisoner[number] ?: emptyList()).map { container ->
          PrisonerPropertyContainerDto.fromColumns(
            container = container,
            prisonerName = prisoner.fullName(),
            prisonName = prisonNames[container.prisonId],
            locationDescription = container.currentInternalLocationId?.let { locations[it]?.displayName() },
            inPrisonersCurrentPrison = prisoner?.prisonId == container.prisonId,
          )
        },
      )
    }
    return PageImpl(groups, pageable, prisonerPage.totalElements)
  }

  @Transactional(readOnly = true)
  fun getById(id: UUID): PropertyContainerDto = repository.findById(id)
    .map(PropertyContainerDto::from)
    .orElseThrow { PropertyContainerNotFoundException(id) }

  /** A container's full history, newest event first. Throws [PropertyContainerNotFoundException] if the container does not exist. */
  @Transactional(readOnly = true)
  fun getEvents(id: UUID): List<PropertyEventDto> = repository.findById(id)
    .orElseThrow { PropertyContainerNotFoundException(id) }
    .events
    .sortedByDescending { it.eventDateTime }
    .map(PropertyEventDto::from)

  /** When the container was last touched - the most recent event, falling back to its creation time. */
  private fun PropertyContainer.lastUpdated() = events.maxOfOrNull { it.eventDateTime } ?: createDateTime

  private fun Prisoner?.fullName(): String? = this?.let {
    listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null }
  }

  private companion object {
    /** The storage-location search term that means "held offsite at Branston" rather than an internal code. */
    const val BRANSTON_SEARCH_TERM = "BRANSTON"

    /** The locations-inside-prison location type property is stored in - the only type the search resolves against. */
    const val BOX_LOCATION_TYPE = "BOX"
  }
}
