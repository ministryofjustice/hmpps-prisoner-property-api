package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.cleanup

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerStatusResolver.Companion.RELEASED_MOVEMENT_TYPE
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerStatusResolver.Companion.RELEASED_PRISON_ID
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.service.ContainerStatusResolver.Companion.TRANSIT_PRISON_ID
import java.time.LocalDate

/**
 * The single rule for whether a legacy clean-up may close a container held at a prison: the property retention
 * policy (MAPB-854), applied to the container itself and to where its owner is now (from prisoner-search).
 *
 * Property is kept for 13 months after the person leaves - released, died, escaped or absconded, or moved to
 * another prison - and is closed after that. Confiscated property, and property whose proposed disposal date is
 * still to come, is never closed whoever owns it. Property for someone still here is never closed, however old.
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
   * Whether the container rules itself out, whoever owns it, or null when it does not. A disposal date that has
   * already passed does not protect a container: the record has reached the end of its retention.
   */
  fun containerExclusion(containerType: ContainerType, proposedDisposalDate: LocalDate?, today: LocalDate): IneligibleReason? = excludedContainer(containerType, proposedDisposalDate, today)

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

  /**
   * The person is out of prison. NOMIS records deaths, escapes and absconds as release movements too, told apart
   * only by the movement reason code, so they all arrive here and share the same 13 months.
   */
  private fun decideReleased(prisoner: Prisoner, cutoff: LocalDate): CleanupDecision {
    if (prisoner.lastMovementTypeCode != RELEASED_MOVEMENT_TYPE) return CleanupDecision.NotEligible(IneligibleReason.NOT_RELEASED_MOVEMENT)
    val leftOn = prisoner.lastMovementDate ?: return CleanupDecision.NotEligible(IneligibleReason.NO_MOVEMENT_DATE)
    if (leftOn.isAfter(cutoff)) return CleanupDecision.NotEligible(IneligibleReason.TOO_RECENT)
    return CleanupDecision.Remove(eventDate = leftOn, reason = releaseReason(prisoner.lastMovementReasonCode))
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
    return CleanupDecision.Remove(eventDate = dateLeft, reason = CleanupReason.TRANSFERRED, toPrisonId = prisoner.prisonId)
  }

  private fun releaseReason(movementReasonCode: String?): CleanupReason = when (movementReasonCode) {
    DIED_REASON_CODE -> CleanupReason.DIED
    in ESCAPED_REASON_CODES -> CleanupReason.ESCAPED
    else -> CleanupReason.RELEASED
  }

  companion object {
    /** How long property is kept after the person leaves before the clean-up may close it. */
    const val RETENTION_MONTHS = 13L

    /** NOMIS release reason for a death in custody. */
    private const val DIED_REASON_CODE = "DEC"

    /** NOMIS release reasons for an escape (ESCP) or an abscond (UAL, UAL_ECL) - the reasons NOMIS allows a recapture from. */
    private val ESCAPED_REASON_CODES = setOf("ESCP", "UAL", "UAL_ECL")

    /** People must have left on or before this date for their property to be closed: 13 months before [today]. */
    fun cutoff(today: LocalDate): LocalDate = today.minusMonths(RETENTION_MONTHS)

    /** [containerExclusion], callable where the rule is not injected (the write path re-checks it at processing time). */
    fun excludedContainer(containerType: ContainerType, proposedDisposalDate: LocalDate?, today: LocalDate): IneligibleReason? = when {
      containerType == ContainerType.CONFISCATED -> IneligibleReason.CONFISCATED
      proposedDisposalDate?.isAfter(today) == true -> IneligibleReason.DISPOSAL_DATE_NOT_REACHED
      else -> null
    }
  }
}

sealed interface CleanupDecision {
  /**
   * The owner left the holding prison on [eventDate], on or before the cut-off, for [reason]: mark the container
   * removed, dated to then. [toPrisonId] is where they are now when they moved to another prison.
   */
  data class Remove(val eventDate: LocalDate, val reason: CleanupReason, val toPrisonId: String? = null) : CleanupDecision

  data class NotEligible(val reason: IneligibleReason) : CleanupDecision
}

/** Why the owner's property is due to be closed. Reported in the preview; the container is marked removed whatever the reason. */
enum class CleanupReason {
  /** Released from custody. */
  RELEASED,

  /** Died in custody (NOMIS release reason DEC). */
  DIED,

  /** Escaped or absconded (NOMIS release reasons ESCP, UAL, UAL_ECL). */
  ESCAPED,

  /** Now at another prison. */
  TRANSFERRED,
}

/** Why a container is left alone. Reported in the preview so an admin can see what the rule is excluding. */
enum class IneligibleReason {
  /** Confiscated property: there may be an ongoing dispute or review, so it is never closed automatically. */
  CONFISCATED,

  /** The container's proposed disposal date is still to come. */
  DISPOSAL_DATE_NOT_REACHED,

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

  /** The person left less than 13 months ago (after the cut-off date). */
  TOO_RECENT,
}
