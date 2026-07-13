package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PropertyLocation
import java.util.UUID

@Schema(description = "A property storage location within a prison, its capacity and how many containers it currently holds")
data class BoxLocationDto(
  @Schema(description = "Id of the storage location (locations-inside-prison-api)", example = "11111111-1111-1111-1111-111111111111")
  val id: UUID,

  @Schema(description = "Id of the prison the location is in", example = "LEI")
  val prisonId: String,

  @Schema(description = "Location code", example = "PROP1")
  val code: String,

  @Schema(description = "Local (display) name of the location, if set", example = "Reception Property Store", nullable = true)
  val localName: String?,

  @Schema(description = "Full path of the location within the prison", example = "RECP-PROP1")
  val pathHierarchy: String,

  @Schema(description = "Human-friendly name, preferring the local name and falling back to the path hierarchy", example = "Reception Property Store")
  val name: String,

  @Schema(description = "Number of property containers currently held in this location", example = "3")
  val containerCount: Int,

  @Schema(description = "How many property containers this location can hold (capacity of its PROPERTY usage)", example = "10")
  val capacity: Int,

  @Schema(description = "Remaining spaces for property containers (capacity minus the containers currently held)", example = "7")
  val availableSpaces: Int,
) {
  companion object {
    fun from(location: PropertyLocation, containerCount: Int): BoxLocationDto {
      val capacity = location.capacity ?: 0
      return BoxLocationDto(
        id = location.id,
        prisonId = location.prisonId,
        code = location.code,
        localName = location.localName,
        pathHierarchy = location.pathHierarchy,
        name = location.displayName(),
        containerCount = containerCount,
        capacity = capacity,
        availableSpaces = (capacity - containerCount).coerceAtLeast(0),
      )
    }
  }
}

@Schema(description = "How to order the storage locations returned for a prison")
enum class BoxLocationSort {
  @Schema(description = "Alphabetically by name (default)")
  NAME,

  @Schema(description = "Most available spaces first, then by name")
  MOST_AVAILABLE,
}
