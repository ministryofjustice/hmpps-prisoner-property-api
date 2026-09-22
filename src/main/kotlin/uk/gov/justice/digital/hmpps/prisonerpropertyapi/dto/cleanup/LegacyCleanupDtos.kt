package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupAction
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItemStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJob
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJobStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.IneligibleReason
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

const val DEFAULT_OLDER_THAN_DAYS = 28
const val MIN_OLDER_THAN_DAYS = 1L
const val MAX_OLDER_THAN_DAYS = 3650L

@Schema(description = "Request to run the legacy property clean-up for a prison")
data class StartLegacyCleanupRequest(
  @field:Schema(
    description = "Only close property for people who left at least this many days ago",
    example = "28",
    defaultValue = "28",
    minimum = "1",
    maximum = "3650",
  )
  @field:Min(MIN_OLDER_THAN_DAYS)
  @field:Max(MAX_OLDER_THAN_DAYS)
  val olderThanDays: Int = DEFAULT_OLDER_THAN_DAYS,
)

@Schema(description = "A count of containers and of the distinct prisoners they belong to")
data class CleanupCountDto(
  @Schema(description = "Number of containers", example = "412")
  val containers: Int,
  @Schema(description = "Number of distinct prisoners those containers belong to", example = "180")
  val prisoners: Int,
) {
  companion object {
    val NONE = CleanupCountDto(0, 0)
  }
}

@Schema(description = "How many containers fall in a band of time since the person left")
data class CleanupAgeBandDto(
  @Schema(description = "Band label", example = "91 to 365 days")
  val label: String,
  @Schema(description = "Lower bound of the band in days since the person left", example = "91")
  val fromDays: Int,
  @Schema(description = "Upper bound of the band in days, or null for open-ended", example = "365", nullable = true)
  val toDays: Int?,
  @Schema(description = "Containers in the band whose owner left in it", example = "120")
  val containers: Int,
)

@Schema(description = "What a legacy clean-up would close at a prison for a given look-back window, without changing anything")
data class LegacyCleanupPreviewDto(
  @Schema(description = "Prison the preview is for", example = "MDI")
  val prisonId: String,
  @Schema(description = "The look-back window the preview applied", example = "28")
  val olderThanDays: Int,
  @Schema(description = "People must have left on or before this date to be in scope", example = "2026-08-14")
  val cutoffDate: LocalDate,
  @Schema(description = "When the preview was generated")
  val generatedAt: LocalDateTime,
  @Schema(description = "Containers that would be marked returned: the owner was released on or before the cut-off")
  val toReturn: CleanupCountDto,
  @Schema(description = "Containers that would be marked transferred: the owner left for another prison on or before the cut-off")
  val toTransfer: CleanupCountDto,
  @Schema(description = "Containers currently shown as due for return at the prison, whatever the window - the tile the clean-up reduces")
  val dueForReturnNow: CleanupCountDto,
  @Schema(description = "Containers currently shown as due for transfer out at the prison, whatever the window - the tile the clean-up reduces")
  val dueForTransferOutNow: CleanupCountDto,
  @Schema(description = "Every live container at the prison that was considered")
  val candidates: CleanupCountDto,
  @Schema(description = "Containers left alone, by reason")
  val ineligible: Map<IneligibleReason, CleanupCountDto>,
  @Schema(description = "The containers that would be closed, banded by how long ago the person left")
  val ageBands: List<CleanupAgeBandDto>,
)

@Schema(description = "One container a clean-up job set out to close and what happened to it")
data class LegacyCleanupItemDto(
  val containerId: UUID,
  @Schema(example = "A1234AA")
  val prisonerNumber: String,
  val action: LegacyCleanupAction,
  @Schema(description = "The date the closing event is dated to - the release date, or the date the person left")
  val plannedEventDate: LocalDate,
  @Schema(description = "For TRANSFER, the prison the person is now at", nullable = true, example = "LEI")
  val plannedToPrisonId: String?,
  val status: LegacyCleanupItemStatus,
  @Schema(description = "Why the item was skipped or failed, if it was", nullable = true)
  val message: String?,
  val processedAt: LocalDateTime?,
) {
  companion object {
    fun from(item: LegacyCleanupItem) = LegacyCleanupItemDto(
      containerId = item.containerId,
      prisonerNumber = item.prisonerNumber,
      action = item.action,
      plannedEventDate = item.plannedEventDate,
      plannedToPrisonId = item.plannedToPrisonId,
      status = item.status,
      message = item.message,
      processedAt = item.processedAt,
    )
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A legacy clean-up job and its progress")
data class LegacyCleanupJobDto(
  val id: UUID,
  @Schema(example = "MDI")
  val prisonId: String,
  val status: LegacyCleanupJobStatus,
  @Schema(example = "28")
  val olderThanDays: Int,
  val cutoffDate: LocalDate,
  @Schema(description = "The admin who requested the run", example = "ADMIN_USER")
  val requestedBy: String,
  val requestedAt: LocalDateTime,
  val startTime: LocalDateTime?,
  val endTime: LocalDateTime?,
  @Schema(description = "Containers the job set out to close")
  val totalRecords: Int,
  @Schema(description = "Items that have reached a final status (processed, skipped or failed)")
  val processedRecords: Int,
  val returnedRecords: Int,
  val transferredRecords: Int,
  val skippedRecords: Int,
  val failedRecords: Int,
  @Schema(description = "The items, only on the single-job read", nullable = true)
  val items: List<LegacyCleanupItemDto>? = null,
) {
  companion object {
    fun from(job: LegacyCleanupJob, includeItems: Boolean) = LegacyCleanupJobDto(
      id = job.id!!,
      prisonId = job.prisonId,
      status = job.status,
      olderThanDays = job.olderThanDays,
      cutoffDate = job.cutoffDate,
      requestedBy = job.requestedBy,
      requestedAt = job.requestedAt,
      startTime = job.startTime,
      endTime = job.endTime,
      totalRecords = job.totalRecords,
      processedRecords = job.returnedRecords + job.transferredRecords + job.skippedRecords + job.failedRecords,
      returnedRecords = job.returnedRecords,
      transferredRecords = job.transferredRecords,
      skippedRecords = job.skippedRecords,
      failedRecords = job.failedRecords,
      items = if (includeItems) job.items.map(LegacyCleanupItemDto::from) else null,
    )
  }
}
