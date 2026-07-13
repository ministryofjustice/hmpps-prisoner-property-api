package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PropertyLocation
import java.util.UUID

@Schema(description = "Request to create a new property storage location for a prison")
data class CreatePropertyLocationRequest(
  @param:Schema(description = "Name to display for the location", example = "Reception property store", required = true)
  @field:NotBlank(message = "Enter a name for the storage location")
  @field:Size(min = 1, max = 80, message = "Name must be between 1 and 80 characters")
  val localName: String,

  @param:Schema(description = "How many property containers this location can hold", example = "10", required = true)
  @field:Min(value = 0, message = "Capacity cannot be negative")
  val capacity: Int,
)

@Schema(description = "Request to update a property storage location's name and/or capacity")
data class UpdatePropertyLocationRequest(
  @param:Schema(description = "New name to display for the location", example = "Reception property store", required = false)
  @field:Size(min = 1, max = 80, message = "Name must be between 1 and 80 characters")
  val localName: String? = null,

  @param:Schema(description = "New number of property containers this location can hold", example = "12", required = false)
  @field:Min(value = 0, message = "Capacity cannot be negative")
  val capacity: Int? = null,
)

@Schema(description = "A property storage location for the management screens, with its capacity and how full it is")
data class PropertyLocationAdminDto(
  @param:Schema(description = "Location id", example = "2475f250-434a-4257-afe7-b911f1773a4d")
  val id: UUID,

  @param:Schema(description = "Prison id", example = "MDI")
  val prisonId: String,

  @param:Schema(description = "Location code", example = "PROP1")
  val code: String,

  @param:Schema(description = "Name to display for the location", example = "Reception property store")
  val name: String,

  @param:Schema(description = "Location type", example = "BOX", required = false)
  val locationType: String? = null,

  @param:Schema(description = "How many property containers this location can hold", example = "10")
  val capacity: Int,

  @param:Schema(description = "How many containers are currently held here", example = "3")
  val containersHeld: Int,

  @param:Schema(description = "How many more containers can be stored here (capacity minus held, never below zero)", example = "7")
  val availableSpaces: Int,
) {
  companion object {
    fun from(location: PropertyLocation, containersHeld: Int): PropertyLocationAdminDto {
      val capacity = location.capacity ?: 0
      return PropertyLocationAdminDto(
        id = location.id,
        prisonId = location.prisonId,
        code = location.code,
        name = location.displayName(),
        locationType = location.locationType,
        capacity = capacity,
        containersHeld = containersHeld,
        availableSpaces = (capacity - containersHeld).coerceAtLeast(0),
      )
    }
  }
}
