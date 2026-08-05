package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyContainer
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType
import java.time.LocalDate
import java.util.UUID

/**
 * A snapshot of everything about a container that a subscriber can observe - the fields of
 * [uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.PropertyContainerDto], plus the denormalised
 * [PropertyContainer.receivingPrisonId].
 *
 * Take one *before* mutating a container and diff it *after* [PropertyContainer.refreshDerivedState] to
 * get the `changedFields` for its domain event. Deriving the list this way rather than writing it out by
 * hand is deliberate: every write path used to name only the field it set, so a removal reported
 * `["removalOutcome"]` while silently also changing the status, the location and the receiving prison.
 * A subscriber filtering on `changedFields` - the NOMIS sync does exactly that - then never saw the change
 * it actually cared about. Nothing in a hand-written list makes the omission visible; a diff cannot omit.
 */
data class ContainerState(
  val sealNumber: String?,
  val containerType: ContainerType,
  val status: ContainerStatus,
  val internalLocationId: UUID?,
  val storageLocationType: StorageLocationType?,
  val receivingPrisonId: String?,
  val removalOutcome: RemovalOutcome?,
  val removalDate: LocalDate?,
  val proposedDisposalDate: LocalDate?,
) {
  companion object {
    fun of(container: PropertyContainer) = ContainerState(
      sealNumber = container.currentSealNumber,
      containerType = container.containerType,
      // The read-time status, not the denormalised currentStatusValue, so this matches what a subscriber
      // sees on the container itself - PropertyContainerDto carries currentStatus() with the time-based
      // disposal overlay applied.
      status = container.currentStatus(),
      internalLocationId = container.currentLocation(),
      storageLocationType = container.currentLocationType(),
      receivingPrisonId = container.receivingPrisonId,
      removalOutcome = container.removalOutcome,
      removalDate = container.removalDate,
      proposedDisposalDate = container.proposedDisposalDate,
    )
  }
}

/**
 * The `changedFields` for this container's domain event: every subscriber-visible field that differs from
 * [before]. Empty when nothing changed, which the write paths treat as "raise no event".
 *
 * The names are the published contract, not the column names - `location` covers both the internal location
 * and the storage location type, since a subscriber sees one location either way. The order is fixed so the
 * list is stable to assert on.
 */
fun PropertyContainer.changedFieldsSince(before: ContainerState): List<String> {
  val after = ContainerState.of(this)
  return buildList {
    if (after.sealNumber != before.sealNumber) add("sealNumber")
    if (after.containerType != before.containerType) add("containerType")
    if (after.internalLocationId != before.internalLocationId || after.storageLocationType != before.storageLocationType) {
      add("location")
    }
    if (after.proposedDisposalDate != before.proposedDisposalDate) add("proposedDisposalDate")
    if (after.removalOutcome != before.removalOutcome || after.removalDate != before.removalDate) add("removalOutcome")
    if (after.status != before.status) add("currentStatus")
    if (after.receivingPrisonId != before.receivingPrisonId) add("receivingPrisonId")
  }
}
