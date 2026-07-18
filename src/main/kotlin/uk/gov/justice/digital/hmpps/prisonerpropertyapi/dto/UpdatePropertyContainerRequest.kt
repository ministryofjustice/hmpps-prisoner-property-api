package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.time.LocalDate
import java.util.UUID

/**
 * The mutable details of a property container. A full replace: the container's type, seal, location
 * and proposed disposal date are set to the supplied values, and any change is recorded in history.
 */
@Schema(description = "Updated details for a property container")
data class UpdatePropertyContainerRequest(
  @Schema(description = "Type of container", example = "STANDARD", requiredMode = Schema.RequiredMode.REQUIRED)
  val containerType: ContainerType,

  @Schema(description = "Seal number identifying the container", example = "SN8842K1", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val sealNumber: String,

  @Schema(description = "Internal storage location id (locations-inside-prison-api), if known", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val internalLocationId: UUID? = null,

  @Schema(
    description = "Where the container is stored: INTERNAL (a prison location, given by internalLocationId) or " +
      "BRANSTON (the offsite warehouse, no internal location). Send BRANSTON to move excess property offsite. " +
      "Leave null (with no internalLocationId) to leave the storage location unchanged.",
    example = "INTERNAL",
    nullable = true,
  )
  val locationType: StorageLocationType? = null,

  @Schema(description = "Date the container is proposed to be disposed of, if any", example = "2026-09-01", nullable = true)
  val proposedDisposalDate: LocalDate? = null,
)
