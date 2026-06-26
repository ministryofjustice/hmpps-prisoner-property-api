package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationDetail
import java.util.UUID

@Schema(description = "A property box location within a prison and how many containers it currently holds")
data class BoxLocationDto(
  @Schema(description = "Id of the box location (locations-inside-prison-api)", example = "11111111-1111-1111-1111-111111111111")
  val id: UUID,

  @Schema(description = "Id of the prison the box is in", example = "LEI")
  val prisonId: String,

  @Schema(description = "Location code", example = "PROP1")
  val code: String,

  @Schema(description = "Local (display) name of the box, if set", example = "Reception Property Store", nullable = true)
  val localName: String?,

  @Schema(description = "Full path of the box within the prison", example = "RECP-PROP1")
  val pathHierarchy: String,

  @Schema(description = "Human-friendly name, preferring the local name and falling back to the path hierarchy", example = "Reception Property Store")
  val name: String,

  @Schema(description = "Number of property containers currently held in this box", example = "3")
  val containerCount: Int,
) {
  companion object {
    fun from(location: LocationDetail, containerCount: Int) = BoxLocationDto(
      id = location.id,
      prisonId = location.prisonId,
      code = location.code,
      localName = location.localName,
      pathHierarchy = location.pathHierarchy,
      name = location.displayName(),
      containerCount = containerCount,
    )
  }
}

@Schema(description = "How to order the box locations returned for a prison")
enum class BoxLocationSort {
  @Schema(description = "Alphabetically by name (default)")
  NAME,

  @Schema(description = "Emptiest boxes first (fewest containers), then by name")
  FEWEST_CONTAINERS,
}
