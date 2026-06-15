package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** Whether a sync/migrate upsert created a new DPS container or updated an existing one. */
enum class SyncMappingType { CREATED, UPDATED }

@Schema(description = "The result of a property container sync/migrate upsert")
data class SyncPropertyContainerResponse(
  @Schema(description = "The DPS container id - newly minted when CREATED, the existing id when UPDATED", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d")
  val dpsId: UUID,

  @Schema(description = "The NOMIS PROPERTY_CONTAINER_ID, echoed back so the caller can persist the mapping", example = "123456")
  val nomisPropertyContainerId: Long,

  @Schema(description = "Whether a new DPS container was created or an existing one updated", example = "CREATED")
  val mappingType: SyncMappingType,
)
