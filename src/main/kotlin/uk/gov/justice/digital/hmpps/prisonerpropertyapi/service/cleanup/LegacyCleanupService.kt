package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.CleanupCandidate
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupAction
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJob
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJobRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.CleanupAgeBandDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.CleanupCountDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.LegacyCleanupJobDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.LegacyCleanupPreviewDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.LegacyCleanupListener
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.LegacyCleanupMessage
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.LegacyCleanupMessageType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyTelemetry
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerStatusResolver
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The request side of the legacy property clean-up: what a run *would* close ([preview]), starting one
 * ([start]) and reading back its progress. The run itself happens on the SQS listener thread in
 * [LegacyCleanupProcessingService]. See `docs/legacy-cleanup.md`.
 *
 * Preview and start share one [plan], so the numbers an admin is shown are the items the job is created with.
 * The plan calls prisoner-search, and does so with no transaction open - the bulk lookup can take seconds for a
 * large prison and there is no reason to hold a pooled connection across it.
 */
@Service
class LegacyCleanupService(
  private val repository: PropertyContainerRepository,
  private val jobRepository: LegacyCleanupJobRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val rule: LegacyCleanupRule,
  private val statusResolver: ContainerStatusResolver,
  private val hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
  private val telemetryClient: TelemetryClient,
  transactionManager: PlatformTransactionManager,
) {
  private val transaction = TransactionTemplate(transactionManager)

  private val queue by lazy {
    hmppsQueueService.findByQueueId(LegacyCleanupListener.QUEUE_ID)
      ?: throw IllegalStateException("hmpps.sqs queue '${LegacyCleanupListener.QUEUE_ID}' is not configured")
  }

  fun preview(prisonId: String, olderThanDays: Int): LegacyCleanupPreviewDto = plan(prisonId, olderThanDays).toPreview()

  /**
   * Create a job from the current plan and hand it to the listener. The items are saved with the job in one
   * transaction and the queue message is sent only after that commits - a listener that received it first
   * would find nothing to claim. A plan with nothing to do is recorded as finished on the spot so the admin
   * still gets a job to look at, and no message is sent.
   *
   * One job may be in flight per prison. The pre-check gives a clean 409; the partial unique index behind it
   * catches the race between two admins, surfacing as a constraint violation mapped to the same 409.
   */
  fun start(prisonId: String, olderThanDays: Int, username: String): LegacyCleanupJobDto {
    if (jobRepository.existsByPrisonIdAndStatusIn(prisonId, LegacyCleanupJob.ACTIVE_STATUSES)) {
      throw LegacyCleanupJobActiveException(prisonId)
    }
    val plan = plan(prisonId, olderThanDays)
    val job = try {
      transaction.execute { saveJob(plan, username) }!!
    } catch (e: DataIntegrityViolationException) {
      throw LegacyCleanupJobActiveException(prisonId)
    }
    telemetryClient.trackEvent(
      PropertyTelemetry.LEGACY_CLEANUP_REQUESTED,
      mapOf(
        "prisonId" to prisonId,
        "jobId" to job.id.toString(),
        "olderThanDays" to olderThanDays.toString(),
        "totalRecords" to job.totalRecords.toString(),
        "toReturn" to plan.toReturn.size.toString(),
        "toTransfer" to plan.toTransfer.size.toString(),
        "requestedBy" to username,
      ),
      null,
    )
    return LegacyCleanupJobDto.from(job, includeItems = false)
  }

  private fun saveJob(plan: CleanupPlan, username: String): LegacyCleanupJob {
    val now = LocalDateTime.now()
    val job = LegacyCleanupJob(
      prisonId = plan.prisonId,
      olderThanDays = plan.olderThanDays,
      cutoffDate = plan.cutoff,
      requestedBy = username,
      requestedAt = now,
      totalRecords = plan.items.size,
    )
    plan.items.forEach { planned ->
      job.items.add(
        LegacyCleanupItem(
          job = job,
          containerId = planned.containerId,
          prisonerNumber = planned.prisonerNumber,
          action = planned.action,
          plannedEventDate = planned.eventDate,
          plannedToPrisonId = planned.toPrisonId,
        ),
      )
    }
    if (job.items.isEmpty()) {
      job.finish(now)
    } else {
      sendStartMessageAfterCommit(job)
    }
    return jobRepository.saveAndFlush(job)
  }

  private fun sendStartMessageAfterCommit(job: LegacyCleanupJob) {
    TransactionSynchronizationManager.registerSynchronization(
      object : TransactionSynchronization {
        override fun afterCommit() {
          val message = LegacyCleanupMessage(LegacyCleanupMessageType.START_CLEANUP, job.id!!)
          queue.sqsClient.sendMessage(
            SendMessageRequest.builder()
              .queueUrl(queue.queueUrl)
              .messageBody(objectMapper.writeValueAsString(message))
              .build(),
          ).get()
          log.info("Sent START_CLEANUP for legacy clean-up job {} ({} items at {})", job.id, job.totalRecords, job.prisonId)
        }
      },
    )
  }

  @Transactional(readOnly = true)
  fun getJobs(prisonId: String): List<LegacyCleanupJobDto> = jobRepository.findByPrisonIdOrderByRequestedAtDesc(prisonId).map { LegacyCleanupJobDto.from(it, includeItems = false) }

  @Transactional(readOnly = true)
  fun getJob(id: UUID): LegacyCleanupJobDto = jobRepository.findById(id)
    .map { LegacyCleanupJobDto.from(it, includeItems = true) }
    .orElseThrow { LegacyCleanupJobNotFoundException(id) }

  /**
   * Classify every live container at [prisonId] against the rule. One grouped query for the candidates, one
   * bulk prisoner-search lookup for their owners (chunked by the client), then the pure decision per owner.
   *
   * Alongside the decision, each owner is also classified with the same [ContainerStatusResolver] rule the
   * summary tiles use, so the preview can show "due for return now" and "due for transfer out now" figures
   * that match the tiles exactly - that is what tells the admin how much of the visible backlog the window
   * will clear.
   */
  private fun plan(prisonId: String, olderThanDays: Int): CleanupPlan {
    val today = LocalDate.now()
    val cutoff = today.minusDays(olderThanDays.toLong())
    val candidates = repository.findCleanupCandidates(prisonId, today)
    val prisonerNumbers = candidates.mapTo(mutableSetOf()) { it.prisonerNumber }
    if (prisonerNumbers.size > MAX_CANDIDATE_PRISONERS) {
      throw LegacyCleanupTooLargeException(prisonId, prisonerNumbers.size, MAX_CANDIDATE_PRISONERS)
    }
    val prisoners: Map<String, Prisoner> = if (prisonerNumbers.isEmpty()) emptyMap() else prisonerSearchClient.getPrisoners(prisonerNumbers)
    if (prisoners.size < prisonerNumbers.size) {
      log.warn("Legacy clean-up plan for {} resolved {} of {} property owners; the rest are ineligible", prisonId, prisoners.size, prisonerNumbers.size)
    }

    val decisions = prisonerNumbers.associateWith { rule.decide(prisoners[it], prisonId, cutoff) }
    val shownAs = prisonerNumbers.associateWith { statusResolver.ownerLocation(prisoners[it], prisonId) }

    return CleanupPlan(
      prisonId = prisonId,
      olderThanDays = olderThanDays,
      cutoff = cutoff,
      today = today,
      candidates = candidates,
      decisions = decisions,
      readsAs = candidates.associate { it.id to shownAs.getValue(it.prisonerNumber).statusFor(it.status) },
    )
  }

  private companion object {
    /**
     * Same ceiling as PrisonStatusOverlayFactory: far above any real establishment, so exceeding it means a
     * fault rather than a big prison, and better refused than bound into an unreasonable SQL statement.
     */
    const val MAX_CANDIDATE_PRISONERS = 20_000
    private val log = LoggerFactory.getLogger(LegacyCleanupService::class.java)
  }
}

