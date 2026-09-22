package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup

import java.util.UUID

/** A clean-up was requested for a prison that already has one pending or running - maps to 409. */
class LegacyCleanupJobActiveException(prisonId: String) : RuntimeException("A legacy clean-up is already in progress for $prisonId")

class LegacyCleanupJobNotFoundException(id: UUID) : RuntimeException("Legacy clean-up job not found: $id")

/** Too many live containers at the prison to classify in one pass - maps to 400, and means something is wrong rather than merely big. */
class LegacyCleanupTooLargeException(prisonId: String, candidates: Int, cap: Int) : RuntimeException("Legacy clean-up for $prisonId has $candidates candidate prisoners, above the cap of $cap")

/** The container no longer matches what the clean-up expected (held elsewhere, or due for disposal) - the item is skipped, not failed. */
class LegacyCleanupNotApplicableException(id: UUID, reason: String) : RuntimeException("Container $id cannot be closed by the legacy clean-up: $reason")
