package uk.gov.justice.digital.hmpps.prisonerpropertyapi.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonPropertyFilter
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration.IntegrationTestBase
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class PropertyContainerRepositoryTest : IntegrationTestBase() {

  @Autowired
  private lateinit var containerRepository: PropertyContainerRepository

  @Autowired
  private lateinit var eventRepository: PropertyEventRepository

  @AfterEach
  fun cleanUp() {
    eventRepository.deleteAll()
    containerRepository.deleteAll()
  }

  @Test
  fun `persists a container with its events and finds it by prisoner number`() {
    val container = containerRepository.save(containerWithSealMoveHistory())

    val found = containerRepository.findByPrisonerNumber("A1234BC")
    assertThat(found).singleElement().extracting { it.id }.isEqualTo(container.id)

    val events = eventRepository.findByContainerIdOrderByEventDateTimeDesc(container.id!!)
    assertThat(events).extracting<PropertyEventType> { it.eventType }
      .containsExactly(PropertyEventType.MOVED, PropertyEventType.SEAL_CHANGED, PropertyEventType.CREATED_SEALED)
  }

  @Test
  fun `derives current status and location from the latest events`() {
    val container = containerWithSealMoveHistory()

    assertThat(container.currentSealNumber).isEqualTo("SEAL002")
    assertThat(container.currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(container.currentLocation()).isEqualTo(LOCATION_B)
  }

  @Test
  fun `current status reflects a disposed container`() {
    val container = PropertyContainer(
      prisonerNumber = "B2345CD",
      prisonId = "LEI",
      containerType = ContainerType.CONFISCATED,
      createdByUserId = "USER1",
    )
    container.events.add(event(container, PropertyEventType.CREATED_SEALED, baseTime, sealNumber = "SEAL100"))
    container.events.add(event(container, PropertyEventType.DISPOSED, baseTime.plusDays(2)))

    assertThat(container.currentStatus()).isEqualTo(ContainerStatus.DISPOSED)
  }

  @Test
  fun `findPrisonerNumbersPage pages by prisoner and counts distinct prisoners`() {
    saveActive("A0001AA", "S1")
    saveActive("A0001AA", "S2")
    saveActive("B0002BB", "S3")
    saveActive("C0003CC", "S4")

    val firstPage = containerRepository.findPrisonerNumbersPage("LEI", PrisonPropertyFilter(), PageRequest.of(0, 2))
    assertThat(firstPage.totalElements).isEqualTo(3)
    assertThat(firstPage.content).containsExactly("A0001AA", "B0002BB")

    val secondPage = containerRepository.findPrisonerNumbersPage("LEI", PrisonPropertyFilter(), PageRequest.of(1, 2))
    assertThat(secondPage.content).containsExactly("C0003CC")
  }

  @Test
  fun `hides removed containers unless their status is requested`() {
    saveActive("A0001AA", "S1")
    saveActive("A0001AA", "S2").apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState()
      containerRepository.save(this)
    }

    val defaultContainers = containerRepository.findContainers("LEI", PrisonPropertyFilter(), listOf("A0001AA"))
    assertThat(defaultContainers).singleElement().extracting { it.currentSealNumber }.isEqualTo("S1")

    val disposedFilter = PrisonPropertyFilter(statuses = listOf(ContainerStatus.DISPOSED))
    val disposedContainers = containerRepository.findContainers("LEI", disposedFilter, listOf("A0001AA"))
    assertThat(disposedContainers).singleElement().extracting { it.currentSealNumber }.isEqualTo("S2")
    assertThat(containerRepository.findPrisonerNumbersPage("LEI", PrisonPropertyFilter(), PageRequest.of(0, 10)).totalElements).isEqualTo(1)
  }

  @Test
  fun `filters by seal number, container types, location id and branston`() {
    val a = saveActive("A0001AA", "SEAL-X", location = LOCATION_A, type = ContainerType.VALUABLES)
    val b = saveActive("A0001AA", "SEAL-Y", location = LOCATION_B, type = ContainerType.CONFISCATED)
    saveActive("A0001AA", "SEAL-Z", branston = true)

    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(sealNumber = "SEAL-X"), listOf("A0001AA")))
      .singleElement().extracting { it.id }.isEqualTo(a.id)
    // A single type matches only that type; multiple types match any of them.
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(containerTypes = listOf(ContainerType.VALUABLES)), listOf("A0001AA")))
      .singleElement().extracting { it.id }.isEqualTo(a.id)
    assertThat(
      containerRepository.findContainers(
        "LEI",
        PrisonPropertyFilter(containerTypes = listOf(ContainerType.VALUABLES, ContainerType.CONFISCATED)),
        listOf("A0001AA"),
      ),
    ).extracting<UUID> { it.id }.containsExactlyInAnyOrder(a.id, b.id)
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(locationIds = listOf(LOCATION_A)), listOf("A0001AA")))
      .singleElement().extracting { it.id }.isEqualTo(a.id)
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(locationIds = emptyList()), listOf("A0001AA"))).isEmpty()
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(branstonOnly = true), listOf("A0001AA")))
      .singleElement().extracting { it.currentSealNumber }.isEqualTo("SEAL-Z")
  }

  @Test
  fun `includeRemoved surfaces returned and disposed containers alongside active ones`() {
    saveActive("A0001AA", "ACTIVE")
    saveActive("A0001AA", "GONE-DISPOSED").apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState()
      containerRepository.save(this)
    }
    saveActive("A0001AA", "GONE-RETURNED").apply {
      removalOutcome = RemovalOutcome.RETURNED
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState()
      containerRepository.save(this)
    }
    saveActive("A0001AA", "GONE-TRANSFERRED").apply {
      removalOutcome = RemovalOutcome.TRANSFERRED
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState()
      containerRepository.save(this)
    }
    saveActive("A0001AA", "GONE-IN-ERROR").apply {
      removalOutcome = RemovalOutcome.CREATED_IN_ERROR
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState()
      containerRepository.save(this)
    }

    // Default hides everything removed; includeRemoved brings back returned/disposed only
    // (not transferred, and never created-in-error which lives only in the person-view history).
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(), listOf("A0001AA")))
      .extracting<String> { it.currentSealNumber }.containsExactly("ACTIVE")
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(includeRemoved = true), listOf("A0001AA")))
      .extracting<String> { it.currentSealNumber }.containsExactlyInAnyOrder("ACTIVE", "GONE-DISPOSED", "GONE-RETURNED")
  }

  @Test
  fun `countDueForDisposal counts only active containers whose disposal date has arisen`() {
    saveActive("A0001AA", "NO-DATE")
    saveWithDisposalDate("A0001AA", "PAST", LocalDate.now().minusDays(1))
    saveWithDisposalDate("A0001AA", "TODAY", LocalDate.now())
    saveWithDisposalDate("A0001AA", "FUTURE", LocalDate.now().plusDays(1))
    // A disposed container with a past date must not count - it has left active storage.
    saveWithDisposalDate("A0001AA", "GONE", LocalDate.now().minusDays(5)).apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = LocalDate.now().minusDays(5)
      refreshDerivedState()
      containerRepository.save(this)
    }

    assertThat(containerRepository.countDueForDisposal("LEI", LocalDate.now())).isEqualTo(2)
  }

  @Test
  fun `filtering by DISPOSAL_REQUIRED returns only containers whose disposal date has arisen`() {
    saveWithDisposalDate("A0001AA", "PAST", LocalDate.now().minusDays(1))
    saveWithDisposalDate("A0001AA", "FUTURE", LocalDate.now().plusDays(1))
    saveActive("A0001AA", "STORED")

    // A future-dated container is not yet due, so it is excluded and its denormalised status stays STORED.
    assertThat(
      containerRepository.findContainers(
        "LEI",
        PrisonPropertyFilter(statuses = listOf(ContainerStatus.DISPOSAL_REQUIRED)),
        listOf("A0001AA"),
      ),
    ).extracting<String> { it.currentSealNumber }.containsExactly("PAST")

    assertThat(containerRepository.findByPrisonerNumber("A0001AA").first { it.currentSealNumber == "FUTURE" }.currentStatusValue)
      .isEqualTo(ContainerStatus.STORED)
  }

  @Test
  fun `findPrisonerNumbers returns all distinct matching prisoner numbers, ordered and unpaged`() {
    saveActive("B0002BB", "SEAL-B")
    saveActive("A0001AA", "SEAL-A1")
    saveActive("A0001AA", "SEAL-A2") // same prisoner, two containers - listed once
    saveActive("C0003CC", "SEAL-C").apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState()
      containerRepository.save(this)
    }

    // default filter hides removed (C excluded); ordered by prisoner number; A appears once
    assertThat(containerRepository.findPrisonerNumbers("LEI", PrisonPropertyFilter()))
      .containsExactly("A0001AA", "B0002BB")
  }

  @Test
  fun `free-text search matches prisoner number, seal number or resolved storage location`() {
    val bySeal = saveActive("A0001AA", "SN-FIND-ME", location = LOCATION_A)
    val byLocation = saveActive("A0001AA", "SN-OTHER", location = LOCATION_B)
    saveActive("A0001AA", "SN-NEITHER", branston = true)
    val prisoners = listOf("A0001AA")

    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(search = "SN-FIND-ME"), prisoners))
      .singleElement().extracting { it.id }.isEqualTo(bySeal.id)
    // prisoner-number match is case-insensitive (the term is upper-cased before comparison).
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(search = "a0001aa"), prisoners))
      .extracting<UUID> { it.id }.contains(bySeal.id, byLocation.id)
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(search = "PB0200", searchLocationIds = listOf(LOCATION_B)), prisoners))
      .singleElement().extracting { it.id }.isEqualTo(byLocation.id)
  }

  @Test
  fun `countContainersByLocation counts only containers physically present in an internal box`() {
    saveActive("A0001AA", "S1", location = LOCATION_A)
    saveActive("B0002BB", "S2", location = LOCATION_A)
    saveActive("C0003CC", "S3", location = LOCATION_B)
    saveActive("D0004DD", "S4", branston = true) // offsite - no internal location, not counted
    saveActive("E0005EE", "S5", location = LOCATION_A).apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState() // removed - location cleared, not counted
      containerRepository.save(this)
    }

    val counts = containerRepository.countContainersByLocation("LEI").associate { it.locationId to it.count }

    assertThat(counts).containsExactlyInAnyOrderEntriesOf(mapOf(LOCATION_A to 2L, LOCATION_B to 1L))
  }

  private fun saveActive(
    prisonerNumber: String,
    seal: String,
    location: UUID? = null,
    branston: Boolean = false,
    type: ContainerType = ContainerType.STANDARD,
  ): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      containerType = type,
      createdByUserId = "USER1",
      currentSealNumber = seal,
    )
    val storageType = when {
      branston -> StorageLocationType.BRANSTON
      location != null -> StorageLocationType.INTERNAL
      else -> null
    }
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, baseTime, "USER1", sealNumber = seal, toInternalLocationId = location, toStorageLocationType = storageType),
    )
    container.refreshDerivedState()
    return containerRepository.save(container)
  }

  private fun saveWithDisposalDate(prisonerNumber: String, seal: String, disposalDate: LocalDate): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = seal,
      proposedDisposalDate = disposalDate,
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, baseTime, "USER1", sealNumber = seal, toInternalLocationId = LOCATION_A, toStorageLocationType = StorageLocationType.INTERNAL),
    )
    container.refreshDerivedState()
    return containerRepository.save(container)
  }

  private fun containerWithSealMoveHistory(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = "SEAL002",
    )
    container.events.add(
      event(container, PropertyEventType.CREATED_SEALED, baseTime, sealNumber = "SEAL001", toLocation = LOCATION_A),
    )
    container.events.add(
      event(container, PropertyEventType.SEAL_CHANGED, baseTime.plusHours(1), sealNumber = "SEAL002"),
    )
    container.events.add(
      event(container, PropertyEventType.MOVED, baseTime.plusHours(2), toLocation = LOCATION_B),
    )
    return container
  }

  private fun event(
    container: PropertyContainer,
    type: PropertyEventType,
    at: LocalDateTime,
    sealNumber: String? = null,
    toLocation: UUID? = null,
  ) = PropertyEvent(
    container = container,
    eventType = type,
    eventDateTime = at,
    eventUserId = "USER1",
    sealNumber = sealNumber,
    toInternalLocationId = toLocation,
  )

  private companion object {
    private val baseTime: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
  }
}
