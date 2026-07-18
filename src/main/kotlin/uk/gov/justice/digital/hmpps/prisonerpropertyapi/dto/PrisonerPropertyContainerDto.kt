package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonerMovementStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Schema(
  description = "A sealed container of a prisoner's property, enriched with the prisoner, prison and " +
    "location names resolved from prisoner-search, prison-register and locations-inside-prison-api.",
)
data class PrisonerPropertyContainerDto(
  @Schema(description = "Unique id of the container", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d")
  val id: UUID,

  @Schema(description = "Prisoner number the property belongs to", example = "A1234BC")
  val prisonerNumber: String,

  @Schema(description = "Name of the prisoner the property belongs to, from prisoner-search. Null if the prisoner could not be resolved", example = "John Smith", nullable = true)
  val prisonerName: String?,

  @Schema(description = "Id of the prison holding the container", example = "LEI")
  val prisonId: String,

  @Schema(description = "Name of the prison holding the container, from prison-register. Null if the prison could not be resolved", example = "Leeds (HMP)", nullable = true)
  val prisonName: String?,

  @Schema(description = "Id of the prisoner's current prison, from prisoner-search. Distinct from the holding prison; null if the prisoner could not be resolved", example = "IWI", nullable = true)
  val prisonerCurrentPrisonId: String?,

  @Schema(description = "Name of the prisoner's current prison, from prison-register. Distinct from the holding prison; null if it could not be resolved", example = "Isle of Wight (HMP)", nullable = true)
  val prisonerCurrentPrisonName: String?,

  @Schema(description = "The prisoner's movement status, from prisoner-search: whether they are held in an establishment, in transit between prisons, or released. Null if the prisoner could not be resolved", example = "IN_ESTABLISHMENT", nullable = true)
  val prisonerMovementStatus: PrisonerMovementStatus?,

  @Schema(description = "Whether the container is currently held in the prisoner's current prison. False flags property left behind at a prison the prisoner has moved on from", example = "true")
  val inPrisonersCurrentPrison: Boolean,

  @Schema(description = "Type of container", example = "STANDARD")
  val containerType: ContainerType,

  @Schema(description = "Current seal number, derived from the most recent seal event", example = "SEAL12345", nullable = true)
  val currentSealNumber: String?,

  @Schema(description = "Current status, derived from the most recent event", example = "STORED")
  val currentStatus: ContainerStatus,

  @Schema(description = "Current internal location id, derived from the most recent move. Null when offsite at Branston, unrecorded, or disposed", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val currentLocation: UUID?,

  @Schema(description = "Current storage location type (INTERNAL prison location or offsite BRANSTON), derived from the most recent move", example = "INTERNAL", nullable = true)
  val currentLocationType: StorageLocationType?,

  @Schema(description = "Human-friendly description of the current internal location, from locations-inside-prison-api. Null when offsite, unrecorded, disposed, or the location could not be resolved", example = "Reception Property Store", nullable = true)
  val locationDescription: String?,

  @Schema(description = "Date the container is proposed to be disposed of, if any", example = "2026-09-01", nullable = true)
  val proposedDisposalDate: LocalDate?,

  @Schema(description = "Why the container left active storage (disposed, returned, transferred, combined), if it has", example = "DISPOSED", nullable = true)
  val removalOutcome: RemovalOutcome?,

  @Schema(description = "Date the container left active storage, if it has", example = "2026-09-15", nullable = true)
  val removalDate: LocalDate?,

  @Schema(description = "When the container was created")
  val createDateTime: LocalDateTime,

  @Schema(description = "Id of the user who created the container", example = "AUSER_GEN")
  val createdByUserId: String,
) {
  companion object {
    fun from(
      container: PropertyContainer,
      prisonerName: String?,
      prisonName: String?,
      prisonerCurrentPrisonId: String?,
      prisonerCurrentPrisonName: String?,
      prisonerMovementStatus: PrisonerMovementStatus?,
      locationDescription: String?,
      inPrisonersCurrentPrison: Boolean,
    ) = PrisonerPropertyContainerDto(
      id = container.id!!,
      prisonerNumber = container.prisonerNumber,
      prisonerName = prisonerName,
      prisonId = container.prisonId,
      prisonName = prisonName,
      prisonerCurrentPrisonId = prisonerCurrentPrisonId,
      prisonerCurrentPrisonName = prisonerCurrentPrisonName,
      prisonerMovementStatus = prisonerMovementStatus,
      inPrisonersCurrentPrison = inPrisonersCurrentPrison,
      containerType = container.containerType,
      currentSealNumber = container.currentSealNumber,
      currentStatus = container.currentStatus(),
      currentLocation = container.currentLocation(),
      currentLocationType = container.currentLocationType(),
      locationDescription = locationDescription,
      proposedDisposalDate = container.proposedDisposalDate,
      removalOutcome = container.removalOutcome,
      removalDate = container.removalDate,
      createDateTime = container.createDateTime,
      createdByUserId = container.createdByUserId,
    )

    /**
     * As [from], but reads the denormalised current status/location columns instead of deriving from the
     * container's events - so the establishment-wide list can render rows without loading any events.
     */
    fun fromColumns(
      container: PropertyContainer,
      prisonerName: String?,
      prisonName: String?,
      prisonerCurrentPrisonId: String?,
      prisonerCurrentPrisonName: String?,
      prisonerMovementStatus: PrisonerMovementStatus?,
      locationDescription: String?,
      inPrisonersCurrentPrison: Boolean,
    ) = PrisonerPropertyContainerDto(
      id = container.id!!,
      prisonerNumber = container.prisonerNumber,
      prisonerName = prisonerName,
      prisonId = container.prisonId,
      prisonName = prisonName,
      prisonerCurrentPrisonId = prisonerCurrentPrisonId,
      prisonerCurrentPrisonName = prisonerCurrentPrisonName,
      prisonerMovementStatus = prisonerMovementStatus,
      inPrisonersCurrentPrison = inPrisonersCurrentPrison,
      containerType = container.containerType,
      currentSealNumber = container.currentSealNumber,
      // The denormalised column holds the time-stable base status; overlay DISPOSAL_REQUIRED once the
      // proposed disposal date has arisen, so the list matches the person view without loading events.
      currentStatus = if (container.isDisposalDue()) ContainerStatus.DISPOSAL_REQUIRED else container.currentStatusValue,
      currentLocation = container.currentInternalLocationId,
      currentLocationType = container.currentStorageLocationType,
      locationDescription = locationDescription,
      proposedDisposalDate = container.proposedDisposalDate,
      removalOutcome = container.removalOutcome,
      removalDate = container.removalDate,
      createDateTime = container.createDateTime,
      createdByUserId = container.createdByUserId,
    )
  }
}
