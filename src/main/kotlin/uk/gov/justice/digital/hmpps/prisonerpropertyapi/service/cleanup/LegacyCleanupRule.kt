package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerStatusResolver.Companion.RELEASED_MOVEMENT_TYPE
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerStatusResolver.Companion.RELEASED_PRISON_ID
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerStatusResolver.Companion.TRANSIT_PRISON_ID
import java.time.LocalDate

/**
 * The single rule for whether a legacy clean-up may close a container held at a prison, given where its
 * owner is now (from prisoner-search) and the cut-off date the admin chose.
 *
 * It is deliberately narrower than [uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.OwnerLocation],
 * which decides what a container *reads as*: everything the clean-up closes reads as due for return or due for
 * transfer out, but not everything that reads that way is closed. Someone still here with a confirmed release
 * date tomorrow reads as due for return and must be left alone; someone in transit has only just left; and
 * anyone prisoner-search cannot resolve, or whose movement date it does not carry, is never guessed at.
 *
 * Pure, so the decision is unit-testable and the preview and the run cannot apply different rules.
 */
@Component
class LegacyCleanupRule {

  /**
   * Decide what to do with a container held at [heldPrisonId] whose owner is [prisoner]: close it because the
   * owner left on or before [cutoff], or leave it and say why.
   */
  fun decide(prisoner: Prisoner?, heldPrisonId: String, cutoff: LocalDate): CleanupDecision = when {
    prisoner?.prisonId == null -> CleanupDecision.NotEligible(IneligibleReason.UNRESOLVED)
    prisoner.prisonId == heldPrisonId -> CleanupDecision.NotEligible(IneligibleReason.OWNER_HERE)
    prisoner.prisonId == TRANSIT_PRISON_ID -> CleanupDecision.NotEligible(IneligibleReason.IN_TRANSIT)
    prisoner.prisonId == RELEASED_PRISON_ID -> decideReleased(prisoner, cutoff)
    else -> decideTransferred(prisoner, heldPrisonId, cutoff)
  }

  private fun decideReleased(prisoner: Prisoner, cutoff: LocalDate): CleanupDecision {
    if (prisoner.lastMovementTypeCode != RELEASED_MOVEMENT_TYPE) return CleanupDecision.NotEligible(IneligibleReason.NOT_RELEASED_MOVEMENT)
    val releaseDate = prisoner.lastMovementDate ?: return CleanupDecision.NotEligible(IneligibleReason.NO_MOVEMENT_DATE)
    if (releaseDate.isAfter(cutoff)) return CleanupDecision.NotEligible(IneligibleReason.TOO_RECENT)
    return CleanupDecision.Return(eventDate = releaseDate)
  }

  /**
   * The person is at another real prison. When they came straight from here, prisoner-search says exactly when
   * they left; otherwise the date they were admitted to where they are now is the latest they can have left,
   * which errs on the side of leaving property alone for longer.
   */
  private fun decideTransferred(prisoner: Prisoner, heldPrisonId: String, cutoff: LocalDate): CleanupDecision {
    val dateLeft = if (prisoner.previousPrisonId == heldPrisonId) prisoner.previousPrisonLeavingDate else prisoner.lastAdmissionDate
    if (dateLeft == null) return CleanupDecision.NotEligible(IneligibleReason.NO_MOVEMENT_DATE)
    if (dateLeft.isAfter(cutoff)) return CleanupDecision.NotEligible(IneligibleReason.TOO_RECENT)
    return CleanupDecision.Transfer(toPrisonId = prisoner.prisonId!!, dateLeft = dateLeft)
  }
}

sealed interface CleanupDecision {
  /** The owner was released on [eventDate]: mark the container returned, dated to the release. */
  data class Return(val eventDate: LocalDate) : CleanupDecision

  /** The owner is now at [toPrisonId], having left the holding prison on or before [dateLeft]: mark it transferred there. */
  data class Transfer(val toPrisonId: String, val dateLeft: LocalDate) : CleanupDecision

  data class NotEligible(val reason: IneligibleReason) : CleanupDecision
}

/** Why a container is left alone. Reported in the preview so an admin can see what the window is excluding. */
enum class IneligibleReason {
  /** prisoner-search did not return the person, or returned no current prison. */
  UNRESOLVED,

  /** The person is at the prison holding the property. */
  OWNER_HERE,

  /** The person is between prisons; nobody knows the destination yet. */
  IN_TRANSIT,

  /** Out of prison but not by a release movement (for example a court or hospital movement recorded as OUT). */
  NOT_RELEASED_MOVEMENT,

  /** prisoner-search carries no date for the movement, so how long ago it was is unknown. */
  NO_MOVEMENT_DATE,

  /** The person left after the cut-off date. */
  TOO_RECENT,
}
