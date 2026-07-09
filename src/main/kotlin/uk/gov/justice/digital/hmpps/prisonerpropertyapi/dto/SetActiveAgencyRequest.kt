package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Switch the property service on or off for an agency (prison)")
data class SetActiveAgencyRequest(
  @Schema(
    description = "Whether the property service should be active for this agency",
    example = "true",
    requiredMode = Schema.RequiredMode.REQUIRED,
  )
  val active: Boolean,
)
