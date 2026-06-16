package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.util.UUID

/**
 * A move of a container to an internal prison location or offsite to the Branston warehouse. For an
 * INTERNAL move an [internalLocationId] is required; for a BRANSTON move it must be omitted.
 */
@Schema(description = "Details for moving a property container")
data class MoveContainerRequest(
  @Schema(description = "Where the container is being moved to - an INTERNAL prison location or the offsite BRANSTON warehouse", example = "BRANSTON", requiredMode = Schema.RequiredMode.REQUIRED)
  val locationType: StorageLocationType,

  @Schema(description = "Internal storage location id (locations-inside-prison-api) - required for an INTERNAL move, omitted for BRANSTON", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val internalLocationId: UUID? = null,
)
