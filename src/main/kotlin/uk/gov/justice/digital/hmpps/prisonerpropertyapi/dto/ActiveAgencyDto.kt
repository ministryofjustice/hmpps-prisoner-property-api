package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ActiveAgency

@Schema(description = "Whether the property service is switched on for an agency (prison)")
data class ActiveAgencyDto(
  @Schema(description = "Agency (prison) id", example = "MDI")
  val agencyId: String,

  @Schema(description = "Whether the property service is active for this agency", example = "true")
  val active: Boolean,
) {
  companion object {
    fun from(agency: ActiveAgency) = ActiveAgencyDto(agencyId = agency.agencyId, active = agency.active)
  }
}
