package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
  description = "The property containers held in an establishment for a single prisoner, grouped together " +
    "for the establishment-wide list. Carries the prisoner's identity and current establishment (from " +
    "prisoner-search) for the row header, with one entry per matching container.",
)
data class PrisonerPropertyGroupDto(
  @Schema(description = "Prisoner number the containers belong to", example = "A1234BC")
  val prisonerNumber: String,

  @Schema(description = "Name of the prisoner, from prisoner-search. Null if the prisoner could not be resolved", example = "John Smith", nullable = true)
  val prisonerName: String?,

  @Schema(description = "Id of the prisoner's current establishment, from prisoner-search. May differ from the establishment holding the property, or be null if the prisoner is not currently in an establishment", example = "LEI", nullable = true)
  val prisonerCurrentPrisonId: String?,

  @Schema(description = "Name of the prisoner's current establishment, from prison-register. Null if it could not be resolved", example = "Leeds (HMP)", nullable = true)
  val prisonerCurrentPrisonName: String?,

  @Schema(description = "The matching property containers held for this prisoner in the establishment")
  val containers: List<PrisonerPropertyContainerDto>,
)
