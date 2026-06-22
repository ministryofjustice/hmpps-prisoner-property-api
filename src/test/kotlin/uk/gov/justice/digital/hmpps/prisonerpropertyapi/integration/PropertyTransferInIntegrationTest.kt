package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.check
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import java.time.LocalDateTime

/**
 * The receiving establishment's "transfer in" flow: adding a container with a `previousSealNumber` that
 * matches the prisoner's due-for-transfer-out record at the sending prison reconciles the two - the new
 * record is created here and the sending prison's container is deactivated (transferred).
 */
class PropertyTransferInIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @MockitoSpyBean
  private lateinit var domainEventPublisher: DomainEventPublisher

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `adding a container with a matching previous seal transfers in the sending prison's record`() {
    val source = repository.save(dueForTransferOut(prisonId = "LEI", seal = "OLDSEAL", toPrisonId = "MDI")).id!!

    val created = webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "RECEPTION", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(
        CreatePropertyContainerRequest(
          prisonerNumber = "A1234BC",
          prisonId = "MDI",
          containerType = ContainerType.STANDARD,
          sealNumber = "NEWSEAL",
          previousSealNumber = "OLDSEAL",
        ),
      )
      .exchange()
      .expectStatus().isCreated
      .expectBody(PropertyContainerDto::class.java)
      .returnResult().responseBody!!

    // the new container is held at the receiving prison
    assertThat(created.prisonId).isEqualTo("MDI")
    assertThat(created.currentSealNumber).isEqualTo("NEWSEAL")
    assertThat(created.currentStatus).isEqualTo(ContainerStatus.STORED)

    // the sending prison's record is deactivated as transferred
    val reconciled = repository.findById(source).orElseThrow()
    assertThat(reconciled.removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    assertThat(reconciled.currentStatus()).isEqualTo(ContainerStatus.TRANSFER)
    assertThat(reconciled.events.last().toPrisonId).isEqualTo("MDI")

    // both the created (new) and updated (source) events are published
    val captor = argumentCaptor<HmppsDomainEvent>()
    verify(domainEventPublisher, times(2)).publish(captor.capture())
    assertThat(captor.allValues.map { it.eventType })
      .containsExactly("prison-property.container.created", "prison-property.container.updated")
  }

  @Test
  fun `adding a container with an unmatched previous seal still creates the container`() {
    val created = webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "RECEPTION", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(
        CreatePropertyContainerRequest(
          prisonerNumber = "A1234BC",
          prisonId = "MDI",
          containerType = ContainerType.STANDARD,
          sealNumber = "NEWSEAL",
          previousSealNumber = "NOTHING_MATCHES",
        ),
      )
      .exchange()
      .expectStatus().isCreated
      .expectBody(PropertyContainerDto::class.java)
      .returnResult().responseBody!!

    assertThat(created.prisonId).isEqualTo("MDI")
    assertThat(created.currentSealNumber).isEqualTo("NEWSEAL")
    verify(domainEventPublisher, times(1)).publish(
      check { assertThat(it.eventType).isEqualTo("prison-property.container.created") },
    )
  }

  private fun dueForTransferOut(prisonId: String, seal: String, toPrisonId: String): PropertyContainer {
    val container = PropertyContainer(
      prisonerNumber = "A1234BC",
      prisonId = prisonId,
      containerType = ContainerType.STANDARD,
      createdByUserId = "A_USER",
      createDateTime = LocalDateTime.parse("2026-01-01T09:00:00"),
      currentSealNumber = seal,
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.CREATED_SEALED, LocalDateTime.parse("2026-01-01T09:00:00"), "A_USER", sealNumber = seal, toPrisonId = prisonId),
    )
    container.events.add(
      PropertyEvent(container, PropertyEventType.PRISONER_RECEIVED, LocalDateTime.parse("2026-02-01T09:00:00"), "PRISONER_PROPERTY_API", fromPrisonId = prisonId, toPrisonId = toPrisonId),
    )
    return container
  }
}
