package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
  description = "Whole-prison property totals for the establishment summary tiles. Counts are for the prison as a " +
    "whole, independent of any list paging or filtering.",
)
data class PrisonPropertySummaryDto(
  @Schema(description = "The prison's total internal BOX storage locations minus the containers currently stored on-site (floored at 0)", example = "150")
  val availableStorageLocations: Int,

  @Schema(description = "Number of containers currently in internal storage at this establishment", example = "3000")
  val storedOnSite: Int,

  @Schema(description = "Number of containers held here for people no longer at this establishment (status DUE_FOR_TRANSFER_OUT)", example = "80")
  val dueToTransferOut: Int,

  @Schema(description = "Number of containers due to be returned (flagged after the prisoner's release)", example = "36")
  val dueToBeReturned: Int,

  @Schema(description = "Number of containers whose proposed disposal date has arisen (today or earlier)", example = "40")
  val dueToBeDisposed: Int,
)
