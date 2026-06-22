package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerWriteService

/**
 * Consumes HMPPS domain events from the `prisonerproperty` SQS queue, which is subscribed to the
 * shared `hmpps-domain-events` SNS topic.
 *
 * Handles `prison-offender-events.prisoner.received`: when a prisoner is received into a prison, any
 * of their active property still held at a different prison is flagged due for transfer out. The
 * service is the transaction boundary; the resulting domain events are published here only after it
 * commits.
 */
@Service
class PrisonerEventListener(
  private val objectMapper: ObjectMapper,
  private val propertyContainerWriteService: PropertyContainerWriteService,
  private val domainEventPublisher: DomainEventPublisher,
) {

  @SqsListener("prisonerproperty", factory = "hmppsQueueContainerFactoryProxy")
  fun onDomainEvent(rawMessage: String) {
    val sqsMessage = objectMapper.readValue<SQSMessage>(rawMessage)
    val event = objectMapper.readValue<HmppsDomainEvent>(sqsMessage.message)
    when (event.eventType) {
      PRISONER_RECEIVED_EVENT_TYPE -> handlePrisonerReceived(event)
      else -> log.info("Received domain event of type {} (not yet handled)", event.eventType)
    }
  }

  private fun handlePrisonerReceived(event: HmppsDomainEvent) {
    val prisonerNumber = event.additionalInformation?.get("nomsNumber") as? String ?: event.prisonerNumber
    val newPrisonId = event.additionalInformation?.get("prisonId") as? String
    if (prisonerNumber.isNullOrBlank() || newPrisonId.isNullOrBlank()) {
      log.warn("Ignoring {} with missing prisoner number or prisonId", event.eventType)
      return
    }
    propertyContainerWriteService.prisonerReceived(prisonerNumber, newPrisonId)
      .forEach(domainEventPublisher::publish)
  }

  private companion object {
    private const val PRISONER_RECEIVED_EVENT_TYPE = "prison-offender-events.prisoner.received"
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

data class SQSMessage(
  @JsonProperty("Type") val type: String,
  @JsonProperty("Message") val message: String,
  @JsonProperty("MessageId") val messageId: String? = null,
)
