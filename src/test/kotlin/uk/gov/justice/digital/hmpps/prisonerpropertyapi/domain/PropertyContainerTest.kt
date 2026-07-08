package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PropertyContainerTest {

  private fun container(
    proposedDisposalDate: LocalDate? = null,
    events: List<PropertyEventType> = listOf(PropertyEventType.CREATED_SEALED),
  ): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
      currentSealNumber = "SEAL1",
      proposedDisposalDate = proposedDisposalDate,
    )
    events.forEachIndexed { index, type ->
      container.events.add(PropertyEvent(container, type, BASE_TIME.plusHours(index.toLong()), "USER1"))
    }
    return container
  }

  @Test
  fun `due for disposal once the disposal date has arisen`() {
    assertThat(container(LocalDate.now().minusDays(1)).currentStatus()).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
    assertThat(container(LocalDate.now()).currentStatus()).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
  }

  @Test
  fun `stored while the disposal date is still in the future`() {
    assertThat(container(LocalDate.now().plusDays(1)).currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(container(null).currentStatus()).isEqualTo(ContainerStatus.STORED)
  }

  @Test
  fun `a disposal event never drives the status - the date does`() {
    // The DISPOSAL_REQUIRED event is recorded on set, but a future date is not yet due.
    val container = container(
      LocalDate.now().plusDays(1),
      events = listOf(PropertyEventType.CREATED_SEALED, PropertyEventType.DISPOSAL_REQUIRED),
    )
    assertThat(container.currentStatus()).isEqualTo(ContainerStatus.STORED)
    assertThat(container.baseStatus()).isEqualTo(ContainerStatus.STORED)
  }

  @Test
  fun `baseStatus never returns the time-based disposal overlay`() {
    assertThat(container(LocalDate.now().minusDays(1)).baseStatus()).isEqualTo(ContainerStatus.STORED)
  }

  @Test
  fun `baseStatus keeps due for transfer out under a disposal date, currentStatus overlays disposal once due`() {
    val container = container(
      LocalDate.now().plusDays(1),
      events = listOf(PropertyEventType.CREATED_SEALED, PropertyEventType.PRISONER_RECEIVED),
    )
    assertThat(container.baseStatus()).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
    assertThat(container.currentStatus()).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)

    container.proposedDisposalDate = LocalDate.now().minusDays(1)
    assertThat(container.currentStatus()).isEqualTo(ContainerStatus.DISPOSAL_REQUIRED)
    assertThat(container.baseStatus()).isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
  }

  @Test
  fun `a removed container is never disposal-due`() {
    val container = container(LocalDate.now().minusDays(1)).apply { removalOutcome = RemovalOutcome.RETURNED }
    assertThat(container.isDisposalDue()).isFalse()
    assertThat(container.currentStatus()).isEqualTo(ContainerStatus.RETURNED)
  }

  private companion object {
    private val BASE_TIME: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
  }
}
