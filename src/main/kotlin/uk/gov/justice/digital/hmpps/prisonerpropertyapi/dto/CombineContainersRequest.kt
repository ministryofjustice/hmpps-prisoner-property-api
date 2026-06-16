package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.util.UUID

/**
 * Combine the property of two or more source containers into a single new sealed container. The source
 * containers must all belong to the same prisoner and prison; the new container inherits both. The
 * sources are removed from active storage (COMBINED) and the new container holds the combined property.
 */
@Schema(description = "Details for combining containers into a new sealed container")
data class CombineContainersRequest(
  @Schema(description = "Ids of the source containers to combine (at least two)", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:Size(min = 2, message = "At least two source containers are required to combine")
  val sourceContainerIds: List<UUID>,

  @Schema(description = "Type of the new container", example = "STANDARD", requiredMode = Schema.RequiredMode.REQUIRED)
  val containerType: ContainerType,

  @Schema(description = "Seal number for the new container", example = "SN8842K1", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val sealNumber: String,

  @Schema(description = "Internal storage location id of the new container (locations-inside-prison-api), if held internally", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val internalLocationId: UUID? = null,

  @Schema(description = "Storage location type of the new container; defaults to INTERNAL when an internal location id is given", example = "INTERNAL", nullable = true)
  val locationType: StorageLocationType? = null,
)
