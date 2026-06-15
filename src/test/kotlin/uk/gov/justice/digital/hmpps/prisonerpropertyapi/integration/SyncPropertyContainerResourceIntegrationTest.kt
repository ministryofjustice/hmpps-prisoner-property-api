package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.NomisContainerCode
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncMappingType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sync.SyncPropertyContainerResponse
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class SyncPropertyContainerResourceIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var eventRepository: PropertyEventRepository

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `creates a new container from a NOMIS snapshot`() {
    val response = upsert(request())

    assertThat(response.mappingType).isEqualTo(SyncMappingType.CREATED)
    assertThat(eventRepository.findByContainerIdOrderByEventDateTimeDesc(response.dpsId))
      .singleElement().extracting { it.eventType }.isEqualTo(PropertyEventType.CREATED_SEALED)

    getById(response.dpsId)
      .jsonPath("$.containerType").isEqualTo("STANDARD")
      .jsonPath("$.currentSealNumber").isEqualTo("SEAL1")
      .jsonPath("$.currentStatus").isEqualTo("STORED")
      .jsonPath("$.currentLocation").isEqualTo(LOCATION.toString())
      .jsonPath("$.currentLocationType").isEqualTo("INTERNAL")
  }

  @Test
  fun `re-syncing an unchanged snapshot adds no events`() {
    val created = upsert(request())

    val updated = upsert(request(dpsId = created.dpsId))

    assertThat(updated.dpsId).isEqualTo(created.dpsId)
    assertThat(updated.mappingType).isEqualTo(SyncMappingType.UPDATED)
    assertThat(eventRepository.findByContainerIdOrderByEventDateTimeDesc(created.dpsId)).hasSize(1)
  }

  @Test
  fun `a changed seal appends a seal-changed event`() {
    val created = upsert(request())

    upsert(request(dpsId = created.dpsId, sealMark = "SEAL2"))

    val events = eventRepository.findByContainerIdOrderByEventDateTimeDesc(created.dpsId)
    assertThat(events).hasSize(2)
    assertThat(events.first().eventType).isEqualTo(PropertyEventType.SEAL_CHANGED)
    getById(created.dpsId).jsonPath("$.currentSealNumber").isEqualTo("SEAL2")
  }

  @Test
  fun `a changed location appends a move event`() {
    val created = upsert(request())
    val newLocation = UUID.fromString("33333333-3333-3333-3333-333333333333")

    upsert(request(dpsId = created.dpsId, internalLocationId = newLocation))

    assertThat(eventRepository.findByContainerIdOrderByEventDateTimeDesc(created.dpsId).first().eventType)
      .isEqualTo(PropertyEventType.MOVED)
    getById(created.dpsId).jsonPath("$.currentLocation").isEqualTo(newLocation.toString())
  }

  @Test
  fun `disposing a container sets the status and clears the location`() {
    val created = upsert(request())

    upsert(request(dpsId = created.dpsId, expiryDate = LocalDate.parse("2026-09-15")))

    getById(created.dpsId)
      .jsonPath("$.currentStatus").isEqualTo("DISPOSED")
      .jsonPath("$.disposedDate").isEqualTo("2026-09-15")
      .jsonPath("$.currentLocation").doesNotExist()
  }

  @Test
  fun `a proposed disposal date sets the disposal-required status`() {
    val created = upsert(request())

    upsert(request(dpsId = created.dpsId, proposedDisposalDate = LocalDate.parse("2026-09-01")))

    getById(created.dpsId)
      .jsonPath("$.currentStatus").isEqualTo("DISPOSAL_REQUIRED")
      .jsonPath("$.proposedDisposalDate").isEqualTo("2026-09-01")
  }

  @Test
  fun `a missing seal becomes a flagged placeholder`() {
    val created = upsert(request(sealMark = null))

    getById(created.dpsId).jsonPath("$.currentSealNumber").isEqualTo("MISSING-123")
  }

  @ParameterizedTest
  @CsvSource(
    "BULK,STANDARD",
    "VALUABLES,VALUABLES",
    "CONFISCATED,CONFISCATED",
    "FOR_DESTRUCTION,STANDARD",
  )
  fun `maps NOMIS container codes to DPS types`(code: NomisContainerCode, expectedType: String) {
    val created = upsert(request(containerCode = code))

    getById(created.dpsId).jsonPath("$.containerType").isEqualTo(expectedType)
  }

  @Test
  fun `Branston Storage maps to EXCESS held offsite at Branston with no internal location`() {
    val created = upsert(request(containerCode = NomisContainerCode.BRANSTON_STORAGE, internalLocationId = null))

    getById(created.dpsId)
      .jsonPath("$.containerType").isEqualTo("EXCESS")
      .jsonPath("$.currentLocationType").isEqualTo("BRANSTON")
      .jsonPath("$.currentLocation").doesNotExist()
  }

  @Test
  fun `rejects an unknown container code`() {
    val body = """
      {
        "nomisPropertyContainerId": 123,
        "prisonerNumber": "A1234BC",
        "prisonId": "LEI",
        "containerCode": "Nonsense",
        "createDateTime": "2026-01-01T09:00:00",
        "createUsername": "QWILLIS"
      }
    """.trimIndent()

    webTestClient.post().uri("/sync/property-containers/upsert")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__SYNC")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(body)
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `returns not found when updating an unknown DPS id`() {
    webTestClient.post().uri("/sync/property-containers/upsert")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__SYNC")))
      .bodyValue(request(dpsId = UUID.randomUUID()))
      .exchange()
      .expectStatus().isNotFound
  }

  @Test
  fun `migrate creates a container`() {
    val response = webTestClient.post().uri("/sync/property-containers/migrate")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__SYNC")))
      .bodyValue(request())
      .exchange()
      .expectStatus().isOk
      .expectBody(SyncPropertyContainerResponse::class.java)
      .returnResult().responseBody!!

    assertThat(response.mappingType).isEqualTo(SyncMappingType.CREATED)
  }

  @Test
  fun `returns unauthorized when no token is presented`() {
    webTestClient.post().uri("/sync/property-containers/upsert")
      .bodyValue(request())
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns forbidden without the sync role`() {
    webTestClient.post().uri("/sync/property-containers/upsert")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .bodyValue(request())
      .exchange()
      .expectStatus().isForbidden
  }

  private fun upsert(request: SyncPropertyContainerRequest): SyncPropertyContainerResponse = webTestClient.post()
    .uri("/sync/property-containers/upsert")
    .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__SYNC")))
    .bodyValue(request)
    .exchange()
    .expectStatus().isOk
    .expectBody(SyncPropertyContainerResponse::class.java)
    .returnResult().responseBody!!

  private fun getById(id: UUID) = webTestClient.get().uri("/sync/property-containers/{id}", id)
    .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__SYNC")))
    .exchange()
    .expectStatus().isOk
    .expectBody()

  private fun request(
    dpsId: UUID? = null,
    sealMark: String? = "SEAL1",
    internalLocationId: UUID? = LOCATION,
    containerCode: NomisContainerCode = NomisContainerCode.BULK,
    proposedDisposalDate: LocalDate? = null,
    expiryDate: LocalDate? = null,
  ) = SyncPropertyContainerRequest(
    nomisPropertyContainerId = 123,
    dpsId = dpsId,
    prisonerNumber = "A1234BC",
    prisonId = "LEI",
    containerCode = containerCode,
    internalLocationId = internalLocationId,
    sealMark = sealMark,
    proposedDisposalDate = proposedDisposalDate,
    expiryDate = expiryDate,
    createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
    createUsername = "QWILLIS",
    modifyDateTime = LocalDateTime.parse("2026-02-01T09:00:00"),
    modifyUsername = "QWILLIS",
  )

  private companion object {
    private val LOCATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
  }
}
