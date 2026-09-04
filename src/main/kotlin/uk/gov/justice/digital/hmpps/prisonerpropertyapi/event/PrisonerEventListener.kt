package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.microsoft.applicationinsights.TelemetryClient
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.PropertyContainerWriteService

/**
 * Consumes HMPPS domain events from the `prisonerproperty` SQS queue, which is subscribed to the
 * shared `hmpps-domain-events` SNS topic.
 *
 * Handles `prison-offender-events.prisoner.received` (property held at a different prison is flagged due
 * for transfer out), `prison-offender-events.prisoner.released` with reason RELEASED (the prisoner's
 * property is flagged due for return), and `prison-offender-events.prisoner.merged` (NOMIS resolved two
 * prisoner numbers for the same person into one, so the retired number's containers move to the survivor).
 * The service is the transaction boundary; the resulting domain events are published here only after it
 * commits.
 */
@Service
class PrisonerEventListener(
  private val objectMapper: ObjectMapper,
  private val propertyContainerWriteService: PropertyContainerWriteService,
  private val domainEventPublisher: DomainEventPublisher,
  private val telemetryClient: TelemetryClient,
) {

  @SqsListener("prisonerproperty", factory = "hmppsQueueContainerFactoryProxy")
  fun onDomainEvent(rawMessage: String) {
    val sqsMessage = objectMapper.readValue<SQSMessage>(rawMessage)
    val event = objectMapper.readValue<HmppsDomainEvent>(sqsMessage.message)
    when (event.eventType) {
      PRISONER_RECEIVED_EVENT_TYPE -> handlePrisonerReceived(event)
      PRISONER_RELEASED_EVENT_TYPE -> handlePrisonerReleased(event)
      PRISONER_MERGED_EVENT_TYPE -> handlePrisonerMerged(event)
      else -> ignored(event, "unhandled event type")
    }
  }

  private fun handlePrisonerReceived(event: HmppsDomainEvent) {
    val prisonerNumber = event.additionalInformation?.get("nomsNumber") as? String ?: event.prisonerNumber
    val newPrisonId = event.additionalInformation?.get("prisonId") as? String
    if (prisonerNumber.isNullOrBlank() || newPrisonId.isNullOrBlank()) {
      log.warn("Ignoring {} with missing prisoner number or prisonId", event.eventType)
      ignored(event, "missing prisoner number or prisonId")
      return
    }
    propertyContainerWriteService.prisonerReceived(prisonerNumber, newPrisonId)
      .publishOrTrackNoChange(event, prisonerNumber)
  }

  /**
   * The release event also fires for temporary movements (court, TAP, hospital) and transfers, so only a
   * permanent release (reason RELEASED) flags property due for return. A death in custody arrives as a
   * RELEASED event too, distinguished only by the NOMIS movement reason code DEC; it is handled like a
   * release but recorded with a distinct DIED_IN_CUSTODY event so the history reads correctly.
   */
  private fun handlePrisonerReleased(event: HmppsDomainEvent) {
    val reason = event.additionalInformation?.get("reason") as? String
    if (reason != RELEASED_REASON) {
      log.info("Ignoring {} with reason {} - only a permanent release flags property due for return", event.eventType, reason)
      ignored(event, "reason $reason")
      return
    }
    val prisonerNumber = event.additionalInformation?.get("nomsNumber") as? String ?: event.prisonerNumber
    if (prisonerNumber.isNullOrBlank()) {
      log.warn("Ignoring {} with missing prisoner number", event.eventType)
      ignored(event, "missing prisoner number")
      return
    }
    val movementReasonCode = event.additionalInformation?.get("nomisMovementReasonCode") as? String
    val results = if (movementReasonCode == DIED_REASON_CODE) {
      propertyContainerWriteService.prisonerDied(prisonerNumber)
    } else {
      propertyContainerWriteService.prisonerReleased(prisonerNumber)
    }
    results.publishOrTrackNoChange(event, prisonerNumber)
  }

  /**
   * Publishes what the handler produced, or records that it produced nothing.
   *
   * An empty list is the normal, correct outcome for a redelivered or duplicate movement event - the
   * containers are already in the state the event describes - and it is indistinguishable from "the
   * listener never ran" unless we say so.
   */
  private fun List<HmppsDomainEvent>.publishOrTrackNoChange(event: HmppsDomainEvent, prisonerNumber: String) {
    if (isEmpty()) {
      telemetryClient.trackEvent(
        PropertyTelemetry.PRISONER_EVENT_NO_CHANGE,
        mapOf("eventType" to event.eventType, "prisonerNumber" to prisonerNumber),
        null,
      )
      return
    }
    forEach(domainEventPublisher::publish)
  }

  /**
   * Records an inbound event we deliberately did nothing with. [reason] separates the cases - an event
   * type we do not handle, a malformed payload, or a release that was only a temporary movement - so the
   * rates can be told apart. The temporary-movement case is by far the highest volume and is exactly the
   * one you want to be able to confirm is being dropped on purpose.
   */
  private fun ignored(event: HmppsDomainEvent, reason: String) {
    telemetryClient.trackEvent(
      PropertyTelemetry.PRISONER_EVENT_IGNORED,
      mapOf("eventType" to event.eventType, "reason" to reason),
      null,
    )
  }

  /**
   * A NOMIS prisoner-number merge: `nomsNumber` is the number that survives, `removedNomsNumber` the one
   * NOMIS deletes. Everything the retired number holds moves to the survivor.
   *
   * Anything malformed - a missing number, or a "merge" of a number into itself - is logged and dropped
   * rather than thrown. Throwing would retry and eventually dead-letter a message that can never become
   * valid.
   */
  private fun handlePrisonerMerged(event: HmppsDomainEvent) {
    val retained = event.additionalInformation?.get("nomsNumber") as? String ?: event.prisonerNumber
    val removed = event.additionalInformation?.get("removedNomsNumber") as? String
    if (retained.isNullOrBlank() || removed.isNullOrBlank()) {
      log.warn("Ignoring {} with missing retained or removed prisoner number", event.eventType)
      ignored(event, "missing retained or removed prisoner number")
      return
    }
    if (retained == removed) {
      log.warn("Ignoring {} that merges {} into itself", event.eventType, retained)
      ignored(event, "merged into itself")
      return
    }
    propertyContainerWriteService.prisonerMerged(retained, removed)
      .publishOrTrackNoChange(event, retained)
  }

  private companion object {
    private const val PRISONER_RECEIVED_EVENT_TYPE = "prison-offender-events.prisoner.received"
    private const val PRISONER_RELEASED_EVENT_TYPE = "prison-offender-events.prisoner.released"
    private const val PRISONER_MERGED_EVENT_TYPE = "prison-offender-events.prisoner.merged"
    private const val RELEASED_REASON = "RELEASED"

    /** NOMIS movement reason code for a death in custody, carried on the released event's additionalInformation. */
    private const val DIED_REASON_CODE = "DEC"
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

data class SQSMessage(
  @JsonProperty("Type") val type: String,
  @JsonProperty("Message") val message: String,
  @JsonProperty("MessageId") val messageId: String? = null,
)
