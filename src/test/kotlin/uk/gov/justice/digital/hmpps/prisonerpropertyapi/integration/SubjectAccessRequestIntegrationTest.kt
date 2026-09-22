package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The subject access request data endpoint. The endpoint itself comes from hmpps-kotlin-spring-boot-starter,
 * so what is worth testing here is the contract the SAR integration guide sets out - the status codes it
 * lists, and the date-range semantics - rather than the framework's routing.
 */
class SubjectAccessRequestIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @BeforeEach
  fun setUp() {
    repository.saveAll(listOf(recentContainer(), olderContainer()))
  }

  @AfterEach
  fun cleanUp() {
    repository.deleteAll()
  }

  @Test
  fun `requires a token`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `requires the SAR_DATA_ACCESS role`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER")
      .headers(setAuthorisation(roles = listOf("ROLE_BANANAS")))
      .exchange()
      .expectStatus().isForbidden
  }

  /**
   * The product's own read role must not reach SAR data. They are separate grants on purpose: SAR data is
   * released to a different audience under a different legal basis.
   */
  @Test
  fun `the product read role does not grant access`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER")
      .headers(setAuthorisation(roles = listOf("ROLE_PRISONER_PROPERTY__RO")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `returns bad request when neither prn nor crn is supplied`() {
    webTestClient.get().uri(BASE_URL)
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isBadRequest
  }

  /** 209 is the integration guide's "this service does not hold data against that kind of identifier". */
  @Test
  fun `returns 209 for a probation case reference`() {
    webTestClient.get().uri("$BASE_URL?crn=X123456")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isEqualTo(209)
      .expectBody().isEmpty
  }

  @Test
  fun `returns no content for a prisoner with no property`() {
    webTestClient.get().uri("$BASE_URL?prn=A9999ZZ")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isNoContent
      .expectBody().isEmpty
  }

  @Test
  fun `returns every container newest first, each with its full history oldest first`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content.length()").isEqualTo(2)
      // newest first, as the integration guide requires
      .jsonPath("$.content[0].sealNumber").isEqualTo("SAR002")
      .jsonPath("$.content[1].sealNumber").isEqualTo("SAR001")
      .jsonPath("$.content[0].prisonerNumber").isEqualTo(PRISONER)
      .jsonPath("$.content[0].prisonId").isEqualTo("LEI")
      .jsonPath("$.content[0].containerType").isEqualTo("Standard property")
      .jsonPath("$.content[0].status").isEqualTo("In storage")
      .jsonPath("$.content[0].createdByUsername").isEqualTo("USER1")
      .jsonPath("$.content[0].events.length()").isEqualTo(2)
      .jsonPath("$.content[0].events[0].eventType").isEqualTo("Sealed into storage")
      .jsonPath("$.content[0].events[0].eventUsername").isEqualTo("USER1")
      .jsonPath("$.content[0].events[1].eventType").isEqualTo("Moved to a different storage location")
      .jsonPath("$.content[0].events[1].toLocationId").isEqualTo(LOCATION_B.toString())
      .jsonPath("$.content[0].events[1].toStorageLocationType").isEqualTo("In the establishment")
      // the removed container keeps its outcome and the reason it left storage
      .jsonPath("$.content[1].status").isEqualTo("Returned")
      .jsonPath("$.content[1].removalOutcome").isEqualTo("Returned")
      .jsonPath("$.content[1].removalDate").isEqualTo("2025-02-10")
  }

  /**
   * The response is what is disclosed to the person the report is about, so the absences matter as much as
   * the contents: internal identifiers and derived mirror columns must not appear.
   */
  @Test
  fun `does not expose internal identifiers or derived state`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content[0].id").doesNotExist()
      .jsonPath("$.content[0].currentStatusValue").doesNotExist()
      .jsonPath("$.content[0].currentInternalLocationId").doesNotExist()
      .jsonPath("$.content[0].currentStorageLocationType").doesNotExist()
      .jsonPath("$.content[0].receivingPrisonId").doesNotExist()
      .jsonPath("$.content[0].events[0].id").doesNotExist()
      .jsonPath("$.content[0].events[0].relatedContainerId").doesNotExist()
  }

  /**
   * The heart of the date-range rule: a container created long before the range but touched inside it is in
   * scope, and once it is in scope the whole history is returned. A report that showed only the events
   * falling inside the requested window would describe the property misleadingly.
   */
  @Test
  fun `a container created before the range but with an event inside it returns its whole history`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER&fromDate=2025-06-01")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content.length()").isEqualTo(1)
      .jsonPath("$.content[0].sealNumber").isEqualTo("SAR002")
      // created 1 March, only the move falls inside the range, but both events come back
      .jsonPath("$.content[0].events.length()").isEqualTo(2)
      .jsonPath("$.content[0].events[0].eventType").isEqualTo("Sealed into storage")
  }

  @Test
  fun `filters by an end date`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER&toDate=2025-02-28")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content.length()").isEqualTo(1)
      .jsonPath("$.content[0].sealNumber").isEqualTo("SAR001")
  }

  /** Both bounds are inclusive dates - a range of exactly the day an event happened must find it. */
  @Test
  fun `both bounds include the whole of the named day`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER&fromDate=2025-06-15&toDate=2025-06-15")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content.length()").isEqualTo(1)
      .jsonPath("$.content[0].sealNumber").isEqualTo("SAR002")
  }

  @Test
  fun `returns no content when nothing falls in the range`() {
    webTestClient.get().uri("$BASE_URL?prn=$PRISONER&fromDate=2025-06-16")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isNoContent
      .expectBody().isEmpty
  }

  /** Property belonging to someone else must never appear, whatever the range. */
  @Test
  fun `does not return another prisoner's property`() {
    repository.save(
      PropertyContainer(
        prisonerNumber = "B5678CD",
        prisonId = "LEI",
        containerType = ContainerType.STANDARD,
        createdByUserId = "USER2",
        currentSealNumber = "OTHER01",
        createDateTime = LocalDateTime.parse("2025-03-01T10:00:00"),
      ),
    )

    webTestClient.get().uri("$BASE_URL?prn=$PRISONER")
      .headers(setAuthorisation(roles = listOf(SAR_ROLE)))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.content.length()").isEqualTo(2)
      .jsonPath("$.content[?(@.sealNumber == 'OTHER01')]").doesNotExist()
  }

  /** Created 1 March, moved 15 June, still in storage. */
  private fun recentContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = PRISONER,
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = "SAR002",
      createDateTime = LocalDateTime.parse("2025-03-01T10:00:00"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.CREATED_SEALED,
        LocalDateTime.parse("2025-03-01T10:00:00"),
        "USER1",
        sealNumber = "SAR002",
        toInternalLocationId = LOCATION_A,
        toStorageLocationType = StorageLocationType.INTERNAL,
      ),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.MOVED,
        LocalDateTime.parse("2025-06-15T11:00:00"),
        "USER1",
        toInternalLocationId = LOCATION_B,
        toStorageLocationType = StorageLocationType.INTERNAL,
      ),
    )
    container.refreshDerivedState()
    return container
  }

  /** Created 5 January, returned to the prisoner 10 February - wholly outside the June range. */
  private fun olderContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = PRISONER,
      prisonId = "LEI",
      containerType = ContainerType.VALUABLES,
      createdByUserId = "USER1",
      currentSealNumber = "SAR001",
      createDateTime = LocalDateTime.parse("2025-01-05T09:00:00"),
      removalOutcome = RemovalOutcome.RETURNED,
      removalDate = LocalDate.parse("2025-02-10"),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.CREATED_SEALED,
        LocalDateTime.parse("2025-01-05T09:00:00"),
        "USER1",
        sealNumber = "SAR001",
        toInternalLocationId = LOCATION_A,
        toStorageLocationType = StorageLocationType.INTERNAL,
      ),
    )
    container.events.add(
      PropertyEvent(
        container,
        PropertyEventType.RETURNED,
        LocalDateTime.parse("2025-02-10T14:30:00"),
        "USER1",
        eventDate = LocalDate.parse("2025-02-10"),
      ),
    )
    container.refreshDerivedState()
    return container
  }

  private companion object {
    const val BASE_URL = "/subject-access-request"
    const val SAR_ROLE = "ROLE_SAR_DATA_ACCESS"
    const val PRISONER = "A1234SR"
    val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
  }
}
