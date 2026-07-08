package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import java.time.LocalDate

@Schema(description = "Details for removing a property container from active storage: returned to the prisoner, disposed of, transferred to the prisoner's new establishment, or the record was created in error")
data class RemoveContainerRequest(
  @Schema(description = "Why the container is being removed - one of RETURNED, DISPOSED, TRANSFERRED or CREATED_IN_ERROR (COMBINED is not accepted; use the combine endpoint)", example = "RETURNED", requiredMode = Schema.RequiredMode.REQUIRED)
  val outcome: RemovalOutcome,

  @Schema(description = "Date the container was removed; defaults to today when omitted", example = "2026-09-15", nullable = true)
  val date: LocalDate? = null,

  @Schema(description = "Receiving prison id - required when the outcome is TRANSFERRED; the container is reassigned to this prison", example = "MDI", nullable = true)
  val toPrisonId: String? = null,
)
