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
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
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

    assertThat(service.getByPrisonerNumber("A1234BC")).singleElement()
      .satisfies({ assertThat(it.inPrisonersCurrentPrison).isFalse() })
  }

  @Test
  fun `getByPrisonerNumber leaves names null when the prisoner cannot be resolved`() {
    whenever(prisonerSearchClient.getPrisoner("A1234BC")).thenReturn(null)
    whenever(repository.findByPrisonerNumberAndArchivedFalse("A1234BC")).thenReturn(listOf(containerAt("LEI", "SEALA")))

    assertThat(service.getByPrisonerNumber("A1234BC")).singleElement().satisfies({
      assertThat(it.prisonerName).isNull()
      assertThat(it.inPrisonersCurrentPrison).isFalse()
    })
  }

  @Test
  fun `getPrisonProperty groups a page of containers by prisoner, enriched from the denormalised columns`() {
    whenever(prisonerSearchClient.getPrisoners(any())).thenReturn(
      mapOf(
        "A1234BC" to prisoner(prisonId = "LEI"),
        "B2345CD" to Prisoner("B2345CD", "Sam", "Jones", "MDI", "Moorland (HMP)", "B-2-002"),
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
  fun `getPrisonProperty resolves a storage-location code to location ids before querying`() {
    whenever(locationsClient.getNonResidentialLocations("LEI")).thenReturn(
      listOf(
        LocationDetail(id = LOCATION_A, prisonId = "LEI", code = "PB5638", pathHierarchy = "PROP-PB5638"),
        LocationDetail(id = LOCATION_B, prisonId = "LEI", code = "PB0200", pathHierarchy = "PROP-PB0200"),
      ),
    )
    whenever(repository.findPrisonerNumbersPage(eq("LEI"), any(), any())).thenReturn(PageImpl(emptyList(), PAGE, 0))

    service.getPrisonProperty("LEI", storageLocation = "pb5638", pageable = PAGE)

    verify(repository).findPrisonerNumbersPage(
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
    verify(locationsClient, never()).getNonResidentialLocations(any())
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

  private fun prisoner(prisonId: String) = Prisoner(
    prisonerNumber = "A1234BC",
    firstName = "John",
    lastName = "Smith",
    prisonId = prisonId,
    prisonName = "Leeds (HMP)",
    cellLocation = "A-1-001",
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
