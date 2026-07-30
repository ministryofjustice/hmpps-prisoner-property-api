package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.OwnerLocation
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import java.time.LocalDate
import java.time.LocalDateTime

class ContainerStatusResolverTest {

  private val resolver = ContainerStatusResolver()
  private val today = LocalDate.parse("2026-07-30")

  @Test
  fun `an owner in the holding prison is here`() {
    assertThat(resolver.ownerLocation(prisoner("LEI"), "LEI", today)).isEqualTo(OwnerLocation.HERE)
  }

  @Test
  fun `an owner in another prison is elsewhere`() {
    assertThat(resolver.ownerLocation(prisoner("MDI"), "LEI", today)).isEqualTo(OwnerLocation.ELSEWHERE)
  }

  @Test
  fun `an owner in transit is elsewhere - they have left, we just do not know where to yet`() {
    assertThat(resolver.ownerLocation(prisoner("TRN", movement = "TRN"), "LEI", today)).isEqualTo(OwnerLocation.ELSEWHERE)
  }

  @Test
  fun `a released owner is returning`() {
    assertThat(resolver.ownerLocation(prisoner("OUT", movement = "REL"), "LEI", today)).isEqualTo(OwnerLocation.RETURNING)
  }

  @Test
  fun `a released owner is returning even with no release date recorded`() {
    assertThat(resolver.ownerLocation(prisoner("OUT", movement = "REL", releaseDate = null), "LEI", today))
      .isEqualTo(OwnerLocation.RETURNING)
  }

  @Test
  fun `an owner who cannot be resolved, or has no prison, is unknown`() {
    assertThat(resolver.ownerLocation(null, "LEI", today)).isEqualTo(OwnerLocation.UNKNOWN)
    assertThat(resolver.ownerLocation(prisoner(null), "LEI", today)).isEqualTo(OwnerLocation.UNKNOWN)
  }

  @ParameterizedTest(name = "release date {0} -> {1}")
  @CsvSource(
    // A day's notice, so staff can prepare the property before the person walks out.
    "2026-07-29, RETURNING",
    "2026-07-30, RETURNING",
    "2026-07-31, RETURNING",
    "2026-08-01, HERE",
  )
  fun `an owner still here counts as returning from a day before their confirmed release date`(
    releaseDate: LocalDate,
    expected: OwnerLocation,
  ) {
    assertThat(resolver.ownerLocation(prisoner("LEI", releaseDate = releaseDate), "LEI", today)).isEqualTo(expected)
  }

  @Test
  fun `a conditional release date does not count - it can move, so it must not relabel property`() {
    val prisoner = prisoner("LEI").copy(conditionalReleaseDate = today)

    assertThat(resolver.ownerLocation(prisoner, "LEI", today)).isEqualTo(OwnerLocation.HERE)
  }

  @Test
  fun `release beats transfer out - there is no longer time to send it on`() {
    val releasingTomorrow = prisoner("MDI", releaseDate = today.plusDays(1))

    assertThat(resolver.ownerLocation(releasingTomorrow, "LEI", today)).isEqualTo(OwnerLocation.RETURNING)
  }

  @ParameterizedTest(name = "{0} owner -> {1}")
  @CsvSource(
    "LEI, STORED",
    "MDI, DUE_FOR_TRANSFER_OUT",
    "OUT, DUE_FOR_RETURN",
  )
  fun `a live container reads the status its owner's location dictates`(ownerPrisonId: String, expected: ContainerStatus) {
    val container = storedContainer()

    assertThat(resolver.effectiveStatus(container, prisoner(ownerPrisonId, movement = "REL"), today)).isEqualTo(expected)
    assertThat(resolver.effectiveStatusFromColumns(container, prisoner(ownerPrisonId, movement = "REL"), today)).isEqualTo(expected)
  }

  @Test
  fun `a stale transfer-out event does not survive the owner coming back`() {
    val container = storedContainer().apply {
      events.add(PropertyEvent(this, PropertyEventType.PRISONER_RECEIVED, LocalDateTime.parse("2026-03-01T09:00:00"), "SYS", fromPrisonId = "LEI", toPrisonId = "MDI"))
      refreshDerivedState()
    }
    assertThat(container.baseStatus()).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)

