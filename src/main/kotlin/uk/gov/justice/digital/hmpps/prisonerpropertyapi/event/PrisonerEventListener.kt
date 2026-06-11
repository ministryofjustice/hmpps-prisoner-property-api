package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyService

/**
 * Consumes HMPPS domain events from the `prisonerproperty` SQS queue, which is subscribed to the
 * shared `hmpps-domain-events` SNS topic. On a prisoner release we mark their held property as
 * returned.
 */
@Service
class PrisonerEventListener(
  private val objectMapper: ObjectMapper,
  private val propertyService: PropertyService,
) {

  @SqsListener("prisonerproperty", factory = "hmppsQueueContainerFactoryProxy")
  fun onDomainEvent(rawMessage: String) {
    val sqsMessage = objectMapper.readValue<SQSMessage>(rawMessage)
    val event = objectMapper.readValue<HmppsDomainEvent>(sqsMessage.message)

    when (event.eventType) {
      "prison-offender-events.prisoner.released" -> {
        val prisonerNumber = event.personReference?.nomsNumber()
        if (prisonerNumber != null) {
          log.info("Prisoner {} released - marking held property as returned", prisonerNumber)
          propertyService.markAllReturnedForPrisoner(prisonerNumber)
        }
      }

      else -> log.debug("Ignoring domain event of type {}", event.eventType)
    }
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
