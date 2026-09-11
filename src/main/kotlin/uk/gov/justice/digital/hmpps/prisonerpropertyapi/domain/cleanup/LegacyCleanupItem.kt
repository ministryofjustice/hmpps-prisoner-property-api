package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.helper.GeneratedUuidV7
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * One container a [LegacyCleanupJob] intends to close, with the action decided when the job was requested.
 * The decision is re-checked against a fresh prisoner-search read at processing time; the snapshot is what
 * makes the job's total stable and lets the preview and the run agree.
 */
@Entity
@Table(name = "legacy_cleanup_item")
class LegacyCleanupItem(
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "legacy_cleanup_job_id", nullable = false)
  val job: LegacyCleanupJob,

  @Column(name = "property_container_id", nullable = false)
  val containerId: UUID,

  @Column(name = "prisoner_number", nullable = false)
  val prisonerNumber: String,

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false)
  val action: LegacyCleanupAction,

  @Column(name = "planned_event_date", nullable = false)
  val plannedEventDate: LocalDate,

  @Column(name = "planned_to_prison_id")
  val plannedToPrisonId: String? = null,

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  var status: LegacyCleanupItemStatus = LegacyCleanupItemStatus.PENDING,

  @Column(name = "message")
  var message: String? = null,

  @Column(name = "processed_at")
  var processedAt: LocalDateTime? = null,

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  var id: UUID? = null,
) {
  fun markProcessed(now: LocalDateTime, message: String? = null) = settle(LegacyCleanupItemStatus.PROCESSED, message, now)
  fun markSkipped(message: String, now: LocalDateTime) = settle(LegacyCleanupItemStatus.SKIPPED, message, now)
  fun markFailed(message: String, now: LocalDateTime) = settle(LegacyCleanupItemStatus.FAILED, message, now)

  private fun settle(status: LegacyCleanupItemStatus, message: String?, now: LocalDateTime) {
    this.status = status
    this.message = message?.take(MESSAGE_LENGTH)
    this.processedAt = now
  }

  private companion object {
    const val MESSAGE_LENGTH = 255
  }
}

/** What the clean-up does to a container: RETURN marks it returned to a released person; TRANSFER marks it transferred to the prison the person is now at. */
enum class LegacyCleanupAction { RETURN, TRANSFER }

enum class LegacyCleanupItemStatus { PENDING, PROCESSED, SKIPPED, FAILED }
