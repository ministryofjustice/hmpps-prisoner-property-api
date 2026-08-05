package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEvent
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The receiving establishment's "transfer in" flow: adding a container with a `previousSealNumber` that matches
 * property the prisoner still has in storage at another prison reconciles the two - the new record is created
 * here and the sending prison's container is deactivated (transferred), leaving one record for one box.
 */
class PropertyTransferInIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

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

    // both the created (new) and updated (source) events are published, each naming its own container
    assertThat(publishedEvents().map { it.eventType to it.dpsId }).containsExactly(
      "prison-property.container.created" to created.id.toString(),
      "prison-property.container.updated" to source.toString(),
    )
    // The source is transferred out in the same transaction, so it reports the status change as well as the
    // outcome. No receivingPrisonId: it is reconciled against the new record as it is removed, so it never
    // passes through "awaiting arrival at MDI" the way a transfer-out recorded on its own does.
    assertThat(publishedEventsFor(source).single().changedFields)
      .containsExactly("removalOutcome", "currentStatus")
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

  /**
   * The "In transit" case: the sending prison has already marked the box transferred out, so it is nobody's
   * live stock and shows as incoming at the destination. Logging its arrival must reconcile it - this had no
   * coverage at all, which is how the UI came to reject it while the API would have accepted it.
   */
  @Test
  fun `adding a container matches a previous seal held by property already transferred out`() {
    val source = repository.save(transferredOut("OLDSEAL", heldAt = "LEI", toPrisonId = "MDI")).id!!

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

    val reconciled = repository.findById(source).orElseThrow()
    // Still transferred - it was already removed, so it is linked rather than removed again, and its original
    // transfer date survives.
    assertThat(reconciled.removalOutcome).isEqualTo(RemovalOutcome.TRANSFERRED)
    assertThat(reconciled.receivingPrison()).isNull() // reconciled, so it drops off the incoming list
    assertThat(reconciled.latestTransferEvent()?.relatedContainerId).isEqualTo(created.id)
    assertThat(reconciled.latestTransferEvent()?.relatedContainerSealNumber).isEqualTo("NEWSEAL")
  }

  /**
   * A box sent to one prison but arriving at another - the person moved on again, or the destination was
   * simply wrong. The prison holding it must still be able to log it, or the sending prison's record shows it
   * in transit forever and a duplicate gets created here.
   */
  @Test
  fun `adding a container matches a transfer that named a different destination`() {
    val source = repository.save(transferredOut("OLDSEAL", heldAt = "LEI", toPrisonId = "MDI")).id!!

    webTestClient.post().uri("/property-containers")
      .headers(setAuthorisation(username = "RECEPTION", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
      .bodyValue(
        CreatePropertyContainerRequest(
          prisonerNumber = "A1234BC",
          prisonId = "IWI",
          containerType = ContainerType.STANDARD,
          sealNumber = "NEWSEAL",
          previousSealNumber = "OLDSEAL",
        ),
      )
      .exchange()
      .expectStatus().isCreated

    val reconciled = repository.findById(source).orElseThrow()
    assertThat(reconciled.receivingPrison()).isNull()
    assertThat(reconciled.latestTransferEvent()?.relatedContainerSealNumber).isEqualTo("NEWSEAL")
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

  /** A container the sending prison has already marked transferred out, awaiting logging at its destination. */
  private fun transferredOut(seal: String, heldAt: String, toPrisonId: String): PropertyContainer {
    val container = storedAt(heldAt, seal)
    container.events.add(
      PropertyEvent(container, PropertyEventType.TRANSFERRED, LocalDateTime.parse("2026-02-01T09:00:00"), "A_USER", eventDate = LocalDate.parse("2026-02-01"), fromPrisonId = heldAt, toPrisonId = toPrisonId),
    )
    container.removalOutcome = RemovalOutcome.TRANSFERRED
    container.removalDate = LocalDate.parse("2026-02-01")
    container.refreshDerivedState()
    return container
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