/** A container the clean-up will close, and how. */
data class PlannedItem(
  val containerId: UUID,
  val prisonerNumber: String,
  val action: LegacyCleanupAction,
  val eventDate: LocalDate,
  val toPrisonId: String?,
)

/** The outcome of classifying a prison's live containers: the items to close, and everything needed to explain the rest. */
class CleanupPlan(
  val prisonId: String,
  val olderThanDays: Int,
  val cutoff: LocalDate,
  private val today: LocalDate,
  private val candidates: List<CleanupCandidate>,
  private val decisions: Map<String, CleanupDecision>,
  private val readsAs: Map<UUID, ContainerStatus>,
) {
  val items: List<PlannedItem> = candidates.mapNotNull { candidate ->
    when (val decision = decisions.getValue(candidate.prisonerNumber)) {
      is CleanupDecision.Return -> PlannedItem(candidate.id, candidate.prisonerNumber, LegacyCleanupAction.RETURN, decision.eventDate, null)
      is CleanupDecision.Transfer -> PlannedItem(candidate.id, candidate.prisonerNumber, LegacyCleanupAction.TRANSFER, decision.dateLeft, decision.toPrisonId)
      is CleanupDecision.NotEligible -> null
    }
  }

  val toReturn get() = items.filter { it.action == LegacyCleanupAction.RETURN }
  val toTransfer get() = items.filter { it.action == LegacyCleanupAction.TRANSFER }

  fun toPreview(): LegacyCleanupPreviewDto {
    val ineligible = candidates
      .mapNotNull { candidate -> (decisions.getValue(candidate.prisonerNumber) as? CleanupDecision.NotEligible)?.let { it.reason to candidate } }
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, rows) -> count(rows) }
    return LegacyCleanupPreviewDto(
      prisonId = prisonId,
      olderThanDays = olderThanDays,
      cutoffDate = cutoff,
      generatedAt = LocalDateTime.now(),
      toReturn = count(toReturn),
      toTransfer = count(toTransfer),
      dueForReturnNow = count(candidates.filter { readsAs[it.id] == ContainerStatus.DUE_FOR_RETURN }),
      dueForTransferOutNow = count(candidates.filter { readsAs[it.id] == ContainerStatus.DUE_FOR_TRANSFER_OUT }),
      candidates = count(candidates),
      ineligible = ineligible,
      ageBands = AGE_BANDS.map { band ->
        CleanupAgeBandDto(
          label = band.label,
          fromDays = band.fromDays,
          toDays = band.toDays,
          containers = items.count { band.contains(ChronoUnit.DAYS.between(it.eventDate, today).toInt()) },
        )
      },
    )
  }

  private fun count(rows: List<CleanupCandidate>) = CleanupCountDto(rows.size, rows.distinctBy { it.prisonerNumber }.size)

  @JvmName("countPlanned")
  private fun count(rows: List<PlannedItem>) = CleanupCountDto(rows.size, rows.distinctBy { it.prisonerNumber }.size)

  private data class AgeBand(val label: String, val fromDays: Int, val toDays: Int?) {
    fun contains(days: Int) = days >= fromDays && (toDays == null || days <= toDays)
  }

  private companion object {
    /** How stale the backlog is, for choosing a window: within a quarter, within a year, older. */
    val AGE_BANDS = listOf(
      AgeBand("Up to 90 days", 0, 90),
      AgeBand("91 to 365 days", 91, 365),
      AgeBand("Over a year", 366, null),
    )
  }
}