    assertThat(resolver.effectiveStatus(container, prisoner("LEI"), today)).isEqualTo(ContainerStatus.STORED)
  }

  @Test
  fun `a recorded due for return survives the owner still showing as here`() {
    // Written by a real release or death event. prisoner-search is fed by the same movements and may lag, so
    // clearing it would transiently hide exactly the property this rule exists to surface.
    val container = storedContainer().apply {
      events.add(PropertyEvent(this, PropertyEventType.PRISONER_RELEASED, LocalDateTime.parse("2026-03-01T09:00:00"), "SYS", fromPrisonId = "LEI"))
      refreshDerivedState()
    }

    assertThat(resolver.effectiveStatus(container, prisoner("LEI"), today)).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    assertThat(resolver.effectiveStatusFromColumns(container, prisoner("LEI"), today)).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
  }

  @Test
  fun `a recorded due for return gives way once the owner is in another prison`() {
    // Released and then recalled: they are back in custody, so the property follows them rather than waiting.
    val container = storedContainer().apply {
      events.add(PropertyEvent(this, PropertyEventType.PRISONER_RELEASED, LocalDateTime.parse("2026-03-01T09:00:00"), "SYS", fromPrisonId = "LEI"))
      refreshDerivedState()
    }

    assertThat(resolver.effectiveStatus(container, prisoner("MDI"), today)).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
  }

  @Test
  fun `a removal outcome wins over everything - the container has left storage`() {
    val container = storedContainer().apply {
      removalOutcome = RemovalOutcome.DISPOSED
      removalDate = today
      proposedDisposalDate = today.minusDays(5)
      refreshDerivedState()
    }

    assertThat(resolver.effectiveStatus(container, prisoner("OUT", movement = "REL"), today)).isEqualTo(ContainerStatus.DISPOSED)
    assertThat(resolver.effectiveStatusFromColumns(container, prisoner("OUT", movement = "REL"), today)).isEqualTo(ContainerStatus.DISPOSED)
  }

  @Test
  fun `a disposal that has arisen wins over the owner's location`() {
    val container = storedContainer().apply { proposedDisposalDate = today.minusDays(1) }

    assertThat(resolver.effectiveStatus(container, prisoner("OUT", movement = "REL"))).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
    assertThat(resolver.effectiveStatus(container, prisoner("MDI"))).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
  }

  @Test
  fun `a disposal still to come does not`() {
    val container = storedContainer().apply { proposedDisposalDate = LocalDate.now().plusDays(5) }

    assertThat(resolver.effectiveStatus(container, prisoner("LEI"))).isEqualTo(ContainerStatus.STORED)
  }

  @Test
  fun `an unresolved owner leaves the container's own status alone`() {
    val stored = storedContainer()
    val flagged = storedContainer().apply {
      events.add(PropertyEvent(this, PropertyEventType.PRISONER_RELEASED, LocalDateTime.parse("2026-03-01T09:00:00"), "SYS", fromPrisonId = "LEI"))
      refreshDerivedState()
    }

    assertThat(resolver.effectiveStatus(stored, null, today)).isEqualTo(ContainerStatus.STORED)
    assertThat(resolver.effectiveStatus(flagged, null, today)).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    assertThat(resolver.effectiveStatusFromColumns(flagged, null, today)).isEqualTo(ContainerStatus.DUE_FOR_RETURN)
  }

  @Test
  fun `the live statuses are exactly the ones a container still in storage can hold`() {
    assertThat(OwnerLocation.LIVE_STATUSES)
      .containsExactlyInAnyOrder(ContainerStatus.STORED, ContainerStatus.DUE_FOR_RETURN, ContainerStatus.DUE_FOR_TRANSFER_OUT)
  }

  @Test
  fun `the filter's persisted-status sets are the exact inverse of the status rule`() {
    // What the establishment list filters on and what the rows display come from the same function, so this
    // holds by construction - asserted because their agreeing is the whole point.
    OwnerLocation.entries.forEach { location ->
      OwnerLocation.LIVE_STATUSES.forEach { shown ->
        assertThat(location.persistedStatusesReadingAs(shown))
          .describedAs("%s owner, shown as %s", location, shown)
          .isEqualTo(OwnerLocation.LIVE_STATUSES.filter { location.statusFor(it) == shown }.toSet())
      }
    }
  }

  @Test
  fun `every live status a container can hold reads as exactly one status per owner location`() {
    OwnerLocation.entries.forEach { location ->
      OwnerLocation.LIVE_STATUSES.forEach { persisted ->
        val shownAs = OwnerLocation.LIVE_STATUSES.filter { persisted in location.persistedStatusesReadingAs(it) }
        assertThat(shownAs).describedAs("%s owner, container persisted as %s", location, persisted).hasSize(1)
      }
    }
  }

  private fun storedContainer() = PropertyContainer(
    prisonerNumber = "A1234BC",
    prisonId = "LEI",
    containerType = ContainerType.STANDARD,
    createdByUserId = "USER1",
    currentSealNumber = "SEAL1",
  ).apply {
    events.add(PropertyEvent(this, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2026-01-01T09:00:00"), "USER1", sealNumber = "SEAL1", toPrisonId = "LEI"))
    refreshDerivedState()
  }

  private fun prisoner(
    prisonId: String?,
    movement: String = "ADM",
    releaseDate: LocalDate? = null,
  ) = Prisoner(
    prisonerNumber = "A1234BC",
    firstName = "John",
    lastName = "Smith",
    prisonId = prisonId,
    prisonName = null,
    cellLocation = null,
    lastMovementTypeCode = movement,
    confirmedReleaseDate = releaseDate,
  )
}
