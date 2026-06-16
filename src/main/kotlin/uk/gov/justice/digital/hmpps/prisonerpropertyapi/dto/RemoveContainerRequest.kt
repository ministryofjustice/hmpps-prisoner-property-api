package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import java.time.LocalDate

@Schema(description = "Details for removing a property container from active storage by returning it to the prisoner or transferring it")
data class RemoveContainerRequest(
  @Schema(description = "Why the container is being removed - must be RETURNED or TRANSFERRED (use the dispose endpoint for disposals)", example = "RETURNED", requiredMode = Schema.RequiredMode.REQUIRED)
  val outcome: RemovalOutcome,

  @Schema(description = "Date the container was removed; defaults to today when omitted", example = "2026-09-15", nullable = true)
  val date: LocalDate? = null,

  @Schema(description = "Destination prison id - required when the outcome is TRANSFERRED", example = "MDI", nullable = true)
  val toPrisonId: String? = null,
)
