package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup.LegacyCleanupProcessingService

/**
 * Consumes the `prisonerpropertycleanup` work queue - the message this service sends itself when an admin
 * requests a legacy clean-up (see `LegacyCleanupService.start`) - and runs the job.
 *
 * One message at a time per pod: a job is minutes of serial work and there is no benefit in interleaving
 * two. The processing service guards against the same job arriving twice (SQS redelivery after the
 * visibility timeout, or two pods), so this listener never needs to.
 */
@Service
class LegacyCleanupListener(
  private val objectMapper: ObjectMapper,
  private val processingService: LegacyCleanupProcessingService,
) {

  @SqsListener(QUEUE_ID, factory = "hmppsQueueContainerFactoryProxy", maxConcurrentMessages = "1", maxMessagesPerPoll = "1")
  fun onMessage(rawMessage: String) {
    val message = objectMapper.readValue<LegacyCleanupMessage>(rawMessage)
    when (message.eventType) {
      LegacyCleanupMessageType.START_CLEANUP -> {
        log.info("Received START_CLEANUP for legacy clean-up job {}", message.jobId)
        processingService.process(message.jobId)
      }
    }
  }

  companion object {
    const val QUEUE_ID = "prisonerpropertycleanup"
    private val log = LoggerFactory.getLogger(LegacyCleanupListener::class.java)
  }
}
