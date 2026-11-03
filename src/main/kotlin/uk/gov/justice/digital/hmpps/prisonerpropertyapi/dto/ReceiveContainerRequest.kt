package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.util.UUID

@Schema(description = "Confirm receipt of an existing incoming property container")
data class ReceiveContainerRequest(
  @Schema(description = "Id of the establishment confirming receipt", example = "MDI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val receivingPrisonId: String,

  @Schema(description = "Current seal number on the arriving container", example = "SN8842K1", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val sealNumber: String,

  @Schema(description = "Internal storage location at the receiving establishment, if known", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val internalLocationId: UUID? = null,

  @Schema(description = "Storage location type: INTERNAL is implied by internalLocationId; BRANSTON requires no internal location", example = "INTERNAL", nullable = true)
  val locationType: StorageLocationType? = null,
)
