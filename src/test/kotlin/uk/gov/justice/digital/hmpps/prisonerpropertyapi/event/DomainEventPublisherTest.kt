package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.HmppsTopic
import java.util.concurrent.CompletableFuture

class DomainEventPublisherTest {

  private val snsAsyncClient = mock<SnsAsyncClient>()
  private val objectMapper = ObjectMapper().registerKotlinModule()
  private val hmppsQueueService = mock<HmppsQueueService>()
  private val publisher = DomainEventPublisher(hmppsQueueService, objectMapper)

  @BeforeEach
  fun setUp() {
    val topic = mock<HmppsTopic> {
      on { arn } doReturn "arn:aws:sns:eu-west-2:000000000000:domainevents"
      on { snsClient } doReturn snsAsyncClient
    }
    whenever(hmppsQueueService.findByTopicId("domainevents")).thenReturn(topic)
    whenever(snsAsyncClient.publish(any<PublishRequest>()))
      .thenReturn(CompletableFuture.completedFuture(PublishResponse.builder().build()))
  }

  @Test
  fun `publishes the source in the body and as a message attribute`() {
    publisher.publish(
      HmppsDomainEvent(
        eventType = "prison-property.container.updated",
        prisonerNumber = "A1234BC",
        source = PropertyEventSource.NOMIS,
      ),
    )

    val captor = argumentCaptor<PublishRequest>()
    verify(snsAsyncClient).publish(captor.capture())
    val request = captor.firstValue

    assertThat(request.message()).contains("\"source\":\"NOMIS\"")
    assertThat(request.messageAttributes()["eventType"]?.stringValue()).isEqualTo("prison-property.container.updated")
    assertThat(request.messageAttributes()["source"]?.stringValue()).isEqualTo("NOMIS")
  }

  @Test
  fun `omits the source message attribute when the event has no source`() {
    publisher.publish(HmppsDomainEvent(eventType = "prison-offender-events.prisoner.received"))

    val captor = argumentCaptor<PublishRequest>()
    verify(snsAsyncClient).publish(captor.capture())
    val request = captor.firstValue

    assertThat(request.messageAttributes()).doesNotContainKey("source")
  }
}
