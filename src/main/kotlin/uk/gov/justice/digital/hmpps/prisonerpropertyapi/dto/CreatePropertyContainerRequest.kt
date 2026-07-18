package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.time.LocalDate
import java.util.UUID

@Schema(description = "Details for a new sealed property container")
data class CreatePropertyContainerRequest(
  @Schema(description = "Prisoner (NOMS) number the property belongs to", example = "A1234BC", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:Pattern(regexp = "[A-Za-z][0-9]{4}[A-Za-z]{2}", message = "Prisoner number must be in the format A1234BC")
  val prisonerNumber: String,

  @Schema(description = "Id of the prison holding the container", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,

  @Schema(description = "Type of container", example = "STANDARD", requiredMode = Schema.RequiredMode.REQUIRED)
  val containerType: ContainerType,

  @Schema(description = "Seal number identifying the container", example = "SN8842K1", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val sealNumber: String,

  @Schema(
    description = "If this container is arriving on transfer from another establishment, the seal it was " +
      "recorded under there (its 'due for transfer out' seal). Used to link to and deactivate that record so " +
      "no ghost record is left behind. Leave blank for genuinely new property.",
    example = "SN8842K1",
    nullable = true,
  )
  val previousSealNumber: String? = null,

  @Schema(description = "Internal storage location id (locations-inside-prison-api), if known", example = "11111111-1111-1111-1111-111111111111", nullable = true)
  val internalLocationId: UUID? = null,

  @Schema(
    description = "Where the container is stored: INTERNAL (a prison location, given by internalLocationId) or " +
      "BRANSTON (the offsite warehouse, no internal location). Defaults to INTERNAL when an internalLocationId " +
      "is given. Use BRANSTON for excess property held offsite.",
    example = "INTERNAL",
    nullable = true,
  )
  val locationType: StorageLocationType? = null,

  @Schema(description = "Date the container is proposed to be disposed of, if any", example = "2026-09-01", nullable = true)
  val proposedDisposalDate: LocalDate? = null,
)
