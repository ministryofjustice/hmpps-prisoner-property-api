package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Consumes HMPPS domain events from the `prisonerproperty` SQS queue, which is subscribed to the
 * shared `hmpps-domain-events` SNS topic.
 *
 * TODO: handle `prison-offender-events.prisoner.released` (and others) once the property
 * container API and its event-creating service exist.
 */
@Service
class PrisonerEventListener(
  private val objectMapper: ObjectMapper,
) {

  @SqsListener("prisonerproperty", factory = "hmppsQueueContainerFactoryProxy")
  fun onDomainEvent(rawMessage: String) {
    val sqsMessage = objectMapper.readValue<SQSMessage>(rawMessage)
    val event = objectMapper.readValue<HmppsDomainEvent>(sqsMessage.message)
    log.info("Received domain event of type {} (not yet handled)", event.eventType)
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

data class SQSMessage(
  @JsonProperty("Type") val type: String,
  @JsonProperty("Message") val message: String,
  @JsonProperty("MessageId") val messageId: String? = null,
)
