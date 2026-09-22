package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertySystemUsers
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupAction
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItem
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupItemStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJob
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJobRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.cleanup.LegacyCleanupJobStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.LegacyCleanupJobDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.LegacyCleanupPreviewDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.cleanup.StartLegacyCleanupRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyTelemetry
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerStub
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.IneligibleReason
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.LegacyCleanupProcessingService
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The legacy clean-up end to end: what the preview counts for a prison holding a mix of property, the queued
 * run closing exactly what the preview said it would, and what the run leaves alone. The queue is real
 * (LocalStack), so the happy path exercises the listener; the edge cases drive the processing service
 * directly so their timing is deterministic.
 */
class LegacyCleanupResourceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var jobRepository: LegacyCleanupJobRepository

  @Autowired
  private lateinit var processingService: LegacyCleanupProcessingService

  @Autowired
  private lateinit var transactionTemplate: TransactionTemplate

  private val today = LocalDate.now()
  private val admin by lazy { setAuthorisation(username = "ADMIN_USER", roles = listOf("ROLE_PRISONER_PROPERTY__ADMIN")) }

  @BeforeEach
  fun stubs() {
    hmppsAuth.stubGrantToken()
    prisonRegister.stubGetPrisons()
  }

  @AfterEach
  fun cleanUp() {
    repository.deleteAll()
    jobRepository.deleteAll()
  }

  /**
   * LEI's backlog. Every owner is somewhere different, so each rule branch has a container behind it:
   *  - A1111AA released 60 days ago (two containers)         -> returned
   *  - B2222BB released 10 days ago                          -> too recent
   *  - C3333CC at MDI, left LEI 45 days ago                  -> transferred to MDI (exact leaving date)
   *  - D4444DD at MDI via BXI, admitted to MDI 40 days ago   -> transferred to MDI (admission-date bound)
   *  - E5555EE still at LEI                                   -> owner here
   *  - F6666FF in transit                                     -> in transit
   *  - G7777GG unknown to prisoner-search                     -> unresolved
   *  - H8888HH released 60 days ago but container due for disposal -> not a candidate at all
   */
  private fun seedLeeds(): Map<String, List<UUID>> {
    val ids = mapOf(
      "A1111AA" to listOf(repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!, repository.save(storedAt("LEI", "A1111AA", "SEAL-A2")).id!!),
      "B2222BB" to listOf(repository.save(storedAt("LEI", "B2222BB", "SEAL-B")).id!!),
      "C3333CC" to listOf(repository.save(storedAt("LEI", "C3333CC", "SEAL-C")).id!!),
      "D4444DD" to listOf(repository.save(storedAt("LEI", "D4444DD", "SEAL-D")).id!!),
      "E5555EE" to listOf(repository.save(storedAt("LEI", "E5555EE", "SEAL-E")).id!!),
      "F6666FF" to listOf(repository.save(storedAt("LEI", "F6666FF", "SEAL-F")).id!!),
      "G7777GG" to listOf(repository.save(storedAt("LEI", "G7777GG", "SEAL-G")).id!!),
      "H8888HH" to listOf(repository.save(storedAt("LEI", "H8888HH", "SEAL-H", proposedDisposalDate = today.minusDays(1))).id!!),
    )
    prisonerSearch.stubFindByNumbersDetailed(
      PrisonerStub("A1111AA", "OUT", lastMovementTypeCode = "REL", lastMovementDate = today.minusDays(60).toString()),
      PrisonerStub("B2222BB", "OUT", lastMovementTypeCode = "REL", lastMovementDate = today.minusDays(10).toString()),
      PrisonerStub("C3333CC", "MDI", previousPrisonId = "LEI", previousPrisonLeavingDate = today.minusDays(45).toString(), lastAdmissionDate = today.minusDays(44).toString()),
      PrisonerStub("D4444DD", "MDI", previousPrisonId = "BXI", previousPrisonLeavingDate = today.minusDays(41).toString(), lastAdmissionDate = today.minusDays(40).toString()),
      PrisonerStub("E5555EE", "LEI"),
      PrisonerStub("F6666FF", "TRN", lastMovementTypeCode = "TRN", lastMovementDate = today.minusDays(60).toString()),
      PrisonerStub("H8888HH", "OUT", lastMovementTypeCode = "REL", lastMovementDate = today.minusDays(60).toString()),
    )
    return ids
  }

  @Test
  fun `preview counts what a run would close against what the tiles show, and explains the rest`() {
    seedLeeds()

    val preview = webTestClient.get().uri("/active-agencies/LEI/cleanup/preview?olderThanDays=28")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody(LegacyCleanupPreviewDto::class.java)
      .returnResult().responseBody!!

    assertThat(preview.prisonId).isEqualTo("LEI")
    assertThat(preview.olderThanDays).isEqualTo(28)
    assertThat(preview.cutoffDate).isEqualTo(today.minusDays(28))
    assertThat(preview.toReturn.containers).isEqualTo(2)
    assertThat(preview.toReturn.prisoners).isEqualTo(1)
    assertThat(preview.toTransfer.containers).isEqualTo(2)
    assertThat(preview.toTransfer.prisoners).isEqualTo(2)
    // The tiles: A (2) + B read as due for return; C, D and F read as due for transfer out. H is disposal-due, so not counted.
    assertThat(preview.dueForReturnNow.containers).isEqualTo(3)
    assertThat(preview.dueForTransferOutNow.containers).isEqualTo(3)
    assertThat(preview.candidates.containers).isEqualTo(8)
    assertThat(preview.ineligible.mapValues { it.value.containers }).containsExactlyInAnyOrderEntriesOf(
      mapOf(
        IneligibleReason.TOO_RECENT to 1,
        IneligibleReason.OWNER_HERE to 1,
        IneligibleReason.IN_TRANSIT to 1,
        IneligibleReason.UNRESOLVED to 1,
      ),
    )
    assertThat(preview.ageBands.map { it.label to it.containers }).containsExactly(
      "Up to 90 days" to 4,
      "91 to 365 days" to 0,
      "Over a year" to 0,
    )
    assertNoEventsPublished()
  }

  @Test
  fun `a wider window brings the recent release into scope`() {
    seedLeeds()

    webTestClient.get().uri("/active-agencies/LEI/cleanup/preview?olderThanDays=7")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.toReturn.containers").isEqualTo(3)
      .jsonPath("$.toReturn.prisoners").isEqualTo(2)
  }

  @Test
  fun `the window is validated`() {
    webTestClient.get().uri("/active-agencies/LEI/cleanup/preview?olderThanDays=0")
      .headers(admin)
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `running the clean-up closes exactly what the preview said, releases the locations and tells NOMIS`() {
    val ids = seedLeeds()

    val job = webTestClient.post().uri("/active-agencies/LEI/cleanup")
      .headers(admin)
      .bodyValue(StartLegacyCleanupRequest(olderThanDays = 28))
      .exchange()
      .expectStatus().isAccepted
      .expectBody(LegacyCleanupJobDto::class.java)
      .returnResult().responseBody!!

    assertThat(job.status).isIn(LegacyCleanupJobStatus.PENDING, LegacyCleanupJobStatus.STARTED)
    assertThat(job.totalRecords).isEqualTo(4)
    assertThat(job.requestedBy).isEqualTo("ADMIN_USER")
    assertThat(job.cutoffDate).isEqualTo(today.minusDays(28))
    assertThat(trackedEvent(PropertyTelemetry.LEGACY_CLEANUP_REQUESTED)).containsEntry("totalRecords", "4").containsEntry("toReturn", "2").containsEntry("toTransfer", "2")

    await untilAsserted {
      webTestClient.get().uri("/active-agencies/cleanup/${job.id}")
        .headers(admin)
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.status").isEqualTo("FINISHED")
        .jsonPath("$.processedRecords").isEqualTo(4)
        .jsonPath("$.returnedRecords").isEqualTo(2)
        .jsonPath("$.transferredRecords").isEqualTo(2)
        .jsonPath("$.skippedRecords").isEqualTo(0)
        .jsonPath("$.failedRecords").isEqualTo(0)
        .jsonPath("$.items.length()").isEqualTo(4)
        .jsonPath("$.items[?(@.prisonerNumber == 'C3333CC')].plannedToPrisonId").isEqualTo("MDI")
        .jsonPath("$.items[?(@.prisonerNumber == 'D4444DD')].plannedEventDate").isEqualTo(today.minusDays(40).toString())
    }

    // Returned: dated to the release, attributed to the clean-up, stamped with the job, location freed.
    ids.getValue("A1111AA").forEach { id ->
      val container = repository.findById(id).orElseThrow()
      assertThat(container.removalOutcome).isEqualTo(RemovalOutcome.RETURNED)
      assertThat(container.removalDate).isEqualTo(today.minusDays(60))
      assertThat(container.currentInternalLocationId).isNull()
      val closing = container.events.last()
      assertThat(closing.eventType).isEqualTo(PropertyEventType.RETURNED)
      assertThat(closing.eventUserId).isEqualTo(PropertySystemUsers.LEGACY_CLEANUP)
      assertThat(closing.eventDate).isEqualTo(today.minusDays(60))
      assertThat(closing.legacyCleanupJobId).isEqualTo(job.id)
      assertThat(publishedEventsFor(id).single().changedFields).contains("removalOutcome", "currentStatus")
    }

    // Transferred: destination recorded for the history, but not advertised at MDI as awaiting arrival.
    listOf("C3333CC" to today.minusDays(45), "D4444DD" to today.minusDays(40)).forEach { (prisoner, dateLeft) ->
      val container = repository.findById(ids.getValue(prisoner).single()).orElseThrow()
      assertThat(container.removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
      assertThat(container.removalDate).isEqualTo(dateLeft)
      assertThat(container.latestTransferEvent()!!.toPrisonId).isEqualTo("MDI")
      assertThat(container.latestTransferEvent()!!.legacyCleanupJobId).isEqualTo(job.id)
      assertThat(container.receivingPrisonId).isNull()
      assertThat(publishedEventsFor(container.id!!).single().changedFields).contains("removalOutcome", "currentStatus")
    }

    // Everything else is untouched and silent.
    listOf("B2222BB", "E5555EE", "F6666FF", "G7777GG", "H8888HH").forEach { prisoner ->
      val container = repository.findById(ids.getValue(prisoner).single()).orElseThrow()
      assertThat(container.removalOutcome).isNull()
      assertThat(publishedEventsFor(container.id!!)).isEmpty()
    }
    assertThat(publishedEvents()).hasSize(4)
    assertThat(trackedEvent(PropertyTelemetry.LEGACY_CLEANUP_FINISHED)).containsEntry("returnedRecords", "2").containsEntry("transferredRecords", "2")

    // MDI's transfer-in view does not list the clean-up transfers - nothing is on its way.
    locations.stubPostLocationsBatch()
    prisonerSearch.stubFindByNumbers("C3333CC" to "MDI", "D4444DD" to "MDI")
    prisonerSearch.stubFindByPrison("MDI", "C3333CC", "D4444DD")
    webTestClient.get().uri("/property-containers/prison/MDI?dueForTransferIn=true")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)

    // And the job is listed for the prison.
    webTestClient.get().uri("/active-agencies/LEI/cleanup")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(1)
      .jsonPath("$[0].id").isEqualTo(job.id.toString())
      .jsonPath("$[0].status").isEqualTo("FINISHED")
      .jsonPath("$[0].items").doesNotExist()
  }

  @Test
  fun `a staff transfer is still awaiting arrival at the destination - only clean-up transfers are exempt`() {
    val staffTransferred = repository.save(storedAt("LEI", "S9999SS", "SEAL-S")).id!!
    webTestClient.post().uri("/property-containers/$staffTransferred/remove")
      .headers(setAuthorisation(username = "STAFF", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(mapOf("outcome" to "TRANSFERRED", "toPrisonId" to "MDI"))
      .exchange()
      .expectStatus().isOk

    assertThat(repository.findById(staffTransferred).orElseThrow().receivingPrisonId).isEqualTo("MDI")
  }

  @Test
  fun `nothing to do records a finished job immediately`() {
    repository.save(storedAt("LEI", "E5555EE", "SEAL-E"))
    prisonerSearch.stubFindByNumbersDetailed(PrisonerStub("E5555EE", "LEI"))

    webTestClient.post().uri("/active-agencies/LEI/cleanup")
      .headers(admin)
      .bodyValue(StartLegacyCleanupRequest())
      .exchange()
      .expectStatus().isAccepted
      .expectBody()
      .jsonPath("$.status").isEqualTo("FINISHED")
      .jsonPath("$.totalRecords").isEqualTo(0)
      .jsonPath("$.endTime").exists()
    assertNoEventsPublished()
  }

  @Test
  fun `a second run while one is in flight is refused`() {
    jobRepository.save(job("LEI", LegacyCleanupJobStatus.STARTED))
    prisonerSearch.stubFindByNumbersDetailed()

    webTestClient.post().uri("/active-agencies/LEI/cleanup")
      .headers(admin)
      .bodyValue(StartLegacyCleanupRequest())
      .exchange()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.userMessage").isEqualTo("A legacy clean-up is already in progress for LEI")

    // Another prison is unaffected.
    webTestClient.post().uri("/active-agencies/MDI/cleanup")
      .headers(admin)
      .bodyValue(StartLegacyCleanupRequest())
      .exchange()
      .expectStatus().isAccepted
  }

  @Test
  fun `a container removed by staff between request and run is skipped, and a person who came back is left alone`() {
    val removedByStaff = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val cameBack = repository.save(storedAt("LEI", "B2222BB", "SEAL-B")).id!!
    val stillGone = repository.save(storedAt("LEI", "C3333CC", "SEAL-C")).id!!
    val job = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.PENDING).also { job ->
        job.items += LegacyCleanupItem(job, removedByStaff, "A1111AA", LegacyCleanupAction.RETURN, today.minusDays(60))
        job.items += LegacyCleanupItem(job, cameBack, "B2222BB", LegacyCleanupAction.RETURN, today.minusDays(60))
        job.items += LegacyCleanupItem(job, stillGone, "C3333CC", LegacyCleanupAction.TRANSFER, today.minusDays(45), "MDI")
        job.totalRecords = 3
      },
    )
    // Staff got there first with A's container.
    webTestClient.post().uri("/property-containers/$removedByStaff/remove")
      .headers(setAuthorisation(username = "STAFF", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(mapOf("outcome" to "RETURNED"))
      .exchange()
      .expectStatus().isOk
    // B has since been received back into LEI; C is still at MDI.
    prisonerSearch.stubFindByNumbersDetailed(
      PrisonerStub("A1111AA", "OUT", lastMovementTypeCode = "REL", lastMovementDate = today.minusDays(60).toString()),
      PrisonerStub("B2222BB", "LEI"),
      PrisonerStub("C3333CC", "MDI", previousPrisonId = "LEI", previousPrisonLeavingDate = today.minusDays(45).toString()),
    )

    processingService.process(job.id!!)

    val finished = webTestClient.get().uri("/active-agencies/cleanup/${job.id}")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody(LegacyCleanupJobDto::class.java)
      .returnResult().responseBody!!
    assertThat(finished.status).isEqualTo(LegacyCleanupJobStatus.FINISHED)
    assertThat(finished.transferredRecords).isEqualTo(1)
    assertThat(finished.skippedRecords).isEqualTo(2)
    assertThat(finished.items!!.associate { it.prisonerNumber to it.status }).containsExactlyInAnyOrderEntriesOf(
      mapOf("A1111AA" to LegacyCleanupItemStatus.SKIPPED, "B2222BB" to LegacyCleanupItemStatus.SKIPPED, "C3333CC" to LegacyCleanupItemStatus.PROCESSED),
    )
    assertThat(finished.items!!.first { it.prisonerNumber == "A1111AA" }.message).startsWith("already removed")
    assertThat(finished.items!!.first { it.prisonerNumber == "B2222BB" }.message).isEqualTo("no longer eligible: OWNER_HERE")
    assertThat(repository.findById(cameBack).orElseThrow().removalOutcome).isNull()
    assertThat(repository.findById(stillGone).orElseThrow().removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    // One event for the staff removal, one for the clean-up transfer - nothing for the skips.
    assertThat(publishedEvents().map { it.dpsId }).containsExactly(removedByStaff.toString(), stillGone.toString())
  }

  @Test
  fun `a redelivered start message for a running or finished job is ignored`() {
    val container = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val running = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.STARTED, startTime = LocalDateTime.now().minusMinutes(5)).also { job ->
        job.items += LegacyCleanupItem(job, container, "A1111AA", LegacyCleanupAction.RETURN, today.minusDays(60))
        job.totalRecords = 1
      },
    )
    prisonerSearch.stubFindByNumbersDetailed(PrisonerStub("A1111AA", "OUT", lastMovementTypeCode = "REL", lastMovementDate = today.minusDays(60).toString()))

    processingService.process(running.id!!)

    assertThat(repository.findById(container).orElseThrow().removalOutcome).isNull()
    assertThat(trackedEvent(PropertyTelemetry.LEGACY_CLEANUP_DUPLICATE_MESSAGE)).containsEntry("reason", "already running")
    assertNoEventsPublished()
  }

  @Test
  fun `a job abandoned by a dead pod is re-claimed and resumed from its pending items`() {
    val done = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val pending = repository.save(storedAt("LEI", "A1111AA", "SEAL-A2")).id!!
    val stale = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.STARTED, startTime = LocalDateTime.now().minusHours(2), lastActivityAt = LocalDateTime.now().minusHours(1)).also { job ->
        job.items += LegacyCleanupItem(job, done, "A1111AA", LegacyCleanupAction.RETURN, today.minusDays(60), status = LegacyCleanupItemStatus.PROCESSED)
        job.items += LegacyCleanupItem(job, pending, "A1111AA", LegacyCleanupAction.RETURN, today.minusDays(60))
        job.totalRecords = 2
        job.returnedRecords = 1
      },
    )
    prisonerSearch.stubFindByNumbersDetailed(PrisonerStub("A1111AA", "OUT", lastMovementTypeCode = "REL", lastMovementDate = today.minusDays(60).toString()))

    processingService.process(stale.id!!)

    assertThat(repository.findById(done).orElseThrow().removalOutcome).isNull()
    assertThat(repository.findById(pending).orElseThrow().removalOutcome).isEqualTo(RemovalOutcome.RETURNED)
    assertThat(publishedEvents().map { it.dpsId }).containsExactly(pending.toString())
    val finished = jobRepository.findById(stale.id!!).orElseThrow()
    assertThat(finished.status).isEqualTo(LegacyCleanupJobStatus.FINISHED)
    assertThat(finished.returnedRecords).isEqualTo(2)
  }

  @Test
  fun `a container closed on an earlier delivery whose bookkeeping was lost is settled, not closed twice`() {
    val container = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val job = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.PENDING).also { job ->
        job.items += LegacyCleanupItem(job, container, "A1111AA", LegacyCleanupAction.RETURN, today.minusDays(60))
        job.totalRecords = 1
      },
    )
    // The earlier delivery got as far as the container write.
    transactionTemplate.executeWithoutResult {
      val c = repository.findById(container).orElseThrow()
      c.events.add(PropertyEvent(c, PropertyEventType.RETURNED, LocalDateTime.now(), PropertySystemUsers.LEGACY_CLEANUP, eventDate = today.minusDays(60), fromPrisonId = "LEI", legacyCleanupJobId = job.id))
      c.removalOutcome = RemovalOutcome.RETURNED
      c.removalDate = today.minusDays(60)
      c.refreshDerivedState()
    }
    prisonerSearch.stubFindByNumbersDetailed(PrisonerStub("A1111AA", "OUT", lastMovementTypeCode = "REL", lastMovementDate = today.minusDays(60).toString()))

    processingService.process(job.id!!)

    val finished = jobRepository.findById(job.id!!).orElseThrow()
    assertThat(finished.status).isEqualTo(LegacyCleanupJobStatus.FINISHED)
    assertThat(finished.returnedRecords).isEqualTo(1)
    assertThat(finished.skippedRecords).isEqualTo(0)
    assertThat(repository.findById(container).orElseThrow().events.count { it.eventType == PropertyEventType.RETURNED }).isEqualTo(1)
    assertNoEventsPublished()
  }

  @Test
  fun `prisoner-search being down means nothing is closed`() {
    seedLeeds()
    prisonerSearch.stubFindByNumbersFails()

    webTestClient.get().uri("/active-agencies/LEI/cleanup/preview")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.toReturn.containers").isEqualTo(0)
      .jsonPath("$.toTransfer.containers").isEqualTo(0)
      .jsonPath("$.ineligible.UNRESOLVED.containers").isEqualTo(8)
  }

  @Test
  fun `an unknown job is a 404`() {
    webTestClient.get().uri("/active-agencies/cleanup/${UUID.randomUUID()}")
      .headers(admin)
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `every endpoint requires a token and the admin role`() {
    val jobId = UUID.randomUUID()
    val calls = listOf(
      { webTestClient.get().uri("/active-agencies/LEI/cleanup/preview") },
      { webTestClient.post().uri("/active-agencies/LEI/cleanup").bodyValue(StartLegacyCleanupRequest()) },
      { webTestClient.get().uri("/active-agencies/LEI/cleanup") },
      { webTestClient.get().uri("/active-agencies/cleanup/$jobId") },
    )
    calls.forEach { call -> call().exchange().expectStatus().isUnauthorized }
    calls.forEach { call ->
      call().headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RW"))).exchange().expectStatus().isForbidden
    }
  }

  private fun job(
    prisonId: String,
    status: LegacyCleanupJobStatus,
    startTime: LocalDateTime? = null,
    lastActivityAt: LocalDateTime? = startTime,
  ) = LegacyCleanupJob(
    prisonId = prisonId,
    olderThanDays = 28,
    cutoffDate = today.minusDays(28),
    requestedBy = "ADMIN_USER",
    requestedAt = LocalDateTime.now().minusMinutes(10),
    status = status,
    startTime = startTime,
    lastActivityAt = lastActivityAt,
  )

  private fun storedAt(prisonId: String, prisonerNumber: String, seal: String, proposedDisposalDate: LocalDate? = null): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = LocalDateTime.parse("2025-01-01T09:00:00"),
      currentSealNumber = seal,
      proposedDisposalDate = proposedDisposalDate,
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2025-01-01T09:00:00"), "A_USER", sealNumber = seal, toInternalLocationId = UUID.randomUUID(), toPrisonId = prisonId),
    )
    container.refreshDerivedState()
    assertThat(container.currentStatusValue).isEqualTo(ContainerStatus.STORED)
    return container
  }
}
