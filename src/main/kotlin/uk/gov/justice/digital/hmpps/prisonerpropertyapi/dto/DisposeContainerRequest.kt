package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

@Schema(description = "Details for disposing of a property container")
data class DisposeContainerRequest(
  @Schema(description = "Date the container was disposed of; defaults to today when omitted", example = "2026-09-15", nullable = true)
  val disposalDate: LocalDate? = null,
)
