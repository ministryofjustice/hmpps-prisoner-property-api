package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyLocationRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyLocationAdminDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.UpdatePropertyLocationRequest
import java.util.UUID

/**
 * Backs the property-location management screens: list, add, rename/re-capacity and remove the storage
 * locations a prison can hold property in. All mutations proxy to locations-inside-prison-api (via
 * [LocationsClient]); reads are live, so a change is seen immediately on the next read regardless of which
 * pod serves it. Removal is guarded here because this service owns the container data needed to tell whether
 * a location is still in use.
 */
@Service
class PropertyLocationAdminService(
  private val locationsClient: LocationsClient,
  private val repository: PropertyContainerRepository,
) {

  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  /**
   * Every property storage location in a prison (including full ones), with capacity and how full it is.
   */
  @Transactional(readOnly = true)
  fun listPropertyLocations(prisonId: String): List<PropertyLocationAdminDto> {
    val countsByLocation = repository.countContainersByLocation(prisonId)
      .associate { it.locationId to it.count.toInt() }
    return locationsClient.getPropertyLocations(prisonId)
      .map { PropertyLocationAdminDto.from(it, countsByLocation[it.id] ?: 0) }
      .sortedBy { it.name.lowercase() }
  }

  fun createPropertyLocation(prisonId: String, request: CreatePropertyLocationRequest): PropertyLocationAdminDto {
    val created = locationsClient.createPropertyLocation(prisonId, request)
    log.info("Created property location {} in {}", created.id, prisonId)
    return PropertyLocationAdminDto.from(created, containersHeld = 0)
  }

  /**
   * Rename and/or re-capacity a location. Capacity may not be set below the number of containers already
   * stored there (that would leave it over capacity); this is checked here, before the downstream update,
   * since this service owns the container count.
   */
  fun updatePropertyLocation(id: UUID, request: UpdatePropertyLocationRequest): PropertyLocationAdminDto {
    val held = repository.countContainersInLocation(id, null)
    if (request.capacity != null && request.capacity < held) {
      throw PropertyLocationCapacityBelowUsageException(id, request.capacity, held)
    }
    val updated = locationsClient.updatePropertyLocation(id, request)
    log.info("Updated property location {}", id)
    return PropertyLocationAdminDto.from(updated, containersHeld = held.toInt())
  }

  /**
   * Remove a location as a place property can be stored. Rejected if it still holds any container, since
   * dropping the designation would orphan that property.
   */
  fun removePropertyLocation(id: UUID): PropertyLocationAdminDto {
    val held = repository.countContainersInLocation(id, null)
    if (held > 0) throw PropertyLocationInUseException(id, held)
    val removed = locationsClient.removePropertyLocation(id)
    log.info("Removed property designation from location {}", id)
    return PropertyLocationAdminDto.from(removed, containersHeld = 0)
  }
}
