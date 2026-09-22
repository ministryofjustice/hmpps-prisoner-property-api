package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sar

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A single event in a container's history as it appears in a subject access request response.
 *
 * Deliberately not [uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyEventDto]: the SAR response
 * is a separate contract with its own rules, and sharing the product DTO is how a field added for the product
 * ends up in a prisoner's report without anyone deciding it should be there.
 *
 * Codes and usernames are carried raw, each in its own attribute, and resolved to names by the SAR template
 * helpers (`getPrisonName`, `getLocationNameByDpsId`, `getUserLastName`) at render time - the service must not
 * resolve them itself, because the resulting names are data owned by other services.
 *
 * Enum codes are the exception: they mean nothing outside this service, so they are decoded here to the
 * wording in [sarLabel] rather than disclosed raw.
 */
@Schema(description = "A single event in a property container's history")
data class SarPropertyEvent(
  @Schema(description = "What happened to the container", example = "Moved to a different storage location")
  val eventType: String,

  @Schema(description = "When the event happened")
  val eventDateTime: LocalDateTime,

  @Schema(description = "Business date the event relates to (e.g. proposed disposal or removal date), if any", example = "2026-09-15", nullable = true)
  val eventDate: LocalDate?,

  @Schema(description = "Username of the member of staff who recorded the event", example = "AUSER_GEN")
  val eventUsername: String,

  @Schema(description = "Seal number recorded by this event, for events that carry a seal", example = "SEAL12345", nullable = true)
  val sealNumber: String?,

  @Schema(description = "Internal location the container moved from, for move events", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val fromLocationId: UUID?,

  @Schema(description = "Internal location the container moved to, for move events", example = "22222222-2222-2222-2222-222222222222", nullable = true)
  val toLocationId: UUID?,

  @Schema(description = "Whether the container moved to a location inside the prison or to the offsite national store", example = "In the establishment", nullable = true)
  val toStorageLocationType: String?,

  @Schema(description = "Prison the container moved from, for transfer events", example = "LEI", nullable = true)
  val fromPrisonId: String?,

  @Schema(description = "Prison the container moved to, for transfer events", example = "MDI", nullable = true)
  val toPrisonId: String?,

  @Schema(description = "The container's type as at the time of this event", example = "Standard property")
  val containerType: String,

  @Schema(
    description = "Seal number of the container this one was combined into, or of the matching record at the " +
      "other establishment when property was transferred. The related container's internal id is deliberately " +
      "omitted; the seal is the part that means anything to the person the report is about",
    example = "SN991234",
    nullable = true,
  )
  val relatedContainerSealNumber: String?,
) {
  companion object {
    fun from(event: PropertyEvent) = SarPropertyEvent(
      eventType = event.eventType.sarLabel,
      eventDateTime = event.eventDateTime,
      eventDate = event.eventDate,
      eventUsername = event.eventUserId,
      sealNumber = event.sealNumber,
      fromLocationId = event.fromInternalLocationId,
      toLocationId = event.toInternalLocationId,
      toStorageLocationType = event.toStorageLocationType?.sarLabel,
      fromPrisonId = event.fromPrisonId,
      toPrisonId = event.toPrisonId,
      containerType = event.containerType.sarLabel,
      relatedContainerSealNumber = event.relatedContainerSealNumber,
    )
  }
}
