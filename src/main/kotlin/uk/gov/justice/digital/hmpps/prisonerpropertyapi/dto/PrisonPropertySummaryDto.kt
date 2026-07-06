package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
  description = "Whole-prison property totals for the establishment summary tiles. Counts are for the prison as a " +
    "whole, independent of any list paging or filtering.",
)
data class PrisonPropertySummaryDto(
  @Schema(description = "Number of BOX storage locations configured for the prison (from locations-inside-prison-api)", example = "150")
  val availableStorageLocations: Int,

  @Schema(description = "Number of containers currently stored on-site (status STORED)", example = "3000")
  val storedOnSite: Int,

  @Schema(description = "Number of containers due to be transferred out (status DUE_FOR_TRANSFER_OUT)", example = "80")
  val dueToTransferOut: Int,

  @Schema(description = "Number of containers due to be returned. Always 0 for now - no status yet represents a pending return", example = "0")
  val dueToBeReturned: Int,

  @Schema(description = "Number of containers due to be disposed of (status DISPOSAL_REQUIRED)", example = "40")
  val dueToBeDisposed: Int,
)
