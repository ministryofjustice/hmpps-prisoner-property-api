package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A snapshot of a NOMIS OFFENDER_PPTY_CONTAINERS row to migrate or sync into DPS. NOMIS-specific
 * references (booking id, internal location id) are resolved by the caller before sending: the
 * prisoner number and the locations-inside-prison-api location UUID are supplied directly.
 */
@Schema(description = "A NOMIS property container snapshot to migrate or sync into DPS")
data class SyncPropertyContainerRequest(
  @Schema(description = "NOMIS PROPERTY_CONTAINER_ID", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
  val nomisPropertyContainerId: Long,

  @Schema(
    description = "Existing DPS container id. Null when creating/migrating a new container; supplied on update.",
    example = "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d",
    nullable = true,
  )
  val dpsId: UUID? = null,

  @Schema(description = "Prisoner (NOMS) number, resolved by the caller from OFFENDER_BOOK_ID", example = "A1234BC", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:Pattern(regexp = "[A-Za-z][0-9]{4}[A-Za-z]{2}", message = "Prisoner number must be in the format A1234BC")
  val prisonerNumber: String,

  @Schema(description = "NOMIS AGY_LOC_ID - establishment holding the container", example = "LEI", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val prisonId: String,

  @Schema(
    description = "NOMIS CONTAINER_CODE (PPTY_CNTNR reference)",
    example = "Bulk",
    requiredMode = Schema.RequiredMode.REQUIRED,
  )
  val containerCode: NomisContainerCode,

  @Schema(
    description = "DPS locations-inside-prison-api location UUID for NOMIS INTERNAL_LOCATION_ID, resolved by the caller. " +
      "Null if NOMIS had no location, or for 'Branston Storage' containers which are held offsite with no internal location.",
    example = "11111111-1111-1111-1111-111111111111",
    nullable = true,
  )
  val internalLocationId: UUID? = null,

  @Schema(description = "NOMIS SEAL_MARK (free text, may be null or blank)", example = "SN8842K1", nullable = true)
  val sealMark: String? = null,

  @Schema(description = "NOMIS PROPOSED_DISPOSAL_DATE", example = "2026-09-01", nullable = true)
  val proposedDisposalDate: LocalDate? = null,

  @Schema(description = "NOMIS EXPIRY_DATE - the date the container was disposed of", example = "2026-09-15", nullable = true)
  val expiryDate: LocalDate? = null,

  @Schema(description = "NOMIS CREATE_DATETIME", requiredMode = Schema.RequiredMode.REQUIRED)
  val createDateTime: LocalDateTime,

  @Schema(description = "NOMIS CREATE_USER_ID", example = "QWILLIS", requiredMode = Schema.RequiredMode.REQUIRED)
  @field:NotBlank
  val createUsername: String,

  @Schema(description = "NOMIS MODIFY_DATETIME", nullable = true)
  val modifyDateTime: LocalDateTime? = null,

  @Schema(description = "NOMIS MODIFY_USER_ID", example = "QWILLIS", nullable = true)
  val modifyUsername: String? = null,
)
