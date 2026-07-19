package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import uk.gov.justice.hmpps.sqs.HmppsQueueService

@Service
class DomainEventPublisher(
  private val hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
) {
  private val domainEventsTopic by lazy {
    hmppsQueueService.findByTopicId("domainevents")
      ?: throw IllegalStateException("hmpps.sqs topic 'domainevents' is not configured")
  }

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

    domainEventsTopic.snsClient.publish(request).get()
    log.info("Published domain event {}", event.eventType)
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
