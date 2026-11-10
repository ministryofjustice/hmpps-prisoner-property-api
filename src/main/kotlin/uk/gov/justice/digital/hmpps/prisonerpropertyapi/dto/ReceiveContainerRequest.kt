package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PRISON_ID_MESSAGE
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PRISON_ID_REGEX
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PRISONER_NUMBER_MESSAGE
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PRISONER_NUMBER_REGEX
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.util.UUID

@Schema(description = "Confirm receipt of an existing incoming property container")
data class ReceiveContainerRequest(
  @Schema(description = "Prisoner (NOMS) number the container must belong to", example = "A1234BC", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:Pattern(regexp = PRISONER_NUMBER_REGEX, message = PRISONER_NUMBER_MESSAGE)
  val prisonerNumber: String,

  @Schema(description = "Id of the establishment confirming receipt", example = "MDI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  @field:Pattern(regexp = PRISON_ID_REGEX, message = PRISON_ID_MESSAGE)
  val receivingPrisonId: String,

  @Schema(description = "Current seal number on the arriving container", example = "SN8842K1", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val sealNumber: String,

  @Schema(description = "Internal storage location at the receiving establishment, if known", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val internalLocationId: UUID? = null,

  @Schema(description = "Storage location type: INTERNAL is implied by internalLocationId; BRANSTON requires no internal location", example = "INTERNAL", nullable = true)
  val locationType: StorageLocationType? = null,
)
