package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
  description = "A single prisoner's property totals for the profile \"Property containers\" card, from the point " +
    "of view of the prison the member of staff is viewing from. Counts are over the person's active (not " +
    "removed) containers and use the status the property service shows for each one, so the card agrees with " +
    "the prisoner's property page at that prison. Property held at other prisons, and property due to transfer " +
    "in, is only counted when the prisoner is held at the viewing prison; otherwise only the viewing prison's " +
    "own containers are counted.",
)
data class PrisonerPropertySummaryDto(
  @Schema(description = "The prison the counts are worked out for: the member of staff's active caseload", example = "LEI")
  val prisonId: String,

  @Schema(description = "The viewing prison's name (prison-register), or null if unknown", example = "Leeds (HMP)", nullable = true)
  val prisonName: String?,

  @Schema(description = "Whether the prisoner is currently held at the viewing prison", example = "true")
  val prisonerHeldHere: Boolean,

  @Schema(description = "The prisoner's current establishment id (prisoner-search), or null if they are released or in transit", example = "LEI", nullable = true)
  val currentEstablishmentId: String?,

  @Schema(description = "The prisoner's current establishment name (prison-register), or null if unknown, released or in transit", example = "Leeds (HMP)", nullable = true)
  val currentEstablishmentName: String?,

  @Schema(description = "Active containers held at the viewing prison", example = "2")
  val heldAtThisPrison: Int,

  @Schema(description = "Containers held at the viewing prison that are due to transfer out, because their owner has moved on or is in transit", example = "0")
  val dueToTransferOut: Int,

  @Schema(description = "Containers held at the viewing prison that are due to be returned, because their owner has been released or is about to be", example = "1")
  val dueToBeReturned: Int,

  @Schema(description = "Containers held at the viewing prison whose proposed disposal date has arisen (today or earlier)", example = "1")
  val dueToBeDisposed: Int,

  @Schema(description = "Active containers held at other prisons. Only counted when the prisoner is held at the viewing prison, otherwise 0.", example = "3")
  val heldAtOtherPrisons: Int,

  @Schema(
    description = "Containers due to transfer in to the viewing prison: those held at other prisons that now need to " +
      "follow the prisoner here, plus any already sent here and not yet logged on arrival. Only counted when the " +
      "prisoner is held at the viewing prison, otherwise 0.",
    example = "3",
  )
  val dueToTransferIn: Int,

  @Schema(description = "Whether the prisoner has any recorded property at all, at any prison (including containers since returned, disposed or transferred). Lets the card tell \"never had property\" apart from \"no longer has any\".", example = "true")
  val hasEverHadProperty: Boolean,
)
