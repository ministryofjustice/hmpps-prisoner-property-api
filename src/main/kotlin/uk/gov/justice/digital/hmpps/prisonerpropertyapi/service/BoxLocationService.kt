package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.BoxLocationDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.BoxLocationSort

@Service
class BoxLocationService(
  private val repository: PropertyContainerRepository,
  private val locationsClient: LocationsClient,
) {

  /**
   * The BOX locations available in a prison, each annotated with how many containers are currently held
   * there, so a user can pick a suitable place to store property. Empty boxes are included (count 0).
   * Sorted alphabetically by name, or - with [BoxLocationSort.FEWEST_CONTAINERS] - emptiest first.
   */
  @Transactional(readOnly = true)
  fun getBoxLocations(prisonId: String, sort: BoxLocationSort = BoxLocationSort.NAME): List<BoxLocationDto> {
    val boxes = locationsClient.getLocationsByType(prisonId, BOX)
    // The denormalised current_internal_location_id is the box id while a container is physically present,
    // and null once removed or held offsite - so this counts the containers actually in each box without
    // loading any events.
    val countsByLocation = repository.countContainersByLocation(prisonId)
      .associate { it.locationId to it.count.toInt() }

    val rows = boxes.map { BoxLocationDto.from(it, countsByLocation[it.id] ?: 0) }
    return when (sort) {
      BoxLocationSort.NAME -> rows.sortedBy { it.name.lowercase() }
      BoxLocationSort.FEWEST_CONTAINERS -> rows.sortedWith(compareBy({ it.containerCount }, { it.name.lowercase() }))
    }
  }

  private companion object {
    private const val BOX = "BOX"
  }
}
