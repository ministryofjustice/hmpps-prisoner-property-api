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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.NomisContainerCode
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.PropertyTelemetry
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.LocationsApiExtension.Companion.locations
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonRegisterApiExtension.Companion.prisonRegister
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerSearchApiExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.wiremock.PrisonerStub
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.CleanupReason
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.IneligibleReason
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.LegacyCleanupProcessingService
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.LegacyCleanupRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The legacy clean-up end to end: what the preview counts for a prison holding a mix of property under the
 * 13-month retention rule, the queued run closing exactly what the preview said it would, and what the run
 * leaves alone. The queue is real
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
  private val cutoff = LegacyCleanupRule.cutoff(today)
  private val twoYearsAgo = today.minusYears(2)
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
   * LEI's backlog. Every owner is somewhere different, so each branch of the rule has a container behind it:
   *  - A1111AA released two years ago (two containers)                -> removed (released)
   *  - B2222BB released six months ago                                 -> too recent
   *  - C3333CC at MDI, left LEI 15 months ago                          -> removed (transferred; exact leaving date)
   *  - D4444DD at MDI via BXI, admitted to MDI 14 months ago           -> removed (transferred; admission-date bound)
   *  - E5555EE still at LEI                                            -> owner here
   *  - F6666FF in transit                                              -> in transit
   *  - G7777GG unknown to prisoner-search                              -> unresolved
   *  - H8888HH released two years ago, disposal date already passed    -> removed (released)
   *  - J1010JJ died in custody three years ago                         -> removed (died)
   *  - K1111KK absconded six years ago                                 -> removed (escaped)
   *  - L1212LL released two years ago, confiscated property            -> confiscated, never closed
   *  - M1313MM released two years ago, disposal date next month        -> disposal date not reached
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
      "J1010JJ" to listOf(repository.save(storedAt("LEI", "J1010JJ", "SEAL-J")).id!!),
      "K1111KK" to listOf(repository.save(storedAt("LEI", "K1111KK", "SEAL-K")).id!!),
      "L1212LL" to listOf(repository.save(storedAt("LEI", "L1212LL", "SEAL-L", containerType = ContainerType.CONFISCATED)).id!!),
      "M1313MM" to listOf(repository.save(storedAt("LEI", "M1313MM", "SEAL-M", proposedDisposalDate = today.plusMonths(1))).id!!),
    )
    prisonerSearch.stubFindByNumbersDetailed(
      released("A1111AA", twoYearsAgo),
      released("B2222BB", today.minusMonths(6)),
      PrisonerStub("C3333CC", "MDI", previousPrisonId = "LEI", previousPrisonLeavingDate = today.minusMonths(15).toString(), lastAdmissionDate = today.minusMonths(15).plusDays(1).toString()),
      PrisonerStub("D4444DD", "MDI", previousPrisonId = "BXI", previousPrisonLeavingDate = today.minusMonths(14).minusDays(1).toString(), lastAdmissionDate = today.minusMonths(14).toString()),
      PrisonerStub("E5555EE", "LEI"),
      PrisonerStub("F6666FF", "TRN", lastMovementTypeCode = "TRN", lastMovementDate = twoYearsAgo.toString()),
      released("H8888HH", twoYearsAgo),
      released("J1010JJ", today.minusYears(3), reasonCode = "DEC"),
      released("K1111KK", today.minusYears(6), reasonCode = "UAL"),
      released("L1212LL", twoYearsAgo),
      released("M1313MM", twoYearsAgo),
    )
    return ids
  }

  private fun released(prisonerNumber: String, on: LocalDate, reasonCode: String = "CR") = PrisonerStub(prisonerNumber, "OUT", lastMovementTypeCode = "REL", lastMovementReasonCode = reasonCode, lastMovementDate = on.toString())

  /** Every container the seed expects closed, with the date it should be dated to. */
  private fun expectedClosures(ids: Map<String, List<UUID>>): Map<UUID, LocalDate> = mapOf(
    "A1111AA" to twoYearsAgo,
    "C3333CC" to today.minusMonths(15),
    "D4444DD" to today.minusMonths(14),
    "H8888HH" to twoYearsAgo,
    "J1010JJ" to today.minusYears(3),
    "K1111KK" to today.minusYears(6),
  ).flatMap { (prisoner, date) -> ids.getValue(prisoner).map { it to date } }.toMap()

  @Test
  fun `preview applies the 13-month rule, counts against what the tiles show, and explains the rest`() {
    seedLeeds()

    val preview = webTestClient.get().uri("/active-agencies/LEI/cleanup/preview")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody(LegacyCleanupPreviewDto::class.java)
      .returnResult().responseBody!!

    assertThat(preview.prisonId).isEqualTo("LEI")
    assertThat(preview.retentionMonths).isEqualTo(13)
    assertThat(preview.cutoffDate).isEqualTo(today.minusMonths(13))
    assertThat(preview.toRemove.containers).isEqualTo(7)
    assertThat(preview.toRemove.prisoners).isEqualTo(6)
    assertThat(preview.toRemoveByReason.mapValues { it.value.containers }).containsExactlyInAnyOrderEntriesOf(
      mapOf(
        CleanupReason.RELEASED to 3,
        CleanupReason.TRANSFERRED to 2,
        CleanupReason.DIED to 1,
        CleanupReason.ESCAPED to 1,
      ),
    )
    // The tiles: everyone out reads as due for return except H, whose disposal date has arisen; C, D and F read
    // as due for transfer out.
    assertThat(preview.dueForReturnNow.containers).isEqualTo(7)
    assertThat(preview.dueForTransferOutNow.containers).isEqualTo(3)
    assertThat(preview.candidates.containers).isEqualTo(13)
    assertThat(preview.ineligible.mapValues { it.value.containers }).containsExactlyInAnyOrderEntriesOf(
      mapOf(
        IneligibleReason.TOO_RECENT to 1,
        IneligibleReason.OWNER_HERE to 1,
        IneligibleReason.IN_TRANSIT to 1,
        IneligibleReason.UNRESOLVED to 1,
        IneligibleReason.CONFISCATED to 1,
        IneligibleReason.DISPOSAL_DATE_NOT_REACHED to 1,
      ),
    )
    assertThat(preview.ageBands.map { it.label to it.containers }).containsExactly(
      "13 months to 2 years" to 2,
      "2 to 5 years" to 4,
      "Over 5 years" to 1,
    )
    assertNoEventsPublished()
  }

  @Test
  fun `a window sent by an older client is ignored - the rule is fixed`() {
    seedLeeds()

    webTestClient.get().uri("/active-agencies/LEI/cleanup/preview?olderThanDays=7")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.cutoffDate").isEqualTo(cutoff.toString())
      .jsonPath("$.toRemove.containers").isEqualTo(7)
      .jsonPath("$.olderThanDays").doesNotExist()
  }

  @Test
  fun `running the clean-up removes exactly what the preview said, releases the locations and tells NOMIS`() {
    val ids = seedLeeds()

    // An older client still sends a window in the body; it is ignored.
    val job = webTestClient.post().uri("/active-agencies/LEI/cleanup")
      .headers(admin)
      .bodyValue(mapOf("olderThanDays" to 28))
      .exchange()
      .expectStatus().isAccepted
      .expectBody(LegacyCleanupJobDto::class.java)
      .returnResult().responseBody!!

    assertThat(job.status).isIn(LegacyCleanupJobStatus.PENDING, LegacyCleanupJobStatus.STARTED)
    assertThat(job.totalRecords).isEqualTo(7)
    assertThat(job.requestedBy).isEqualTo("ADMIN_USER")
    assertThat(job.cutoffDate).isEqualTo(cutoff)
    assertThat(job.olderThanDays).isNull()
    assertThat(trackedEvent(PropertyTelemetry.LEGACY_CLEANUP_REQUESTED)).containsEntry("totalRecords", "7").containsEntry("cutoffDate", cutoff.toString())

    await untilAsserted {
      webTestClient.get().uri("/active-agencies/cleanup/${job.id}")
        .headers(admin)
        .exchange()
        .expectStatus().isOk
        .expectBody()
        .jsonPath("$.status").isEqualTo("FINISHED")
        .jsonPath("$.processedRecords").isEqualTo(7)
        .jsonPath("$.removedRecords").isEqualTo(7)
        .jsonPath("$.returnedRecords").isEqualTo(0)
        .jsonPath("$.transferredRecords").isEqualTo(0)
        .jsonPath("$.skippedRecords").isEqualTo(0)
        .jsonPath("$.failedRecords").isEqualTo(0)
        .jsonPath("$.items.length()").isEqualTo(7)
        .jsonPath("$.items[?(@.action != 'REMOVE')]").isEmpty
        .jsonPath("$.items[?(@.prisonerNumber == 'C3333CC')].plannedToPrisonId").isEqualTo("MDI")
        .jsonPath("$.items[?(@.prisonerNumber == 'D4444DD')].plannedEventDate").isEqualTo(today.minusMonths(14).toString())
    }

    // Removed: dated to when the person left, attributed to the clean-up, stamped with the job, location freed.
    expectedClosures(ids).forEach { (id, leftOn) ->
      val container = repository.findById(id).orElseThrow()
      assertThat(container.removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
      assertThat(container.removalDate).isEqualTo(leftOn)
      assertThat(container.currentInternalLocationId).isNull()
      assertThat(container.receivingPrisonId).isNull()
      assertThat(container.removedByLegacyCleanup()).isTrue()
      val closing = container.events.maxBy { it.eventDateTime }
      assertThat(closing.eventType).isEqualTo(PropertyEventType.REMOVED)
      assertThat(closing.eventUserId).isEqualTo(PropertySystemUsers.LEGACY_CLEANUP)
      assertThat(closing.eventDate).isEqualTo(leftOn)
      assertThat(closing.legacyCleanupJobId).isEqualTo(job.id)
      assertThat(publishedEventsFor(id).single().changedFields).contains("removalOutcome", "currentStatus")
    }

    // Everything else is untouched and silent.
    listOf("B2222BB", "E5555EE", "F6666FF", "G7777GG", "L1212LL", "M1313MM").forEach { prisoner ->
      val container = repository.findById(ids.getValue(prisoner).single()).orElseThrow()
      assertThat(container.removalOutcome).isNull()
      assertThat(publishedEventsFor(container.id!!)).isEmpty()
    }
    assertThat(publishedEvents()).hasSize(7)
    assertThat(trackedEvent(PropertyTelemetry.LEGACY_CLEANUP_FINISHED)).containsEntry("removedRecords", "7")

    // The history flags the closure as a legacy clean-up, so it can be told apart from a removal in NOMIS.
    webTestClient.get().uri("/property-containers/${ids.getValue("A1111AA").first()}/events")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$[0].eventType").isEqualTo("REMOVED")
      .jsonPath("$[0].legacyCleanup").isEqualTo(true)
      .jsonPath("$[1].eventType").isEqualTo("CREATED_SEALED")
      .jsonPath("$[1].legacyCleanup").isEqualTo(false)

    // MDI's transfer-in view lists nothing - nothing is on its way.
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
  fun `NOMIS sending a cleared container as active again leaves it removed`() {
    val container = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val job = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.PENDING).also { job ->
        job.items += LegacyCleanupItem(job, container, "A1111AA", LegacyCleanupAction.REMOVE, twoYearsAgo)
        job.totalRecords = 1
      },
    )
    prisonerSearch.stubFindByNumbersDetailed(released("A1111AA", twoYearsAgo))
    processingService.process(job.id!!)
    assertThat(repository.findById(container).orElseThrow().removalOutcome).isEqualTo(RemovalOutcome.REMOVED)

    webTestClient.post().uri("/sync/property-containers/upsert")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__SYNC")))
      .bodyValue(
        SyncPropertyContainerRequest(
          nomisPropertyContainerId = 123,
          dpsId = container,
          prisonerNumber = "A1111AA",
          prisonId = "LEI",
          containerCode = NomisContainerCode.BULK,
          internalLocationId = UUID.randomUUID(),
          sealMark = "SEAL-A1",
          createDateTime = LocalDateTime.parse("2025-01-01T09:00:00"),
          createUsername = "A_USER",
          modifyDateTime = LocalDateTime.now(),
          modifyUsername = "NOMIS_USER",
          active = true,
        ),
      )
      .exchange()
      .expectStatus().isOk

    val after = repository.findById(container).orElseThrow()
    assertThat(after.removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
    assertThat(after.removalDate).isEqualTo(twoYearsAgo)
    assertThat(after.currentInternalLocationId).isNull()
    assertThat(after.events.map { it.eventType }).doesNotContain(PropertyEventType.REACTIVATED, PropertyEventType.MOVED)
    assertThat(trackedEvent(PropertyTelemetry.SYNC_LEGACY_CLEANUP_RETAINED)).containsEntry("dpsId", container.toString())
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
      .exchange()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.userMessage").isEqualTo("A legacy clean-up is already in progress for LEI")

    // Another prison is unaffected.
    webTestClient.post().uri("/active-agencies/MDI/cleanup")
      .headers(admin)
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
        job.items += LegacyCleanupItem(job, removedByStaff, "A1111AA", LegacyCleanupAction.REMOVE, twoYearsAgo)
        job.items += LegacyCleanupItem(job, cameBack, "B2222BB", LegacyCleanupAction.REMOVE, twoYearsAgo)
        job.items += LegacyCleanupItem(job, stillGone, "C3333CC", LegacyCleanupAction.REMOVE, today.minusMonths(15), "MDI")
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
      released("A1111AA", twoYearsAgo),
      PrisonerStub("B2222BB", "LEI"),
      PrisonerStub("C3333CC", "MDI", previousPrisonId = "LEI", previousPrisonLeavingDate = today.minusMonths(15).toString()),
    )

    processingService.process(job.id!!)

    val finished = webTestClient.get().uri("/active-agencies/cleanup/${job.id}")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody(LegacyCleanupJobDto::class.java)
      .returnResult().responseBody!!
    assertThat(finished.status).isEqualTo(LegacyCleanupJobStatus.FINISHED)
    assertThat(finished.removedRecords).isEqualTo(1)
    assertThat(finished.skippedRecords).isEqualTo(2)
    assertThat(finished.items!!.associate { it.prisonerNumber to it.status }).containsExactlyInAnyOrderEntriesOf(
      mapOf("A1111AA" to LegacyCleanupItemStatus.SKIPPED, "B2222BB" to LegacyCleanupItemStatus.SKIPPED, "C3333CC" to LegacyCleanupItemStatus.PROCESSED),
    )
    assertThat(finished.items!!.first { it.prisonerNumber == "A1111AA" }.message).startsWith("already removed")
    assertThat(finished.items!!.first { it.prisonerNumber == "B2222BB" }.message).isEqualTo("no longer eligible: OWNER_HERE")
    assertThat(repository.findById(cameBack).orElseThrow().removalOutcome).isNull()
    assertThat(repository.findById(stillGone).orElseThrow().removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
    // One event for the staff removal, one for the clean-up removal - nothing for the skips.
    assertThat(publishedEvents().map { it.dpsId }).containsExactly(removedByStaff.toString(), stillGone.toString())
  }

  @Test
  fun `a redelivered start message for a running or finished job is ignored`() {
    val container = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val running = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.STARTED, startTime = LocalDateTime.now().minusMinutes(5)).also { job ->
        job.items += LegacyCleanupItem(job, container, "A1111AA", LegacyCleanupAction.REMOVE, twoYearsAgo)
        job.totalRecords = 1
      },
    )
    prisonerSearch.stubFindByNumbersDetailed(released("A1111AA", twoYearsAgo))

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
        job.items += LegacyCleanupItem(job, done, "A1111AA", LegacyCleanupAction.REMOVE, twoYearsAgo, status = LegacyCleanupItemStatus.PROCESSED)
        job.items += LegacyCleanupItem(job, pending, "A1111AA", LegacyCleanupAction.REMOVE, twoYearsAgo)
        job.totalRecords = 2
        job.removedRecords = 1
      },
    )
    prisonerSearch.stubFindByNumbersDetailed(released("A1111AA", twoYearsAgo))

    processingService.process(stale.id!!)

    assertThat(repository.findById(done).orElseThrow().removalOutcome).isNull()
    assertThat(repository.findById(pending).orElseThrow().removalOutcome).isEqualTo(RemovalOutcome.REMOVED)
    assertThat(publishedEvents().map { it.dpsId }).containsExactly(pending.toString())
    val finished = jobRepository.findById(stale.id!!).orElseThrow()
    assertThat(finished.status).isEqualTo(LegacyCleanupJobStatus.FINISHED)
    assertThat(finished.removedRecords).isEqualTo(2)
  }

  @Test
  fun `items planned as returned or transferred before the 13-month rule are skipped, not closed`() {
    val toReturn = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val toTransfer = repository.save(storedAt("LEI", "C3333CC", "SEAL-C")).id!!
    val job = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.PENDING).also { job ->
        job.items += LegacyCleanupItem(job, toReturn, "A1111AA", LegacyCleanupAction.RETURN, twoYearsAgo)
        job.items += LegacyCleanupItem(job, toTransfer, "C3333CC", LegacyCleanupAction.TRANSFER, today.minusMonths(15), "MDI")
        job.totalRecords = 2
      },
    )
    prisonerSearch.stubFindByNumbersDetailed(
      released("A1111AA", twoYearsAgo),
      PrisonerStub("C3333CC", "MDI", previousPrisonId = "LEI", previousPrisonLeavingDate = today.minusMonths(15).toString()),
    )

    processingService.process(job.id!!)

    val finished = webTestClient.get().uri("/active-agencies/cleanup/${job.id}")
      .headers(admin)
      .exchange()
      .expectStatus().isOk
      .expectBody(LegacyCleanupJobDto::class.java)
      .returnResult().responseBody!!
    assertThat(finished.status).isEqualTo(LegacyCleanupJobStatus.FINISHED)
    assertThat(finished.skippedRecords).isEqualTo(2)
    assertThat(finished.items!!.map { it.message }).containsExactlyInAnyOrder("planned as RETURN before the 13-month rule", "planned as TRANSFER before the 13-month rule")
    assertThat(repository.findAllById(listOf(toReturn, toTransfer)).map { it.removalOutcome }).containsOnlyNulls()
    assertNoEventsPublished()
  }

  @Test
  fun `a container closed on an earlier delivery whose bookkeeping was lost is settled, not closed twice`() {
    val container = repository.save(storedAt("LEI", "A1111AA", "SEAL-A1")).id!!
    val job = jobRepository.save(
      job("LEI", LegacyCleanupJobStatus.PENDING).also { job ->
        job.items += LegacyCleanupItem(job, container, "A1111AA", LegacyCleanupAction.REMOVE, twoYearsAgo)
        job.totalRecords = 1
      },
    )
    // The earlier delivery got as far as the container write.
    transactionTemplate.executeWithoutResult {
      val c = repository.findById(container).orElseThrow()
      c.events.add(PropertyEvent(c, PropertyEventType.REMOVED, LocalDateTime.now(), PropertySystemUsers.LEGACY_CLEANUP, eventDate = twoYearsAgo, fromPrisonId = "LEI", legacyCleanupJobId = job.id))
      c.removalOutcome = RemovalOutcome.REMOVED
      c.removalDate = twoYearsAgo
      c.refreshDerivedState()
    }
    prisonerSearch.stubFindByNumbersDetailed(released("A1111AA", twoYearsAgo))

    processingService.process(job.id!!)

    val finished = jobRepository.findById(job.id!!).orElseThrow()
    assertThat(finished.status).isEqualTo(LegacyCleanupJobStatus.FINISHED)
    assertThat(finished.removedRecords).isEqualTo(1)
    assertThat(finished.skippedRecords).isEqualTo(0)
    assertThat(repository.findById(container).orElseThrow().events.count { it.eventType == PropertyEventType.REMOVED }).isEqualTo(1)
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
      .jsonPath("$.toRemove.containers").isEqualTo(0)
      .jsonPath("$.ineligible.UNRESOLVED.containers").isEqualTo(11)
      .jsonPath("$.ineligible.CONFISCATED.containers").isEqualTo(1)
      .jsonPath("$.ineligible.DISPOSAL_DATE_NOT_REACHED.containers").isEqualTo(1)
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
      { webTestClient.post().uri("/active-agencies/LEI/cleanup") },
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
    cutoffDate = cutoff,
    requestedBy = "ADMIN_USER",
    requestedAt = LocalDateTime.now().minusMinutes(10),
    status = status,
    startTime = startTime,
    lastActivityAt = lastActivityAt,
  )

  private fun storedAt(
    prisonId: String,
    prisonerNumber: String,
    seal: String,
    proposedDisposalDate: LocalDate? = null,
    containerType: ContainerType = ContainerType.STANDARD,
  ): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      containerType = containerType,
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
