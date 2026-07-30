package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.client.Prisoner
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.OwnerLocation
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PrisonerMovementStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import java.time.LocalDate

/**
 * The single rule for what status a container reads, given the container and the prisoner who owns it.
 *
 * Everything that shows or counts a status goes through here - the person view, the establishment list rows,
 * the establishment summary tiles and (via `StatusOverlay`) the list's status filter - so a container cannot
 * read "stored" on one screen and "due for return" on another, and a summary count cannot disagree with the
 * rows the matching filter returns.
 *
 * Precedence, highest first:
 *  # a removal outcome - the container has left active storage, and nothing about its owner changes that;
 *  # a proposed disposal date that has arisen - disposal is the more urgent instruction;
 *  # the owner's location ([OwnerLocation]) - a live container follows its owner;
 *  # the container's own base status, when the owner could not be resolved.
 */
@Component
class ContainerStatusResolver {

  /**
   * Where [prisoner] is relative to [heldPrisonId], the prison holding the container.
   *
   * Release wins over "elsewhere": property held at a prison the person has already left, for someone
   * releasing tomorrow, reads as due for return rather than due for transfer out - there is no longer time to
   * send it on, and it has to reach the person. Being in transit counts as elsewhere; the person has left and
   * the property must follow, we just do not know the destination yet.
   */
  fun ownerLocation(prisoner: Prisoner?, heldPrisonId: String, today: LocalDate = LocalDate.now()): OwnerLocation = when {
    prisoner?.prisonId == null -> OwnerLocation.UNKNOWN
    prisoner.prisonId == RELEASED_PRISON_ID -> OwnerLocation.RETURNING
    prisoner.releasingBy(today.plusDays(1)) -> OwnerLocation.RETURNING
    prisoner.prisonId == TRANSIT_PRISON_ID -> OwnerLocation.ELSEWHERE
    prisoner.prisonId == heldPrisonId -> OwnerLocation.HERE
    else -> OwnerLocation.ELSEWHERE
  }

  /** The status [container] reads on the person view, whose base status is derived from its events. */
  fun effectiveStatus(container: PropertyContainer, prisoner: Prisoner?, today: LocalDate = LocalDate.now()): ContainerStatus = effectiveStatus(container, container.baseStatus(), prisoner, today)

  /**
   * The status [container] reads on the establishment list, whose base status comes from the denormalised
   * column - so a page of rows needs no container events loaded.
   */
  fun effectiveStatusFromColumns(container: PropertyContainer, prisoner: Prisoner?, today: LocalDate = LocalDate.now()): ContainerStatus = effectiveStatus(container, container.currentStatusValue, prisoner, today)

  private fun effectiveStatus(
    container: PropertyContainer,
    baseStatus: ContainerStatus,
    prisoner: Prisoner?,
    today: LocalDate,
  ): ContainerStatus = when {
    container.removalOutcome != null -> container.removalOutcome!!.status
    container.isDisposalDue() -> ContainerStatus.DISPOSAL_REQUIRED
    else -> ownerLocation(prisoner, container.prisonId, today).statusFor(baseStatus)
  }

  companion object {
    /** prisoner-search prisonId + lastMovementTypeCode values that mean the prisoner is in transit between prisons. */
    const val TRANSIT_PRISON_ID = "TRN"
    const val TRANSIT_MOVEMENT_TYPE = "TRN"

    /** prisoner-search prisonId + lastMovementTypeCode values that mean the prisoner has been released. */
    const val RELEASED_PRISON_ID = "OUT"
    const val RELEASED_MOVEMENT_TYPE = "REL"

    /**
     * The prisoner's movement status, or null if the prisoner could not be resolved: in transit between prisons
     * (prisonId TRN, lastMovementTypeCode TRN), released (prisonId OUT, lastMovementTypeCode REL), else held in
     * an establishment.
     */
    fun Prisoner?.movementStatus(): PrisonerMovementStatus? = this?.let {
      when {
        prisonId == TRANSIT_PRISON_ID && lastMovementTypeCode == TRANSIT_MOVEMENT_TYPE -> PrisonerMovementStatus.IN_TRANSIT
        prisonId == RELEASED_PRISON_ID && lastMovementTypeCode == RELEASED_MOVEMENT_TYPE -> PrisonerMovementStatus.RELEASED
        else -> PrisonerMovementStatus.IN_ESTABLISHMENT
      }
    }

    /**
     * The prisoner's real establishment, or null when they are not in one - prisoner-search reports the
     * sentinels TRN in transit and OUT once released, which are not prisons.
     */
    fun Prisoner?.realPrisonId(): String? = this?.prisonId?.takeUnless { it == TRANSIT_PRISON_ID || it == RELEASED_PRISON_ID }

    /**
     * Whether the prisoner is due to be released on or before [date]. Uses the confirmed release date only -
     * the conditional (sentence-calculated) date can move, so it is deliberately not used to relabel property.
     */
    private fun Prisoner.releasingBy(date: LocalDate): Boolean = confirmedReleaseDate?.let { !it.isAfter(date) } == true
  }
}
