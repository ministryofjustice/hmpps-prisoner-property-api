package uk.gov.justice.digital.hmpps.prisonerpropertyapi.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.check
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainerRepository
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.CreatePropertyContainerRequest
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.DomainEventPublisher
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.event.HmppsDomainEvent
import uk.gov.justice.hmpps.sqs.HmppsQueueService

class PrisonerReleasedIntegrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var repository: PropertyContainerRepository

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  @Autowired
  private lateinit var objectMapper: ObjectMapper

  @MockitoSpyBean
  private lateinit var domainEventPublisher: DomainEventPublisher

  @AfterEach
  fun cleanUp() = repository.deleteAll()

  @Test
  fun `prisoner released flags their property as due for return`() {
    val container = createContainer(prisonId = "LEI")

    publishPrisonerReleased(prisonerNumber = "A1234BC", reason = "RELEASED")

    await untilAsserted {
      assertThat(repository.findById(container.id).orElseThrow().currentStatus())
        .isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    }
    assertThat(latestEventType(container.id)).isEqualTo(PropertyEventType.PRISONER_RELEASED)
    verify(domainEventPublisher).publish(
      check {
        assertThat(it.eventType).isEqualTo("prison-property.container.updated")
        assertThat(it.prisonerNumber).isEqualTo("A1234BC")
        assertThat(it.additionalInformation?.get("dpsId")).isEqualTo(container.id.toString())
      },
    )
  }

  @Test
  fun `death in custody flags property as due for return with a distinct event`() {
    val container = createContainer(prisonId = "LEI")

    publishPrisonerReleased(prisonerNumber = "A1234BC", reason = "RELEASED", movementReasonCode = "DEC")

    await untilAsserted {
      assertThat(latestEventType(container.id)).isEqualTo(PropertyEventType.DIED_IN_CUSTODY)
    }
    assertThat(repository.findById(container.id).orElseThrow().currentStatus())
      .isEqualTo(ContainerStatus.DUE_FOR_RETURN)
    verify(domainEventPublisher).publish(
      check {
        assertThat(it.eventType).isEqualTo("prison-property.container.updated")
        assertThat(it.prisonerNumber).isEqualTo("A1234BC")
        assertThat(it.additionalInformation?.get("dpsId")).isEqualTo(container.id.toString())
      },
    )
  }

  @Test
  fun `a temporary release does not flag property as due for return`() {
    val container = createContainer(prisonId = "LEI")

    publishPrisonerReleased(prisonerNumber = "A1234BC", reason = "TEMPORARY_ABSENCE_RELEASE")

    // status stays STORED; allow time for the (ignored) message to be consumed
    Thread.sleep(1000)
    assertThat(repository.findById(container.id).orElseThrow().currentStatus()).isEqualTo(ContainerStatus.STORED)
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

  private fun latestEventType(id: java.util.UUID) = repository.findById(id).orElseThrow().events.maxBy { it.eventDateTime }.eventType

  private fun publishPrisonerReleased(prisonerNumber: String, reason: String, movementReasonCode: String? = null) {
    val topic = hmppsQueueService.findByTopicId("domainevents")!!
    val event = HmppsDomainEvent(
      eventType = "prison-offender-events.prisoner.released",
      additionalInformation = buildMap {
        put("nomsNumber", prisonerNumber)
        put("reason", reason)
        movementReasonCode?.let { put("nomisMovementReasonCode", it) }
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
}
