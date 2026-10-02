package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupAction
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItemStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJob
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJobStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.CleanupReason
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.IneligibleReason
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

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
  @Schema(description = "Band label", example = "2 to 5 years")
  val label: String,
  @Schema(description = "Lower bound of the band in whole months since the person left", example = "24")
  val fromMonths: Int,
  @Schema(description = "Upper bound of the band in whole months, or null for open-ended", example = "59", nullable = true)
  val toMonths: Int?,
  @Schema(description = "Containers in the band whose owner left in it", example = "120")
  val containers: Int,
)

@Schema(description = "What a legacy clean-up would close at a prison under the 13-month retention rule, without changing anything")
data class LegacyCleanupPreviewDto(
  @Schema(description = "Prison the preview is for", example = "MDI")
  val prisonId: String,
  @Schema(description = "How many months after the person left their property is kept before it is closed", example = "13")
  val retentionMonths: Int,
  @Schema(description = "People must have left on or before this date (13 months ago) for their property to be closed", example = "2025-09-02")
  val cutoffDate: LocalDate,
  @Schema(description = "When the preview was generated")
  val generatedAt: LocalDateTime,
  @Schema(description = "Containers that would be marked removed: the owner left on or before the cut-off")
  val toRemove: CleanupCountDto,
  @Schema(description = "The containers that would be marked removed, by why the owner left (released, died, escaped or absconded, or now at another prison)")
  val toRemoveByReason: Map<CleanupReason, CleanupCountDto>,
  @Schema(description = "Containers currently shown as due for return at the prison - the tile the clean-up reduces")
  val dueForReturnNow: CleanupCountDto,
  @Schema(description = "Containers currently shown as due for transfer out at the prison - the tile the clean-up reduces")
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
  @Schema(description = "The date the closing event is dated to - the date the person left")
  val plannedEventDate: LocalDate,
  @Schema(description = "The prison the person is now at, when they moved to another prison", nullable = true, example = "LEI")
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
  @Schema(description = "The look-back window in days, for jobs run before the fixed 13-month rule; absent since", nullable = true, example = "28")
  val olderThanDays: Int? = null,
  @Schema(description = "People must have left on or before this date for their property to be closed")
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
  @Schema(description = "Containers marked removed")
  val removedRecords: Int,
  @Schema(description = "Containers marked returned, by jobs run before the 13-month rule")
  val returnedRecords: Int,
  @Schema(description = "Containers marked transferred, by jobs run before the 13-month rule")
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
      processedRecords = job.removedRecords + job.returnedRecords + job.transferredRecords + job.skippedRecords + job.failedRecords,
      removedRecords = job.removedRecords,
      returnedRecords = job.returnedRecords,
      transferredRecords = job.transferredRecords,
      skippedRecords = job.skippedRecords,
      failedRecords = job.failedRecords,
      items = if (includeItems) job.items.map(LegacyCleanupItemDto::from) else null,
    )
  }
}
