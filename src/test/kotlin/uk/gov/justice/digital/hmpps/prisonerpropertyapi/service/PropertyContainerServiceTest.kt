package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class PropertyContainerServiceTest {

  private val repository = mock<PropertyContainerRepository>()
  private val service = PropertyContainerService(repository)

  @Test
  fun `getByPrisonerNumber maps containers to DTOs with derived fields`() {
    whenever(repository.findByPrisonerNumber("A1234BC")).thenReturn(listOf(containerWithHistory()))

    val result = service.getByPrisonerNumber("A1234BC")

    assertThat(result).singleElement().satisfies({
      assertThat(it.prisonerNumber).isEqualTo("A1234BC")
      assertThat(it.currentSealNumber).isEqualTo("SEAL002")
      assertThat(it.currentStatus).isEqualTo(ContainerStatus.STORED)
      assertThat(it.currentLocation).isEqualTo(LOCATION_B)
    })
    verify(repository).findByPrisonerNumber("A1234BC")
  }

  @Test
  fun `getByPrisonId maps containers to DTOs`() {
    whenever(repository.findByPrisonId("LEI")).thenReturn(listOf(containerWithHistory()))

    val result = service.getByPrisonId("LEI")

    assertThat(result).singleElement().extracting { it.prisonId }.isEqualTo("LEI")
    verify(repository).findByPrisonId("LEI")
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

  private fun containerWithHistory(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
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
    return container
  }

  private companion object {
    private val baseTime: LocalDateTime = LocalDateTime.parse("2026-01-01T09:00:00")
    private val LOCATION_A: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val LOCATION_B: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
  }
}
