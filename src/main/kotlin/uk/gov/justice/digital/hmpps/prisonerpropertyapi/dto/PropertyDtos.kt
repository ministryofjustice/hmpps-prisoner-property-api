package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyItem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyStatus
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "Request to record a new item of prisoner property")
data class CreatePropertyItemRequest(
  @field:NotBlank
  @field:Size(max = 240)
  @Schema(description = "Free-text description of the item", example = "Black leather wallet")
  val description: String,

  @field:NotBlank
  @field:Size(max = 40)
  @Schema(description = "Where the item is held", example = "RECEPTION")
  val location: String,
)

@Schema(description = "An item of prisoner property")
data class PropertyItemDto(
  val id: UUID,
  val prisonerNumber: String,
  val description: String,
  val location: String,
  val status: PropertyStatus,
  val createdAt: LocalDateTime,
  val updatedAt: LocalDateTime,
) {
  companion object {
    fun from(item: PropertyItem) = PropertyItemDto(
      id = item.id,
      prisonerNumber = item.prisonerNumber,
      description = item.description,
      location = item.location,
      status = item.status,
      createdAt = item.createdAt,
      updatedAt = item.updatedAt,
    )
  }
}
