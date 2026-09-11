package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.helper.GeneratedUuidV7
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * One admin-requested run of the legacy property clean-up for a prison - see `docs/legacy-cleanup.md`.
 *
 * The candidates are snapshotted as [items] when the job is requested and worked through one committed
 * transaction at a time by `LegacyCleanupProcessingService`, so the total an admin is shown never moves and a
 * redelivery or pod restart resumes from the unprocessed items. The counters are bumped as each item lands
 * (atomically, in SQL) so a progress page can watch the run; [finish] recounts them from the items at the end.
 */
@Entity
@Table(name = "legacy_cleanup_job")
class LegacyCleanupJob(
  @Column(name = "prison_id", nullable = false)
  val prisonId: String,

  @Column(name = "older_than_days", nullable = false)
  val olderThanDays: Int,

  @Column(name = "cutoff_date", nullable = false)
  val cutoffDate: LocalDate,

  @Column(name = "requested_by", nullable = false)
  val requestedBy: String,

  @Column(name = "requested_at", nullable = false)
  val requestedAt: LocalDateTime,

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  var status: LegacyCleanupJobStatus = LegacyCleanupJobStatus.PENDING,

  @Column(name = "start_time")
  var startTime: LocalDateTime? = null,

  @Column(name = "last_activity_at")
  var lastActivityAt: LocalDateTime? = null,

  @Column(name = "end_time")
  var endTime: LocalDateTime? = null,

  @Column(name = "total_records", nullable = false)
  var totalRecords: Int = 0,

  @Column(name = "returned_records", nullable = false)
  var returnedRecords: Int = 0,

  @Column(name = "transferred_records", nullable = false)
  var transferredRecords: Int = 0,

  @Column(name = "skipped_records", nullable = false)
  var skippedRecords: Int = 0,

  @Column(name = "failed_records", nullable = false)
  var failedRecords: Int = 0,

  @OneToMany(mappedBy = "job", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
  val items: MutableList<LegacyCleanupItem> = mutableListOf(),

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  var id: UUID? = null,
) {
  fun isActive(): Boolean = status in ACTIVE_STATUSES

  /** Claim the job for processing: PENDING (or an abandoned STARTED) becomes STARTED, keeping the original start time. */
  fun start(now: LocalDateTime) {
    status = LegacyCleanupJobStatus.STARTED
    if (startTime == null) startTime = now
    lastActivityAt = now
  }

  /** Close the job, recounting the totals from the items so they are authoritative rather than the running counts. */
  fun finish(now: LocalDateTime) {
    status = LegacyCleanupJobStatus.FINISHED
    endTime = now
    lastActivityAt = now
    returnedRecords = items.count { it.status == LegacyCleanupItemStatus.PROCESSED && it.action == LegacyCleanupAction.RETURN }
    transferredRecords = items.count { it.status == LegacyCleanupItemStatus.PROCESSED && it.action == LegacyCleanupAction.TRANSFER }
    skippedRecords = items.count { it.status == LegacyCleanupItemStatus.SKIPPED }
    failedRecords = items.count { it.status == LegacyCleanupItemStatus.FAILED }
  }

  companion object {
    val ACTIVE_STATUSES = listOf(LegacyCleanupJobStatus.PENDING, LegacyCleanupJobStatus.STARTED)
  }
}

enum class LegacyCleanupJobStatus { PENDING, STARTED, FINISHED }
