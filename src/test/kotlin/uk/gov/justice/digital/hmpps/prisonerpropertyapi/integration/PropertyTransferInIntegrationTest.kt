package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
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
 * The receiving establishment's "transfer in" flow: adding a container with a `previousSealNumber` that matches
 * property the prisoner still has in storage at another prison reconciles the two - the new record is created
 * here and the sending prison's container is deactivated (transferred), leaving one record for one box.
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

  /**
   * The reported bug (G0442GA): property held at Whitemoor was added at Belmarsh quoting the old seal, and a
   * second record was created because Whitemoor had never marked the transfer out. Most prisons never do.
   */
  @Test
  fun `adding a container matches a previous seal held by ordinary stored property at another prison`() {
    val source = repository.save(storedAt(prisonId = "LEI", seal = "124744")).id!!

    val created = webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "RECEPTION", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(
        CreatePropertyContainerRequest(
          prisonerNumber = "A1234BC",
          prisonId = "MDI",
          containerType = ContainerType.STANDARD,
          sealNumber = "124744/2",
          // As staff typed it: a different case and some stray whitespace must not defeat the match.
          previousSealNumber = " 124744 ",
        ),
      )
      .exchange()
      .expectStatus().isCreated
      .expectBody(PropertyContainerDto::class.java)
      .returnResult().responseBody!!

    // One record for one box: the sending prison's is deactivated and no longer awaiting arrival here.
    val reconciled = repository.findById(source).orElseThrow()
    assertThat(reconciled.removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    assertThat(reconciled.receivingPrison()).isNull()

    // Each history names the seal it was matched to, so staff can see the two records were joined up.
    assertThat(reconciled.events.last().relatedContainerSealNumber).isEqualTo("124744/2")
    val arriving = repository.findById(created.id).orElseThrow()
    val createdEvent = arriving.events.single { it.eventType == PropertyEventType.CREATED_SEALED }
    assertThat(createdEvent.relatedContainerId).isEqualTo(source)
    assertThat(createdEvent.relatedContainerSealNumber).isEqualTo("124744")
  }

  @Test
  fun `adding a container with an unmatched previous seal is rejected rather than creating a duplicate`() {
    webTestClient.post().uri("/property-containers")
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
      .expectStatus().isBadRequest
      .expectBody()
      // A code, so the front end can put the error against the field the user typed into.
      .jsonPath("$.errorCode").isEqualTo("PREVIOUS_SEAL_NUMBER_NOT_FOUND")
      .jsonPath("$.userMessage").value<String> { assertThat(it).contains("NOTHING_MATCHES") }

    assertThat(repository.findByPrisonerNumber("A1234BC")).isEmpty()
    verify(domainEventPublisher, never()).publish(any())
  }

  private fun storedAt(prisonId: String, seal: String): PropertyContainer {
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
    container.refreshDerivedState()
    return container
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
