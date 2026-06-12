package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "A sealed container of a prisoner's property")
data class PropertyContainerDto(
  @Schema(description = "Unique id of the container", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d")
  val id: UUID,

  @Schema(description = "Prisoner (NOMS) number the property belongs to", example = "A1234BC")
  val prisonerNumber: String,

  @Schema(description = "Id of the prison holding the container", example = "LEI")
  val prisonId: String,

  @Schema(description = "Type of container", example = "STANDARD")
  val containerType: ContainerType,

  @Schema(description = "Current seal number, derived from the most recent seal event", example = "SEAL12345", nullable = true)
  val currentSealNumber: String?,

  @Schema(description = "Current status, derived from the most recent event", example = "STORED")
  val currentStatus: ContainerStatus,

  @Schema(description = "Current internal location id, derived from the most recent move", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val currentLocation: UUID?,

  @Schema(description = "When the container was created")
  val createDateTime: LocalDateTime,

  @Schema(description = "Id of the user who created the container", example = "AUSER_GEN")
  val createdByUserId: String,
) {
  companion object {
    fun from(container: PropertyContainer) = PropertyContainerDto(
      id = container.id!!,
      prisonerNumber = container.prisonerNumber,
      prisonId = container.prisonId,
      containerType = container.containerType,
      currentSealNumber = container.currentSealNumber(),
      currentStatus = container.currentStatus(),
      currentLocation = container.currentLocation(),
      createDateTime = container.createDateTime,
      createdByUserId = container.createdByUserId,
    )
  }
}
