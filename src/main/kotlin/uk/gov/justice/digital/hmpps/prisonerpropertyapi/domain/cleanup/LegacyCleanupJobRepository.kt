package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface LegacyCleanupJobRepository : JpaRepository<LegacyCleanupJob, UUID> {

  /**
   * Load a job for claiming, under a row lock, so two consumers of the same redelivered message serialise: the
   * first flips it to STARTED and commits, the second then reads STARTED and stands down.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from LegacyCleanupJob j where j.id = :id")
  fun findByIdForUpdate(@Param("id") id: UUID): LegacyCleanupJob?

  fun existsByPrisonIdAndStatusIn(prisonId: String, statuses: Collection<LegacyCleanupJobStatus>): Boolean

  fun findByPrisonIdOrderByRequestedAtDesc(prisonId: String): List<LegacyCleanupJob>

  /**
   * Bump a running counter in SQL rather than through the entity, so the progress a watcher sees moves as each
   * item commits and concurrent bumps cannot lose an update. Each also stamps the activity time that the
   * stale-claim rule reads.
   */
  @Modifying
  @Query("update LegacyCleanupJob j set j.returnedRecords = j.returnedRecords + 1, j.lastActivityAt = :now where j.id = :id")
  fun incrementReturned(@Param("id") id: UUID, @Param("now") now: LocalDateTime)

  @Modifying
  @Query("update LegacyCleanupJob j set j.transferredRecords = j.transferredRecords + 1, j.lastActivityAt = :now where j.id = :id")
  fun incrementTransferred(@Param("id") id: UUID, @Param("now") now: LocalDateTime)

  @Modifying
  @Query("update LegacyCleanupJob j set j.skippedRecords = j.skippedRecords + 1, j.lastActivityAt = :now where j.id = :id")
  fun incrementSkipped(@Param("id") id: UUID, @Param("now") now: LocalDateTime)

  @Modifying
  @Query("update LegacyCleanupJob j set j.failedRecords = j.failedRecords + 1, j.lastActivityAt = :now where j.id = :id")
  fun incrementFailed(@Param("id") id: UUID, @Param("now") now: LocalDateTime)
}

interface LegacyCleanupItemRepository : JpaRepository<LegacyCleanupItem, UUID> {
  /** The unprocessed items of a job in insertion order (ids are time-ordered), which is the resume point after a redelivery. */
  fun findByJobIdAndStatusOrderById(jobId: UUID, status: LegacyCleanupItemStatus): List<LegacyCleanupItem>
}
