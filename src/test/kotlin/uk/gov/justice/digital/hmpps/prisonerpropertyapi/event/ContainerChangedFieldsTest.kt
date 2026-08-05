package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class ContainerChangedFieldsTest {

  @Test
  fun `reports nothing when the container is untouched`() {
    val container = storedContainer()
    val before = ContainerState.of(container)

    assertThat(container.changedFieldsSince(before)).isEmpty()
  }

  @Test
  fun `a removal reports the status and location it gives up, not just the outcome`() {
    val container = storedContainer()
    val before = ContainerState.of(container)

    container.events.add(PropertyEvent(container, PropertyEventType.DISPOSED, NOW, "A_USER", eventDate = TODAY))
    container.removalOutcome = RemovalOutcome.DISPOSED
    container.removalDate = TODAY
    container.refreshDerivedState()

    // The bug this diff exists to prevent: reporting only "removalOutcome" let a subscriber filtering on
    // changedFields drop the event that told it the container had left storage.
    assertThat(container.changedFieldsSince(before)).containsExactly("location", "removalOutcome", "currentStatus")
  }

  @Test
  fun `a transfer out also reports the prison it is bound for`() {
    val container = storedContainer()
    val before = ContainerState.of(container)

    container.events.add(
      PropertyEvent(container, PropertyEventType.TRANSFERRED, NOW, "A_USER", eventDate = TODAY, fromPrisonId = "LEI", toPrisonId = "MDI"),
    )
    container.removalOutcome = RemovalOutcome.TRANSFERRED
    container.removalDate = TODAY
    container.refreshDerivedState()

    assertThat(container.changedFieldsSince(before))
      .containsExactly("location", "removalOutcome", "currentStatus", "receivingPrisonId")
  }

  @Test
  fun `a move reports the location once, whichever part of it changed`() {
    val container = storedContainer()
    val before = ContainerState.of(container)

    // Internal location to Branston changes both the id and the storage type; a subscriber sees one location.
    // Dated after the create, since the location is read off the latest event.
    container.events.add(
      PropertyEvent(container, PropertyEventType.MOVED, LATER, "A_USER", fromInternalLocationId = LOCATION, toStorageLocationType = StorageLocationType.BRANSTON),
    )
    container.refreshDerivedState()

    assertThat(container.changedFieldsSince(before)).containsExactly("location")
  }

  @Test
  fun `reports every field a single write touched`() {
    val container = storedContainer()
    val before = ContainerState.of(container)

    container.currentSealNumber = "SEAL2"
    container.containerType = ContainerType.VALUABLES
    container.proposedDisposalDate = LocalDate.parse("2030-01-01")
    container.refreshDerivedState()

    assertThat(container.changedFieldsSince(before))
      .containsExactly("sealNumber", "containerType", "proposedDisposalDate")
  }

  private fun storedContainer(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = NOW,
      currentSealNumber = "SEAL1",
      id = UUID.randomUUID(),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, NOW, "A_USER", sealNumber = "SEAL1", toInternalLocationId = LOCATION, toStorageLocationType = StorageLocationType.INTERNAL),
    )
    container.refreshDerivedState()
    return container
  }

  private companion object {
    val NOW: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    val LATER: LocalDateTime = LocalDateTime.parse("2026-01-01T10:00:00")
    val TODAY: LocalDate = LocalDate.parse("2026-01-01")
    val LOCATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
  }
}
