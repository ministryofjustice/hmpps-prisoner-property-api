package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A prison (agency) and whether the property service is switched on for it")
data class AgencyStatusDto(
  @Schema(description = "Agency (prison) id", example = "MDI")
  val agencyId: String,

  @Schema(description = "Agency (prison) name", example = "Moorland (HMP & YOI)")
  val name: String,

  @Schema(description = "Whether the property service is active for this agency", example = "true")
  val active: Boolean,
)
