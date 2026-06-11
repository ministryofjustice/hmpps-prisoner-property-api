package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
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

    val events = eventRepository.findByContainerIdOrderByEventDateTimeDesc(container.id)
    assertThat(events).extracting<PropertyEventType> { it.eventType }
      .containsExactly(PropertyEventType.MOVED, PropertyEventType.SEAL_CHANGED, PropertyEventType.CREATED_SEALED)
  }

  @Test
  fun `derives current seal, status and location from the latest events`() {
    val container = containerWithSealMoveHistory()

    assertThat(container.currentSealNumber()).isEqualTo("SEAL002")
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

  private fun containerWithSealMoveHistory(): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = "LEI",
      containerType = ContainerType.STANDARD,
      createdByUserId = "USER1",
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
