package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonerPropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
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

  @Transactional(readOnly = true)
  fun getByPrisonId(prisonId: String): List<PropertyContainerDto> = repository.findByPrisonIdAndArchivedFalse(prisonId).map(PropertyContainerDto::from)

  @Transactional(readOnly = true)
  fun getById(id: UUID): PropertyContainerDto = repository.findById(id)
    .map(PropertyContainerDto::from)
    .orElseThrow { PropertyContainerNotFoundException(id) }

  /** When the container was last touched - the most recent event, falling back to its creation time. */
  private fun PropertyContainer.lastUpdated() = events.maxOfOrNull { it.eventDateTime } ?: createDateTime

  private fun Prisoner?.fullName(): String? = this?.let {
    listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null }
  }
}
