package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "A single event in a property container's history")
data class PropertyEventDto(
  @Schema(description = "Unique id of the event", example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d")
  val id: UUID,

  @Schema(description = "What happened to the container", example = "MOVED")
  val eventType: PropertyEventType,

  @Schema(description = "When the event happened")
  val eventDateTime: LocalDateTime,

  @Schema(description = "Id of the user who recorded the event", example = "AUSER_GEN")
  val eventUserId: String,

  @Schema(description = "Seal number recorded by this event, for events that carry a seal", example = "SEAL12345", nullable = true)
  val sealNumber: String?,

  @Schema(description = "Internal location the container moved from, for move events", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val fromInternalLocationId: UUID?,

  @Schema(description = "Internal location the container moved to, for move events", example = "22222222-2222-2222-2222-222222222222", nullable = true)
  val toInternalLocationId: UUID?,

  @Schema(description = "Storage location type the container moved to (INTERNAL prison location or offsite BRANSTON), for move events", example = "INTERNAL", nullable = true)
  val toStorageLocationType: StorageLocationType?,

  @Schema(description = "Prison the container moved from, for transfer events", example = "LEI", nullable = true)
  val fromPrisonId: String?,

  @Schema(description = "Prison the container moved to, for transfer events", example = "MDI", nullable = true)
  val toPrisonId: String?,

  @Schema(description = "Business date the event relates to (e.g. proposed disposal or removal date), if any", example = "2026-09-15", nullable = true)
  val eventDate: LocalDate?,

  @Schema(description = "Id of a related container, for combine events", example = "33333333-3333-3333-3333-333333333333", nullable = true)
  val relatedContainerId: UUID?,
) {
  companion object {
    fun from(event: PropertyEvent) = PropertyEventDto(
      id = event.id!!,
      eventType = event.eventType,
      eventDateTime = event.eventDateTime,
      eventUserId = event.eventUserId,
      sealNumber = event.sealNumber,
      fromInternalLocationId = event.fromInternalLocationId,
      toInternalLocationId = event.toInternalLocationId,
      toStorageLocationType = event.toStorageLocationType,
      fromPrisonId = event.fromPrisonId,
      toPrisonId = event.toPrisonId,
      eventDate = event.eventDate,
      relatedContainerId = event.relatedContainerId,
    )
  }
}
