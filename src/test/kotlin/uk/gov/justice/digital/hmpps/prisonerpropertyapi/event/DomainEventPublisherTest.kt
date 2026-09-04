package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
  private val telemetryClient = mock<TelemetryClient>()
  private val publisher = DomainEventPublisher(hmppsQueueService, objectMapper, telemetryClient)

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

  @Test
  fun `tracks a custom event named after the domain event type, with its detail`() {
    publisher.publish(
      HmppsDomainEvent(
        eventType = "prison-property.container.updated",
        prisonerNumber = "A1234BC",
        source = PropertyEventSource.DPS,
        additionalInformation = mapOf(
          "dpsId" to "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d",
          "changedFields" to listOf("sealNumber", "location"),
        ),
      ),
    )

    val properties = argumentCaptor<Map<String, String>>()
    // The name is derived from the event type, so a new event type is tracked correctly without anyone
    // remembering to add it.
    verify(telemetryClient).trackEvent(eq("prison-property-container-updated"), properties.capture(), isNull())
    assertThat(properties.firstValue).containsEntry("eventType", "prison-property.container.updated")
    assertThat(properties.firstValue).containsEntry("prisonerNumber", "A1234BC")
    assertThat(properties.firstValue).containsEntry("source", "DPS")
    assertThat(properties.firstValue).containsEntry("dpsId", "0196f1d3-9a1f-7c3a-9b2e-2c1f3a4b5c6d")
    assertThat(properties.firstValue).containsEntry("changedFields", "sealNumber,location")
  }

  @Test
  fun `omits absent detail rather than recording it as null`() {
    publisher.publish(HmppsDomainEvent(eventType = "prison-property.container.created"))

    val properties = argumentCaptor<Map<String, String>>()
    verify(telemetryClient).trackEvent(eq("prison-property-container-created"), properties.capture(), isNull())
    // A query filtering on removedNomsNumber should match only the events that genuinely carry one.
    assertThat(properties.firstValue.keys).containsExactly("eventType")
  }

  @Test
  fun `records a publish failure and rethrows, so the loss is not silent`() {
    whenever(snsAsyncClient.publish(any<PublishRequest>()))
      .thenReturn(CompletableFuture.failedFuture(RuntimeException("SNS is down")))
    val event = HmppsDomainEvent(eventType = "prison-property.container.updated", prisonerNumber = "A1234BC")

    assertThatThrownBy { publisher.publish(event) }.isInstanceOf(Exception::class.java)

    // Publishing happens after the transaction commits, so this event is gone for good. It must not be
    // reported as a success, and it must not vanish.
    verify(telemetryClient).trackEvent(eq("prison-property-event-publish-failed"), any(), isNull())
    verify(telemetryClient, never()).trackEvent(eq("prison-property-container-updated"), any(), isNull())
    verify(telemetryClient).trackException(any<Exception>())
  }
}
