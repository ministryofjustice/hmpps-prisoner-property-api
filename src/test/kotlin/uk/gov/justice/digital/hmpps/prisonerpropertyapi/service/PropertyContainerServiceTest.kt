package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationDetail
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.LocationsClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonRegisterClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonPropertyFilter
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonerMovementStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StatusContainerCount
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PrisonPropertySummaryDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class PropertyContainerServiceTest {

  private val repository = mock<PropertyContainerRepository>()
  private val prisonerSearchClient = mock<PrisonerSearchClient>()
  private val prisonRegisterClient = mock<PrisonRegisterClient>()
  private val locationsClient = mock<LocationsClient>()
  private val service = PropertyContainerService(repository, prisonerSearchClient, prisonRegisterClient, locationsClient)

  @BeforeEach
  fun stubEnrichmentByDefault() {
    whenever(prisonerSearchClient.getPrisoner("A1234BC")).thenReturn(prisoner(prisonId = "LEI"))
    whenever(prisonRegisterClient.getPrisonNames()).thenReturn(mapOf("LEI" to "Leeds (HMP)", "MDI" to "Moorland (HMP)"))
    whenever(locationsClient.getLocations(any())).thenReturn(
      mapOf(LOCATION_B to LocationDetail(id = LOCATION_B, prisonId = "LEI", code = "PROP", pathHierarchy = "RECP-PROP", localName = "Reception Property Store")),
    )
  }

  @Test
  fun `getByPrisonerNumber maps containers to enriched DTOs with derived and resolved fields`() {
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(listOf(containerWithHistory()))

    val result = service.getByPrisonerNumber("A1234BC")

    assertThat(result).singleElement().satisfies({
      assertThat(it.prisonerNumber).isEqualTo("A1234BC")
      assertThat(it.prisonerName).isEqualTo("John Smith")
      assertThat(it.prisonName).isEqualTo("Leeds (HMP)")
      assertThat(it.prisonerCurrentPrisonId).isEqualTo("LEI")
      assertThat(it.prisonerCurrentPrisonName).isEqualTo("Leeds (HMP)")
      assertThat(it.prisonerMovementStatus).isEqualTo(PrisonerMovementStatus.IN_ESTABLISHMENT)
      assertThat(it.inPrisonersCurrentPrison).isTrue()
      assertThat(it.currentSealNumber).isEqualTo("SEAL002")
      assertThat(it.currentStatus).isEqualTo(ContainerStatus.STORED)
      assertThat(it.currentLocation).isEqualTo(LOCATION_B)
      assertThat(it.locationDescription).isEqualTo("Reception Property Store")
    })
    verify(repository).findByPrisonerNumberAndArchivedFalse("A1234BC")
  }

  @Test
  fun `getByPrisonerNumber returns an empty list without calling enrichment when the prisoner has no containers`() {
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(emptyList())

    assertThat(service.getByPrisonerNumber("A1234BC")).isEmpty()
    verify(prisonerSearchClient, never()).getPrisoner(any())
  }

  @Test
  fun `getByPrisonerNumber filters to the requested statuses`() {
    val stored = containerAt("LEI", "SEALA")
    val disposed = containerAt("LEI", "SEALB").apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = LocalDate.parse("2026-02-01")
    }
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(listOf(stored, disposed))

    val result = service.getByPrisonerNumber("A1234BC", statuses = listOf(ContainerStatus.DISPOSED))

    assertThat(result).singleElement().satisfies({
      assertThat(it.currentStatus).isEqualTo(ContainerStatus.DISPOSED)
      assertThat(it.currentSealNumber).isEqualTo("SEALB")
    })
  }

  @Test
  fun `getByPrisonerNumber sorts by last-updated date in the requested direction`() {
    val older = containerAt("LEI", "OLD", eventTime = LocalDateTime.parse("2026-01-01T09:00:00"))
    val newer = containerAt("LEI", "NEW", eventTime = LocalDateTime.parse("2026-03-01T09:00:00"))
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(listOf(older, newer))

    assertThat(service.getByPrisonerNumber("A1234BC", sortDirection = Sort.Direction.DESC).map { it.currentSealNumber })
      .containsExactly("NEW", "OLD")
    assertThat(service.getByPrisonerNumber("A1234BC", sortDirection = Sort.Direction.ASC).map { it.currentSealNumber })
      .containsExactly("OLD", "NEW")
  }

  @Test
  fun `getByPrisonerNumber flags property not held in the prisoner's current prison`() {
    whenever(prisonerSearchClient.getPrisoner("A1234BC")).thenReturn(prisoner(prisonId = "MDI"))
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(listOf(containerAt("LEI", "SEALA")))

    assertThat(service.getByPrisonerNumber("A1234BC")).singleElement().satisfies({
      assertThat(it.inPrisonersCurrentPrison).isFalse()
      // The prisoner's current establishment is still surfaced even though none of their property is held there.
      assertThat(it.prisonerCurrentPrisonId).isEqualTo("MDI")
      assertThat(it.prisonerCurrentPrisonName).isEqualTo("Moorland (HMP)")
      assertThat(it.prisonName).isEqualTo("Leeds (HMP)")
    })
  }

  @Test
  fun `getByPrisonerNumber leaves names null when the prisoner cannot be resolved`() {
    whenever(prisonerSearchClient.getPrisoner("A1234BC")).thenReturn(null)
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(listOf(containerAt("LEI", "SEALA")))

    assertThat(service.getByPrisonerNumber("A1234BC")).singleElement().satisfies({
      assertThat(it.prisonerName).isNull()
      assertThat(it.prisonerCurrentPrisonId).isNull()
      assertThat(it.prisonerCurrentPrisonName).isNull()
      assertThat(it.prisonerMovementStatus).isNull()
      assertThat(it.inPrisonersCurrentPrison).isFalse()
    })
  }

  @Test
  fun `getByPrisonerNumber surfaces the prisoner's movement status - in transit and released`() {
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(listOf(containerAt("LEI", "SEALA")))

    whenever(prisonerSearchClient.getPrisoner("A1234BC")).thenReturn(prisoner(prisonId = "TRN", lastMovementTypeCode = "TRN"))
    assertThat(service.getByPrisonerNumber("A1234BC")).singleElement().satisfies({
      assertThat(it.prisonerMovementStatus).isEqualTo(PrisonerMovementStatus.IN_TRANSIT)
    })

    whenever(prisonerSearchClient.getPrisoner("A1234BC")).thenReturn(prisoner(prisonId = "OUT", lastMovementTypeCode = "REL"))
    assertThat(service.getByPrisonerNumber("A1234BC")).singleElement().satisfies({
      assertThat(it.prisonerMovementStatus).isEqualTo(PrisonerMovementStatus.RELEASED)
    })
  }

  @Test
  fun `getPrisonPropertySummary maps status counts and box locations to the summary tiles`() {
    whenever(repository.countContainersByStatus("LEI")).thenReturn(
      listOf(
        statusCount(ContainerStatus.STORED, 3000),
        statusCount(ContainerStatus.DUE_FOR_TRANSFER_OUT, 80),
        statusCount(ContainerStatus.DISPOSAL_REQUIRED, 40),
        // A terminal status the tiles ignore.
        statusCount(ContainerStatus.RETURNED, 5),
      ),
    )
    whenever(locationsClient.getLocationsByType("LEI", "BOX")).thenReturn(
      (1..150).map { LocationDetail(id = UUID.randomUUID(), prisonId = "LEI", code = "PB$it", pathHierarchy = "PROP-PB$it", localName = "Box $it") },
    )

    val summary = service.getPrisonPropertySummary("LEI")

    assertThat(summary.availableStorageLocations).isEqualTo(150)
    assertThat(summary.storedOnSite).isEqualTo(3000)
    assertThat(summary.dueToTransferOut).isEqualTo(80)
    assertThat(summary.dueToBeDisposed).isEqualTo(40)
    // No status yet backs a pending return, so this tile is always 0 for now.
    assertThat(summary.dueToBeReturned).isZero()
  }

  @Test
  fun `getPrisonPropertySummary returns zero counts for statuses and boxes a prison has none of`() {
    whenever(repository.countContainersByStatus("LEI")).thenReturn(emptyList())
    whenever(locationsClient.getLocationsByType("LEI", "BOX")).thenReturn(emptyList())

    assertThat(service.getPrisonPropertySummary("LEI"))
      .isEqualTo(PrisonPropertySummaryDto(availableStorageLocations = 0, storedOnSite = 0, dueToTransferOut = 0, dueToBeReturned = 0, dueToBeDisposed = 0))
  }

  @Test
  fun `getPrisonProperty groups a page of containers by prisoner, enriched from the denormalised columns`() {
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(
      mapOf(
        "A1234BC" to prisoner(prisonId = "LEI"),
        "B2345CD" to Prisoner("B2345CD", "Sam", "Jones", "MDI", "Moorland (HMP)", "B-2-002", null),
      ),
    )
    whenever(repository.findPrisonerNumbersPage(eq("LEI"), any(), any()))
      .thenReturn(PageImpl(listOf("A1234BC", "B2345CD"), PAGE, 2))
    whenever(repository.findContainers(eq("LEI"), any(), eq(listOf("A1234BC", "B2345CD")))).thenReturn(
      listOf(
        containerWithHistory(),
        containerAt("LEI", "SEALA", prisonerNumber = "A1234BC"),
        containerAt("LEI", "SEALB", prisonerNumber = "B2345CD"),
      ),
    )

    val page = service.getPrisonProperty("LEI", pageable = PAGE)

    assertThat(page.totalElements).isEqualTo(2)
    assertThat(page.content).hasSize(2)
    assertThat(page.content[0]).satisfies({
      assertThat(it.prisonerNumber).isEqualTo("A1234BC")
      assertThat(it.prisonerName).isEqualTo("John Smith")
      assertThat(it.prisonerCurrentPrisonId).isEqualTo("LEI")
      assertThat(it.prisonerCurrentPrisonName).isEqualTo("Leeds (HMP)")
      assertThat(it.containers).hasSize(2)
      assertThat(it.containers).allSatisfy({ c -> assertThat(c.inPrisonersCurrentPrison).isTrue() })
      assertThat(it.containers.first { c -> c.currentSealNumber == "SEAL002" }.locationDescription).isEqualTo("Reception Property Store")
    })
    assertThat(page.content[1]).satisfies({
      assertThat(it.prisonerNumber).isEqualTo("B2345CD")
      assertThat(it.prisonerName).isEqualTo("Sam Jones")
      assertThat(it.containers).singleElement().satisfies({ c -> assertThat(c.inPrisonersCurrentPrison).isFalse() })
    })
  }

  @Test
  fun `getPrisonProperty resolves a storage-location term to box location ids by code, local name or path hierarchy`() {
    whenever(locationsClient.getLocationsByType("LEI", "BOX")).thenReturn(
      listOf(
        LocationDetail(id = LOCATION_A, prisonId = "LEI", code = "PB5638", pathHierarchy = "PROP-PB5638", localName = "Reception Box A"),
        LocationDetail(id = LOCATION_B, prisonId = "LEI", code = "PB0200", pathHierarchy = "PROP-PB0200", localName = "Reception Box B"),
      ),
    )
    whenever(repository.findPrisonerNumbersPage(eq("LEI"), any(), any())).thenReturn(PageImpl(emptyList(), PAGE, 0))

    // by code (case-insensitive)
    service.getPrisonProperty("LEI", storageLocation = "pb5638", pageable = PAGE)
    // by local name
    service.getPrisonProperty("LEI", storageLocation = "Reception Box A", pageable = PAGE)
    // by path hierarchy
    service.getPrisonProperty("LEI", storageLocation = "PROP-PB5638", pageable = PAGE)

    verify(repository, times(3)).findPrisonerNumbersPage(
      eq("LEI"),
      check<PrisonPropertyFilter> {
        assertThat(it.locationIds).containsExactly(LOCATION_A)
        assertThat(it.branstonOnly).isFalse()
      },
      any(),
    )
  }

  @Test
  fun `getPrisonProperty treats the BRANSTON search term as an offsite filter`() {
    whenever(repository.findPrisonerNumbersPage(eq("LEI"), any(), any())).thenReturn(PageImpl(emptyList(), PAGE, 0))

    service.getPrisonProperty("LEI", storageLocation = "Branston", pageable = PAGE)

    verify(repository).findPrisonerNumbersPage(
      eq("LEI"),
      check<PrisonPropertyFilter> {
        assertThat(it.branstonOnly).isTrue()
        assertThat(it.locationIds).isNull()
      },
      any(),
    )
    verify(locationsClient, never()).getLocationsByType(any(), any())
  }

  @Test
  fun `getPrisonProperty returns an empty page without enrichment when no prisoners match`() {
    whenever(repository.findPrisonerNumbersPage(eq("LEI"), any(), any())).thenReturn(PageImpl(emptyList(), PAGE, 0))

    val page = service.getPrisonProperty("LEI", pageable = PAGE)

    assertThat(page.content).isEmpty()
    assertThat(page.totalElements).isZero()
    verify(prisonerSearchClient, never()).getPrisoners(any())
  }

  @Test
  fun `getById returns the container when present`() {
    val container = containerWithHistory()
    whenever(repository.findById(any())).thenReturn(Optional.of(container))

    val result = service.getById(container.id!!)

    assertThat(result.id).isEqualTo(container.id)
    verify(repository).findById(container.id!!)
  }

  @Test
  fun `getById throws when the container is not found`() {
    val id = UUID.randomUUID()
    whenever(repository.findById(id)).thenReturn(Optional.empty())

    assertThatThrownBy { service.getById(id) }
      .isInstanceOf(PropertyContainerNotFoundException::class.java)
      .hasMessageContaining(id.toString())
  }

  @Test
  fun `getEvents returns the container's events newest first mapped to DTOs`() {
    val container = containerWithHistory()
    container.events.forEach { it.id = UUID.randomUUID() }
    whenever(repository.findById(any())).thenReturn(Optional.of(container))

    val result = service.getEvents(container.id!!)

    assertThat(result.map { it.eventType }).containsExactly(
      PropertyEventType.MOVED,
      PropertyEventType.SEAL_CHANGED,
      PropertyEventType.CREATED_SEALED,
    )
    assertThat(result.first().toInternalLocationId).isEqualTo(LOCATION_B)
    assertThat(result.last().sealNumber).isEqualTo("SEAL001")
    assertThat(result.last().fromInternalLocationId).isNull()
    verify(repository).findById(container.id!!)
  }

  @Test
  fun `getEvents throws when the container is not found`() {
    val id = UUID.randomUUID()
    whenever(repository.findById(id)).thenReturn(Optional.empty())

    assertThatThrownBy { service.getEvents(id) }
      .isInstanceOf(PropertyContainerNotFoundException::class.java)
      .hasMessageContaining(id.toString())
  }

  private fun statusCount(status: ContainerStatus, count: Long): StatusContainerCount = object : StatusContainerCount {
    override val status = status
    override val count = count
  }

  private fun prisoner(prisonId: String, lastMovementTypeCode: String? = null) = Prisoner(
    prisonerNumber = "A1234BC",
    firstName = "John",
    lastName = "Smith",
    prisonId = prisonId,
    prisonName = "Leeds (HMP)",
    cellLocation = "A-1-001",
    lastMovementTypeCode = lastMovementTypeCode,
  )

  private fun containerAt(prisonId: String, seal: String, eventTime: LocalDateTime = baseTime, prisonerNumber: String = "A1234BC"): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      createDateTime = eventTime,
      currentSealNumber = seal,
      id = UUID.randomUUID(),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, eventTime, "USER1", sealNumber = seal),
    )
    container.refreshDerivedState()
    return container
  }

  private fun containerWithHistory(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = "SEAL002",
      id = UUID.randomUUID(),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, baseTime, "USER1", sealNumber = "SEAL001", toInternalLocationId = LOCATION_A),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.SEAL_CHANGED, baseTime.plusHours(1), "USER1", sealNumber = "SEAL002"),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.MOVED, baseTime.plusHours(2), "USER1", toInternalLocationId = LOCATION_B),
    )
    container.refreshDerivedState()
    return container
  }

  private companion object {
    private val baseTime: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val PAGE: Pageable = PageRequest.of(0, 20)
  }
}
