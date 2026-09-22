package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.hmpps.sqs.HmppsQueueService

class PrisonerReceivedIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  @Autowired
  private lateinit var objectMapper: ObjectMapper

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `prisoner received at a new prison flags their property at the old prison as due for transfer out`() {
    val container = createContainer(prisonId = "LEI")

    publishPrisonerReceived(prisonerNumber = "A1234BC", prisonId = "MDI")

    await untilAsserted {
      assertThat(repository.findById(container.id).orElseThrow().currentStatus())
        .isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
    }
    assertThat(publishedEventsFor(container.id).last()).satisfies({
      assertThat(it.eventType).isEqualTo("prison-property.container.updated")
      assertThat(it.prisonerNumber).isEqualTo("A1234BC")
      // The container has not moved, but it is now due out to the prison the owner went to - and that
      // destination is what the receiving prison's incoming list keys on.
      assertThat(it.changedFields).containsExactly("currentStatus", "receivingPrisonId")
    })
  }

  @Test
  fun `a reception after a transfer out records the destination the transfer out could not (MAPB-861)`() {
    val container = createContainer(prisonId = "LEI")

    // The person leaves LEI. The property is flagged, but nothing yet says where it has to follow them to.
    publishPrisonerReleased(prisonerNumber = "A1234BC", reason = "TRANSFERRED")
    await untilAsserted {
      assertThat(repository.findById(container.id).orElseThrow().currentStatus())
        .isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
    }
    assertThat(repository.findById(container.id).orElseThrow().receivingPrisonId).isNull()

    // Hours later they arrive at MDI, which is the first anyone knows of the destination.
    publishPrisonerReceived(prisonerNumber = "A1234BC", prisonId = "MDI")

    await untilAsserted {
      assertThat(repository.findById(container.id).orElseThrow().receivingPrisonId).isEqualTo("MDI")
    }
    // The status was already right and stays right; both movements are in the history, because both happened.
    assertThat(repository.findById(container.id).orElseThrow().currentStatus())
      .isEqualTo(ContainerStatus.DUE_FOR_TRANSFER_OUT)
    assertThat(repository.findById(container.id).orElseThrow().events.map { it.eventType })
      .containsSequence(PropertyEventType.PRISONER_TRANSFERRED_OUT, PropertyEventType.PRISONER_RECEIVED)
    // Only the destination changed this time round, so that is all the update event reports.
    assertThat(publishedEventsFor(container.id).last().changedFields).containsExactly("receivingPrisonId")
  }

  private fun publishPrisonerReleased(prisonerNumber: String, reason: String) {
    val topic = hmppsQueueService.findByTopicId("domainevents")!!
    val event = HmppsDomainEvent(
      eventType = "prison-offender-events.prisoner.released",
      additionalInformation = mapOf("nomsNumber" to prisonerNumber, "reason" to reason),
    )
    topic.snsClient.publish(
      PublishRequest.builder()
        .topicArn(topic.arn)
        .message(objectMapper.writeValueAsString(event))
        .messageAttributes(
          mapOf(
            "eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.eventType).build(),
          ),
        )
        .build(),
    ).get()
  }

  @Test
  fun `prisoner received does not touch property already held at that prison`() {
    val container = createContainer(prisonId = "MDI")

    publishPrisonerReceived(prisonerNumber = "A1234BC", prisonId = "MDI")

    // status stays STORED; allow time for the (no-op) message to be consumed
    Thread.sleep(1000)
    assertThat(repository.findById(container.id).orElseThrow().currentStatus()).isEqualTo(ContainerStatus.STORED)
    // Nothing changed, so nothing beyond the container's own create may be published.
    assertThat(publishedEventsFor(container.id).map { it.eventType })
      .containsExactly("prison-property.container.created")
    // ...and the fact that it did nothing is itself recorded, so a listener doing nothing is
    // distinguishable from a listener that never ran.
    assertThat(trackedEvent("prison-property-prisoner-event-no-change"))
      .containsEntry("prisonerNumber", "A1234BC")
      .containsEntry("eventType", "prison-offender-events.prisoner.received")
    assertNotTracked("prison-property-container-updated")
  }

  @Test
  fun `a received event with no prisoner number is recorded as ignored`() {
    publishRawPrisonerReceived(prisonerNumber = null, prisonId = "MDI")

    await untilAsserted {
      assertThat(trackedEvent("prison-property-prisoner-event-ignored"))
        .containsEntry("reason", "missing prisoner number or prisonId")
    }
  }

  private fun createContainer(prisonId: String): PropertyContainerDto = webTestClient.post().uri("/property-containers")
    .headers(setAuthorisation(username = "A_USER", roles = listOf("ROLE_PRISONER_PROPERTY__RW")))
    .bodyValue(
      CreatePropertyContainerRequest(
        prisonerNumber = "A1234BC",
        prisonId = prisonId,
        containerType = ContainerType.STANDARD,
        sealNumber = "SEAL1",
        internalLocationId = null,
      ),
    )
    .exchange()
    .expectStatus().isCreated
    .expectBody(PropertyContainerDto::class.java)
    .returnResult().responseBody!!

  private fun publishRawPrisonerReceived(prisonerNumber: String?, prisonId: String) {
    val topic = hmppsQueueService.findByTopicId("domainevents")!!
    val event = HmppsDomainEvent(
      eventType = "prison-offender-events.prisoner.received",
      additionalInformation = buildMap {
        prisonerNumber?.let { put("nomsNumber", it) }
        put("prisonId", prisonId)
        put("reason", "TRANSFERRED")
      },
    )
    topic.snsClient.publish(
      PublishRequest.builder()
        .topicArn(topic.arn)
        .message(objectMapper.writeValueAsString(event))
        .messageAttributes(
          mapOf(
            "eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.eventType).build(),
          ),
        )
        .build(),
    ).get()
  }

  private fun publishPrisonerReceived(prisonerNumber: String, prisonId: String) {
    val topic = hmppsQueueService.findByTopicId("domainevents")!!
    val event = HmppsDomainEvent(
      eventType = "prison-offender-events.prisoner.received",
      additionalInformation = mapOf("nomsNumber" to prisonerNumber, "prisonId" to prisonId, "reason" to "TRANSFERRED"),
    )
    topic.snsClient.publish(
      PublishRequest.builder()
        .topicArn(topic.arn)
        .message(objectMapper.writeValueAsString(event))
        .messageAttributes(
          mapOf(
            "eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.eventType).build(),
          ),
        )
        .build(),
    ).get()
  }
}
