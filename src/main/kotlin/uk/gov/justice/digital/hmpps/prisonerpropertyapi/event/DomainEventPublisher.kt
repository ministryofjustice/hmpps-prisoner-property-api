package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.hmpps.sqs.HmppsQueueService

@Service
class DomainEventPublisher(
  private val hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
  private val telemetryClient: TelemetryClient,
) {
  private val domainEventsTopic by lazy {
    hmppsQueueService.findByTopicId("domainevents")
      ?: throw IllegalStateException("hmpps.sqs topic 'domainevents' is not configured")
  }

  /**
   * Publishes [event] to the domain-events topic and records it in App Insights.
   *
   * Every publish path in the service funnels through here, so this is where published events are
   * tracked rather than at each call site - a new publish route then gets telemetry for free, and
   * cannot silently miss it the way a hand-added `trackEvent` can. The paths that deliberately publish
   * *nothing* never reach this method, and track themselves; see [PropertyTelemetry].
   *
   * Telemetry comes after the publish, never before, so a failure is not reported as a success.
   */
  fun publish(event: HmppsDomainEvent) {
    val request = PublishRequest.builder()
      .topicArn(domainEventsTopic.arn)
      .message(objectMapper.writeValueAsString(event))
      .messageAttributes(
        buildMap {
          put(
            "eventType",
            MessageAttributeValue.builder()
              .dataType("String")
              .stringValue(event.eventType)
              .build(),
          )
          // Surface the originating system as an attribute so subscribers can filter (e.g. a sync-back
          // ignoring NOMIS-origin events) via an SNS subscription filter policy rather than in code.
          event.source?.let {
            put(
              "source",
              MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(it.name)
                .build(),
            )
          }
        },
      )
      .build()

    try {
      domainEventsTopic.snsClient.publish(request).get()
    } catch (e: Exception) {
      // Publishing happens after the transaction has committed, so the data change is already durable
      // and this event is simply lost. Record it before rethrowing - otherwise the loss is invisible in
      // both the logs and the telemetry, and the only symptom is a subscriber that never hears about a
      // change that did happen.
      log.error("Failed to publish domain event {}", event.eventType, e)
      telemetryClient.trackEvent(PropertyTelemetry.EVENT_PUBLISH_FAILED, event.telemetryProperties(), null)
      telemetryClient.trackException(e)
      throw e
    }

    log.info("Published domain event {}", event.eventType)
    telemetryClient.trackEvent(telemetryNameFor(event.eventType), event.telemetryProperties(), null)
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

/**
 * The event, flattened for App Insights. Keys are omitted rather than written as "null" when absent, so
 * a query filtering on `removedNomsNumber` matches only the events that genuinely carry one.
 */
private fun HmppsDomainEvent.telemetryProperties(): Map<String, String> = buildMap {
  put("eventType", eventType)
  prisonerNumber?.let { put("prisonerNumber", it) }
  source?.let { put("source", it.name) }
  additionalInformation?.let { info ->
    (info["dpsId"] as? String)?.let { put("dpsId", it) }
    (info["nomisPropertyContainerId"])?.let { put("nomisPropertyContainerId", it.toString()) }
    (info["removedNomsNumber"] as? String)?.let { put("removedNomsNumber", it) }
    @Suppress("UNCHECKED_CAST")
    (info["changedFields"] as? List<String>)?.let { put("changedFields", it.joinToString(",")) }
  }
}
