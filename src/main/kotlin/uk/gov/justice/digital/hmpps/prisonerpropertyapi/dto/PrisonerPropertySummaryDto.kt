package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
  description = "A single prisoner's property totals for the profile \"Property containers\" tile. All counts " +
    "are for the person's active (not removed) containers, split by where they are held relative to the " +
    "prisoner's current establishment (resolved from prisoner-search). Carries everything the candidate tile " +
    "designs need so the profile can render either the simple two-count layout or the fuller breakdown.",
)
data class PrisonerPropertySummaryDto(
  @Schema(description = "The prisoner's current establishment id (prisoner-search), or null if they are released or in transit", example = "IWI", nullable = true)
  val currentEstablishmentId: String?,

  @Schema(description = "The prisoner's current establishment name (prison-register), or null if unknown/released/in transit", example = "Isle of Wight (HMP)", nullable = true)
  val currentEstablishmentName: String?,

  @Schema(description = "Active containers physically held at the prisoner's current establishment", example = "2")
  val heldInCurrentEstablishment: Int,

  @Schema(description = "Active containers held at any other establishment", example = "3")
  val heldInOtherEstablishments: Int,

  @Schema(description = "Active containers held elsewhere that are due to be transferred in to the current establishment (their owner was received here). A subset of those held in other establishments.", example = "3")
  val dueForTransferIn: Int,

  @Schema(description = "Active containers held at the current establishment that are due to be transferred out (status DUE_FOR_TRANSFER_OUT)", example = "0")
  val dueForTransferOut: Int,

  @Schema(description = "Active containers whose proposed disposal date has arisen (today or earlier)", example = "1")
  val overdueForDisposal: Int,

  @Schema(description = "Active containers flagged due for return following the prisoner's release (status DUE_FOR_RETURN)", example = "1")
  val overdueForReturn: Int,

  @Schema(description = "Whether the prisoner has any recorded property at all (including containers that have since been returned, disposed or transferred). Lets the tile distinguish \"never had property\" from \"no longer has any\".", example = "true")
  val hasEverHadProperty: Boolean,
)
