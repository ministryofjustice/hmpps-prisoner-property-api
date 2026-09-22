package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import java.util.UUID

/**
 * The message the service sends itself on the `prisonerpropertycleanup` queue once a legacy clean-up job has
 * been committed. It is our own JSON, not an SNS envelope - nothing else writes to that queue.
 */
data class LegacyCleanupMessage(
  val eventType: LegacyCleanupMessageType,
  val jobId: UUID,
)

enum class LegacyCleanupMessageType { START_CLEANUP }
