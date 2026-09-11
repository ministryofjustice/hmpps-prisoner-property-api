package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupAction
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItemRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItemStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJobRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJobStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyTelemetry
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerAlreadyRemovedException
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerWriteService
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Runs a legacy clean-up job on the SQS listener thread: claims it, then closes each pending item in turn.
 *
 * Three things per item, in order and never nested: the container write in its own transaction, the item's
 * bookkeeping (status + running counter) in a second, then the domain event published to SNS. Sequential
 * because the write must be durable before it is announced, and the bookkeeping must not roll back with a
 * failed write - it is what records the failure. A pod dying between the first and second leaves a closed
 * container with a PENDING item; the next delivery sees the container already carries this job's closing
 * event and settles the item as processed rather than closing it twice.
 *
 * Explicit [TransactionTemplate]s rather than `@Transactional`: `process` calls its own helpers, which the
 * proxy would not intercept.
 */
@Service
class LegacyCleanupProcessingService(
  private val jobRepository: LegacyCleanupJobRepository,
  private val itemRepository: LegacyCleanupItemRepository,
  private val containerRepository: PropertyContainerRepository,
  private val writeService: PropertyContainerWriteService,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val rule: LegacyCleanupRule,
  private val domainEventPublisher: DomainEventPublisher,
  private val telemetryClient: TelemetryClient,
  transactionManager: PlatformTransactionManager,
) {
  private val newTransaction = TransactionTemplate(transactionManager)
  private val requiresNew = TransactionTemplate(transactionManager).apply {
    propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
  }

  fun process(jobId: UUID) {
    val context = claim(jobId) ?: return
    log.info("Processing legacy clean-up job {} at {}: {} pending items", jobId, context.prisonId, context.pending.size)

    // One bulk read for every owner still to be processed, outside any transaction. The decision is re-made
    // against this rather than the snapshot so a person who came back, or whose record moved on, is skipped.
    val prisoners = prisonerSearchClient.getPrisoners(context.pending.map { it.prisonerNumber }.distinct())

    context.pending.forEach { item ->
      // The exceptions are caught outside the template on purpose: they are thrown through the write service's
      // own @Transactional proxy, which marks the transaction rollback-only on the way out, so it has to be
      // rolled back (by the template) rather than committed by a handler inside it.
      val outcome = try {
        requiresNew.execute { close(item, prisoners[item.prisonerNumber], context) }!!
      } catch (e: ContainerAlreadyRemovedException) {
        if (closedByThisJob(item.containerId, jobId)) {
          // Closed on an earlier delivery whose bookkeeping never committed. Nothing to publish again either:
          // the event went, or was lost, then.
          ItemOutcome.Processed(event = null, message = "closed on an earlier delivery")
        } else {
          ItemOutcome.Skipped("already removed: ${e.message}")
        }
      } catch (e: LegacyCleanupNotApplicableException) {
        ItemOutcome.Skipped(e.message ?: "not applicable")
      } catch (e: Exception) {
        log.error("Legacy clean-up job {}: failed to close container {}", jobId, item.containerId, e)
        ItemOutcome.Failed(e.message ?: e.javaClass.simpleName)
      }
      newTransaction.executeWithoutResult { record(item.id!!, jobId, context.prisonId, outcome) }
      (outcome as? ItemOutcome.Processed)?.event?.let { publishQuietly(it, item.id!!, jobId) }
    }

    newTransaction.executeWithoutResult { finish(jobId) }
  }

  /**
   * Take ownership of the job under a row lock. A PENDING job is claimed; a STARTED one that has shown no
   * activity for [STALE_CLAIM_THRESHOLD] is assumed abandoned by a pod that died and is re-claimed - only its
   * still-pending items are then processed. Anything else is a redelivery of a message already being, or
   * already, handled: tracked and dropped.
   */
  private fun claim(jobId: UUID): JobContext? = newTransaction.execute {
    val job = jobRepository.findByIdForUpdate(jobId)
    val now = LocalDateTime.now()
    when {
      job == null -> {
        log.warn("Legacy clean-up job {} not found; ignoring message", jobId)
        duplicate(jobId, "not found")
        null
      }
      job.status == LegacyCleanupJobStatus.FINISHED -> {
        duplicate(jobId, "already finished")
        null
      }
      job.status == LegacyCleanupJobStatus.STARTED && !isStale(job.lastActivityAt ?: job.startTime, now) -> {
        duplicate(jobId, "already running")
        null
      }
      else -> {
        if (job.status == LegacyCleanupJobStatus.STARTED) {
          log.warn("Legacy clean-up job {} was started at {} with no activity since {}; re-claiming", jobId, job.startTime, job.lastActivityAt)
        }
        job.start(now)
        jobRepository.saveAndFlush(job)
        JobContext(
          jobId = jobId,
          prisonId = job.prisonId,
          cutoff = job.cutoffDate,
          pending = itemRepository.findByJobIdAndStatusOrderById(jobId, LegacyCleanupItemStatus.PENDING),
        )
      }
    }
  }

  private fun isStale(lastActivity: LocalDateTime?, now: LocalDateTime) = lastActivity == null || Duration.between(lastActivity, now) > STALE_CLAIM_THRESHOLD

  /**
   * Close one container, inside the caller's fresh transaction. The decision is re-made from the live
   * prisoner-search record and must agree with the snapshot's action; a person who no longer qualifies, or now
   * qualifies differently, is skipped and left for a later run rather than closed on the old grounds.
   */
  private fun close(item: LegacyCleanupItem, prisoner: Prisoner?, context: JobContext): ItemOutcome {
    val decision = rule.decide(prisoner, context.prisonId, context.cutoff)
    return when {
      decision is CleanupDecision.Return && item.action == LegacyCleanupAction.RETURN ->
        ItemOutcome.Processed(writeService.legacyCleanupReturn(item.containerId, decision.eventDate, context.jobId, context.prisonId).event)
      decision is CleanupDecision.Transfer && item.action == LegacyCleanupAction.TRANSFER ->
        ItemOutcome.Processed(writeService.legacyCleanupTransfer(item.containerId, decision.toPrisonId, decision.dateLeft, context.jobId, context.prisonId).event)
      decision is CleanupDecision.NotEligible -> ItemOutcome.Skipped("no longer eligible: ${decision.reason}")
      else -> ItemOutcome.Skipped("owner now qualifies for ${decision.javaClass.simpleName.uppercase()}, not ${item.action}")
    }
  }

  private fun closedByThisJob(containerId: UUID, jobId: UUID): Boolean = containerRepository.findById(containerId)
    .map { container -> container.events.any { it.legacyCleanupJobId == jobId } }
    .orElse(false)

  private fun record(itemId: UUID, jobId: UUID, prisonId: String, outcome: ItemOutcome) {
    val now = LocalDateTime.now()
    val item = itemRepository.findById(itemId).orElseThrow()
    when (outcome) {
      is ItemOutcome.Processed -> {
        item.markProcessed(now, outcome.message)
        if (item.action == LegacyCleanupAction.RETURN) jobRepository.incrementReturned(jobId, now) else jobRepository.incrementTransferred(jobId, now)
      }
      is ItemOutcome.Skipped -> {
        item.markSkipped(outcome.reason, now)
        jobRepository.incrementSkipped(jobId, now)
        track(PropertyTelemetry.LEGACY_CLEANUP_ITEM_SKIPPED, jobId, prisonId, item, outcome.reason)
      }
      is ItemOutcome.Failed -> {
        item.markFailed(outcome.reason, now)
        jobRepository.incrementFailed(jobId, now)
        track(PropertyTelemetry.LEGACY_CLEANUP_ITEM_FAILED, jobId, prisonId, item, outcome.reason)
      }
    }
    itemRepository.save(item)
  }

  /**
   * Announce the closure now that it is committed. A publish failure is already logged and tracked by the
   * publisher; it is swallowed here rather than allowed to bounce the SQS message, which would re-run the
   * whole job for one lost event. The item keeps a note so the loss is visible against the job.
   */
  private fun publishQuietly(event: HmppsDomainEvent, itemId: UUID, jobId: UUID) {
    try {
      domainEventPublisher.publish(event)
    } catch (e: Exception) {
      log.error("Legacy clean-up job {}: domain event for item {} was not published", jobId, itemId, e)
      newTransaction.executeWithoutResult {
        itemRepository.findById(itemId).ifPresent { it.message = "closed, but the domain event could not be published" }
      }
    }
  }

  private fun finish(jobId: UUID) {
    val job = jobRepository.findByIdForUpdate(jobId) ?: return
    if (job.status == LegacyCleanupJobStatus.FINISHED) return
    val now = LocalDateTime.now()
    job.finish(now)
    jobRepository.saveAndFlush(job)
    log.info(
      "Legacy clean-up job {} at {} finished: {} returned, {} transferred, {} skipped, {} failed of {}",
      jobId,
      job.prisonId,
      job.returnedRecords,
      job.transferredRecords,
      job.skippedRecords,
      job.failedRecords,
      job.totalRecords,
    )
    telemetryClient.trackEvent(
      PropertyTelemetry.LEGACY_CLEANUP_FINISHED,
      mapOf(
        "prisonId" to job.prisonId,
        "jobId" to jobId.toString(),
        "totalRecords" to job.totalRecords.toString(),
        "returnedRecords" to job.returnedRecords.toString(),
        "transferredRecords" to job.transferredRecords.toString(),
        "skippedRecords" to job.skippedRecords.toString(),
        "failedRecords" to job.failedRecords.toString(),
        "durationMs" to Duration.between(job.startTime ?: now, now).toMillis().toString(),
      ),
      null,
    )
  }

  private fun duplicate(jobId: UUID, reason: String) {
    log.info("Ignoring START_CLEANUP for legacy clean-up job {}: {}", jobId, reason)
    telemetryClient.trackEvent(PropertyTelemetry.LEGACY_CLEANUP_DUPLICATE_MESSAGE, mapOf("jobId" to jobId.toString(), "reason" to reason), null)
  }

  private fun track(name: String, jobId: UUID, prisonId: String, item: LegacyCleanupItem, reason: String) {
    telemetryClient.trackEvent(
      name,
      mapOf("jobId" to jobId.toString(), "prisonId" to prisonId, "dpsId" to item.containerId.toString(), "prisonerNumber" to item.prisonerNumber, "reason" to reason),
      null,
    )
  }

  private class JobContext(val jobId: UUID, val prisonId: String, val cutoff: LocalDate, val pending: List<LegacyCleanupItem>)

  private sealed interface ItemOutcome {
    class Processed(val event: HmppsDomainEvent?, val message: String? = null) : ItemOutcome
    class Skipped(val reason: String) : ItemOutcome
    class Failed(val reason: String) : ItemOutcome
  }

  companion object {
    /** A STARTED job quiet for this long is treated as abandoned and may be re-claimed by a redelivered message. */
    val STALE_CLAIM_THRESHOLD: Duration = Duration.ofMinutes(30)
    private val log = LoggerFactory.getLogger(LegacyCleanupProcessingService::class.java)
  }
}
