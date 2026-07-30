package uk.gov.justice.digital.hmpps.prisonerpropertyapi.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.OwnerLocation
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonPropertyFilter
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StatusOverlay
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
    // Seal-number matching is case-insensitive.
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(sealNumber = "seal-x"), listOf("A0001AA")))
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
  fun `includeRemoved surfaces removed, returned and disposed containers alongside active ones`() {
    saveActive("A0001AA", "ACTIVE")
    saveActive("A0001AA", "GONE-REMOVED").apply {
      removalOutcome = RemovalOutcome.REMOVED
      removalDate = LocalDate.parse("2026-02-01")
      refreshDerivedState()
      containerRepository.save(this)
    }
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

    // Default hides everything that has left active storage; includeRemoved brings back the property still
    // accounted for here - removed/returned/disposed - but not transferred (now another prison's property)
    // and never created-in-error, which lives only in the person-view history.
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(), listOf("A0001AA")))
      .extracting<String> { it.currentSealNumber }.containsExactly("ACTIVE")
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(includeRemoved = true), listOf("A0001AA")))
      .extracting<String> { it.currentSealNumber }
      .containsExactlyInAnyOrder("ACTIVE", "GONE-REMOVED", "GONE-DISPOSED", "GONE-RETURNED")
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
  fun `countActiveByPrisonerAndStatus groups by prisoner and status, excluding removed and disposal-due ones`() {
    saveActive("A0001AA", "S1")
    saveActive("A0001AA", "S2")
    saveActive("B0002BB", "S3")
    // A future disposal date is not yet due, so the container still counts as stored.
    saveWithDisposalDate("A0001AA", "FUTURE", LocalDate.now().plusDays(5))
    // Disposal due takes precedence, so this one is excluded - it is counted by countDueForDisposal alone,
    // which is what stops a container being counted in two summary tiles at once.
    saveWithDisposalDate("A0001AA", "DUE", LocalDate.now().minusDays(1))
    // Removed property has left storage entirely.
    saveActive("A0001AA", "GONE").apply {
      removalOutcome = RemovalOutcome.RETURNED
      removalDate = LocalDate.now()
      refreshDerivedState()
      containerRepository.save(this)
    }
    // A persisted due-for-transfer-out container is counted under that status, not lumped in with stored.
    saveActive("C0003CC", "MOVED").apply {
      events.add(PropertyEvent(this, PropertyEventType.PRISONER_RECEIVED, baseTime.plusDays(1), "USER1", fromPrisonId = "LEI", toPrisonId = "MDI"))
      refreshDerivedState()
      containerRepository.save(this)
    }

    val counts = containerRepository.countActiveByPrisonerAndStatus("LEI", LocalDate.now())
      .associate { (it.prisonerNumber to it.status) to it.count }

    assertThat(counts).containsOnly(
      entry("A0001AA" to ContainerStatus.STORED, 3L),
      entry("B0002BB" to ContainerStatus.STORED, 1L),
      entry("C0003CC" to ContainerStatus.DUE_FOR_TRANSFER_OUT, 1L),
    )
  }

  @Test
  fun `findActivePrisonerNumbers returns the prisoners holding live property at the prison`() {
    saveActive("A0001AA", "S1")
    saveActive("A0001AA", "S2")
    saveActive("B0002BB", "S3")
    saveActive("A0001AA", "GONE").apply {
      removalOutcome = RemovalOutcome.RETURNED
      removalDate = LocalDate.now()
      refreshDerivedState()
      containerRepository.save(this)
    }
    // Held at another prison, so not this establishment's to classify.
    saveActive("C0003CC", "ELSEWHERE", prisonId = "MDI")

    assertThat(containerRepository.findActivePrisonerNumbers("LEI")).containsExactlyInAnyOrder("A0001AA", "B0002BB")
  }

  @Test
  fun `filtering by an owner-dependent status matches the status shown, not the persisted one`() {
    saveActive("A0001AA", "RELEASED")
    saveActive("B0002BB", "HERE")
    saveActive("C0003CC", "MOVED")
    // All three are persisted STORED; the owners decide what each one actually reads.
    val overlay = overlayOf(
      "A0001AA" to OwnerLocation.RETURNING,
      "B0002BB" to OwnerLocation.HERE,
      "C0003CC" to OwnerLocation.ELSEWHERE,
    )
    val numbers = listOf("A0001AA", "B0002BB", "C0003CC")

    assertThat(sealsMatching(ContainerStatus.DUE_FOR_RETURN, overlay, numbers)).containsExactly("RELEASED")
    assertThat(sealsMatching(ContainerStatus.DUE_FOR_TRANSFER_OUT, overlay, numbers)).containsExactly("MOVED")
    // STORED excludes the two relabelled away, with no separate exclusion clause needed.
    assertThat(sealsMatching(ContainerStatus.STORED, overlay, numbers)).containsExactly("HERE")
  }

  @Test
  fun `filtering by an owner-dependent status falls back to the persisted status for unresolved prisoners`() {
    saveActive("A0001AA", "STORED")
    saveActive("A0001AA", "MOVED").apply {
      events.add(PropertyEvent(this, PropertyEventType.PRISONER_RECEIVED, baseTime.plusDays(1), "USER1", fromPrisonId = "LEI", toPrisonId = "MDI"))
      refreshDerivedState()
      containerRepository.save(this)
    }
    val overlay = overlayOf("A0001AA" to OwnerLocation.UNKNOWN)

    assertThat(sealsMatching(ContainerStatus.STORED, overlay, listOf("A0001AA"))).containsExactly("STORED")
    assertThat(sealsMatching(ContainerStatus.DUE_FOR_TRANSFER_OUT, overlay, listOf("A0001AA"))).containsExactly("MOVED")
  }

  @Test
  fun `filtering by an owner-dependent status without an overlay behaves as it did before`() {
    saveActive("A0001AA", "STORED")

    assertThat(sealsMatching(ContainerStatus.STORED, null, listOf("A0001AA"))).containsExactly("STORED")
    assertThat(sealsMatching(ContainerStatus.DUE_FOR_RETURN, null, listOf("A0001AA"))).isEmpty()
  }

  @Test
  fun `disposal due keeps precedence over the owner-dependent statuses`() {
    saveWithDisposalDate("A0001AA", "DUE", LocalDate.now().minusDays(1))
    val overlay = overlayOf("A0001AA" to OwnerLocation.RETURNING)

    // Its owner is being released, but disposal is the more urgent instruction - so it shows, and is filtered,
    // as due for disposal only. The buckets stay exclusive.
    assertThat(sealsMatching(ContainerStatus.DUE_FOR_RETURN, overlay, listOf("A0001AA"))).isEmpty()
    assertThat(sealsMatching(ContainerStatus.DISPOSAL_REQUIRED, overlay, listOf("A0001AA"))).containsExactly("DUE")
  }

  @Test
  fun `includeRemoved still surfaces removed containers alongside an owner-dependent status`() {
    saveActive("A0001AA", "STORED")
    saveActive("A0001AA", "GONE").apply {
      removalOutcome = RemovalOutcome.RETURNED
      removalDate = LocalDate.now()
      refreshDerivedState()
      containerRepository.save(this)
    }
    val overlay = overlayOf("A0001AA" to OwnerLocation.HERE)

    val seals = containerRepository.findContainers(
      "LEI",
      PrisonPropertyFilter(statuses = listOf(ContainerStatus.STORED), includeRemoved = true, statusOverlay = overlay),
      listOf("A0001AA"),
    ).map { it.currentSealNumber }

    assertThat(seals).containsExactlyInAnyOrder("STORED", "GONE")
  }

  @Test
  fun `incoming property includes property held elsewhere for someone now at this prison`() {
    // Nothing on the container records the move - the owner being here is what makes it incoming. This is the
    // case NOMIS-migrated property falls into, and the one the recorded destination alone always missed.
    saveActive("A0001AA", "LEFT-BEHIND", prisonId = "LEI")

    assertThat(incomingSeals(roll = setOf("A0001AA"), prisonerNumbers = listOf("A0001AA"))).containsExactly("LEFT-BEHIND")
  }

  @Test
  fun `incoming property excludes property already held at this prison`() {
    saveActive("A0001AA", "HERE", prisonId = "MDI")

    assertThat(incomingSeals(roll = setOf("A0001AA"), prisonerNumbers = listOf("A0001AA"))).isEmpty()
  }

  @Test
  fun `incoming property excludes property whose owner is not at this prison`() {
    saveActive("A0001AA", "SOMEONE-ELSES", prisonId = "LEI")

    assertThat(incomingSeals(roll = setOf("B0002BB"), prisonerNumbers = listOf("A0001AA"))).isEmpty()
  }

  @Test
  fun `incoming property excludes property that has left storage elsewhere`() {
    saveActive("A0001AA", "GONE", prisonId = "LEI").apply {
      removalOutcome = RemovalOutcome.RETURNED
      removalDate = LocalDate.now()
      refreshDerivedState()
      containerRepository.save(this)
    }

    assertThat(incomingSeals(roll = setOf("A0001AA"), prisonerNumbers = listOf("A0001AA"))).isEmpty()
  }

  @Test
  fun `incoming property still includes a transfer already sent here, alongside the owner-driven ones`() {
    saveTransferredTo("A0001AA", "IN-TRANSIT", heldAt = "LEI", toPrisonId = "MDI")
    saveActive("B0002BB", "LEFT-BEHIND", prisonId = "LEI")

    assertThat(incomingSeals(roll = setOf("A0001AA", "B0002BB"), prisonerNumbers = listOf("A0001AA", "B0002BB")))
      .containsExactlyInAnyOrder("IN-TRANSIT", "LEFT-BEHIND")
  }

  @Test
  fun `incoming property falls back to the recorded destination when the roll is unavailable`() {
    saveTransferredTo("A0001AA", "IN-TRANSIT", heldAt = "LEI", toPrisonId = "MDI")
    saveActive("B0002BB", "LEFT-BEHIND", prisonId = "LEI")

    // A null roll is "we do not know who is here", so only what the containers themselves record shows.
    assertThat(incomingSeals(roll = null, prisonerNumbers = listOf("A0001AA", "B0002BB"))).containsExactly("IN-TRANSIT")
  }

  @Test
  fun `an empty roll means nobody is here, so only recorded destinations match`() {
    saveTransferredTo("A0001AA", "IN-TRANSIT", heldAt = "LEI", toPrisonId = "MDI")
    saveActive("B0002BB", "LEFT-BEHIND", prisonId = "LEI")

    assertThat(incomingSeals(roll = emptySet(), prisonerNumbers = listOf("A0001AA", "B0002BB"))).containsExactly("IN-TRANSIT")
  }

  @Test
  fun `includeRemoved does not widen what counts as incoming`() {
    saveActive("A0001AA", "GONE", prisonId = "LEI").apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = LocalDate.now()
      refreshDerivedState()
      containerRepository.save(this)
    }

    // Property disposed of at another prison is not this prison's to receive, however the filter is set.
    assertThat(incomingSeals(roll = setOf("A0001AA"), prisonerNumbers = listOf("A0001AA"), includeRemoved = true)).isEmpty()
  }

  @Test
  fun `a seal search still applies to incoming property`() {
    saveActive("A0001AA", "WANTED", prisonId = "LEI")
    saveActive("A0001AA", "OTHER", prisonId = "LEI")

    val seals = containerRepository.findContainers(
      "MDI",
      PrisonPropertyFilter(includeTransferIn = true, incomingPrisonerNumbers = setOf("A0001AA"), sealNumber = "wanted"),
      listOf("A0001AA"),
    ).map { it.currentSealNumber }

    assertThat(seals).containsExactly("WANTED")
  }

  @Test
  fun `asking for a status as well returns held-here property with that status plus all incoming property`() {
    saveActive("A0001AA", "STORED-HERE", prisonId = "MDI")
    saveActive("A0001AA", "LEFT-BEHIND", prisonId = "LEI")

    val seals = containerRepository.findContainers(
      "MDI",
      PrisonPropertyFilter(
        statuses = listOf(ContainerStatus.STORED),
        statusOverlay = overlayOf("A0001AA" to OwnerLocation.HERE),
        includeTransferIn = true,
        incomingPrisonerNumbers = setOf("A0001AA"),
      ),
      listOf("A0001AA"),
    ).map { it.currentSealNumber }

    // The two scopes are OR'd: incoming property is not narrowed by the status selection.
    assertThat(seals).containsExactlyInAnyOrder("STORED-HERE", "LEFT-BEHIND")
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
    // seal-number match is case-insensitive.
    assertThat(containerRepository.findContainers("LEI", PrisonPropertyFilter(search = "sn-find-me"), prisoners))
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

  /** An owner classification built by hand, so the predicate can be tested without prisoner-search. */
  private fun overlayOf(vararg owners: Pair<String, OwnerLocation>) = StatusOverlay(owners.toMap())

  /** The seals the establishment list at MDI returns when asking for incoming property, given a prison roll. */
  private fun incomingSeals(roll: Set<String>?, prisonerNumbers: List<String>, includeRemoved: Boolean = false): List<String?> = containerRepository.findContainers(
    "MDI",
    PrisonPropertyFilter(includeTransferIn = true, incomingPrisonerNumbers = roll, includeRemoved = includeRemoved),
    prisonerNumbers,
  ).map { it.currentSealNumber }

  /** A container at [heldAt] already transferred out to [toPrisonId] and not yet logged there. */
  private fun saveTransferredTo(prisonerNumber: String, seal: String, heldAt: String, toPrisonId: String): PropertyContainer = saveActive(prisonerNumber, seal, prisonId = heldAt).apply {
    events.add(PropertyEvent(this, PropertyEventType.TRANSFERRED, baseTime.plusDays(1), "USER1", fromPrisonId = heldAt, toPrisonId = toPrisonId))
    removalOutcome = RemovalOutcome.TRANSFERRED
    removalDate = LocalDate.now()
    refreshDerivedState()
    containerRepository.save(this)
  }

  /** The seals of the containers the establishment list returns when filtering by [status]. */
  private fun sealsMatching(status: ContainerStatus, overlay: StatusOverlay?, prisonerNumbers: List<String>): List<String?> = containerRepository.findContainers(
    "LEI",
    PrisonPropertyFilter(statuses = listOf(status), statusOverlay = overlay),
    prisonerNumbers,
  ).map { it.currentSealNumber }

  private fun saveActive(
    prisonerNumber: String,
    seal: String,
    location: UUID? = null,
    branston: Boolean = false,
    type: ContainerType = ContainerType.STANDARD,
    prisonId: String = "LEI",
  ): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = prisonerNumber,
      prisonId = prisonId,
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
