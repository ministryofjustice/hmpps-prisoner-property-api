package uk.gov.justice.digital.hmpps.prisonerpropertyapi.dto.sar

import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerStatus
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.ContainerType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.PropertyEventType
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.StorageLocationType

/**
 * Plain-English wording for the subject access request report.
 *
 * The SAR integration guiding principles require codes that stand for something to be decoded to their full
 * names rather than disclosed raw, so the report carries these rather than `CREATED_SEALED`. The library's
 * `convertCamelCase` helper cannot do it - these codes are upper snake case, and it renders them as
 * "created _ sealed".
 *
 * This is deliberately not reusing the descriptions in `ExportReferenceData`. Those are written for the data
 * dictionary: they are long, they explain the model rather than the event, and one of them names
 * `related_container_id`, an internal identifier that must never reach a report.
 *
 * This wording is read by the person the report is about. It is subject to sign-off by the Offender SAR team
 * as part of the data review (MAPB-768) and the report review (MAPB-772) - expect it to change, and change
 * it here rather than in the template.
 */

internal val PropertyEventType.sarLabel: String
  get() = when (this) {
    PropertyEventType.CREATED_SEALED -> "Sealed into storage"
    PropertyEventType.SEAL_CHANGED -> "Resealed"
    PropertyEventType.CONTAINER_TYPE_CHANGE -> "Type of property changed"
    PropertyEventType.MOVED -> "Moved to a different storage location"
    PropertyEventType.PRISONER_RECEIVED -> "Due to be sent on to another establishment"
    PropertyEventType.PRISONER_RELEASED -> "Due to be returned on release"
    PropertyEventType.DIED_IN_CUSTODY -> "Due to be returned"
    PropertyEventType.TRANSFERRED -> "Sent to another establishment"
    PropertyEventType.RETURNED -> "Returned"
    PropertyEventType.DISPOSAL_REQUIRED -> "Due for disposal"
    PropertyEventType.DISPOSED -> "Disposed of"
    PropertyEventType.COMBINED -> "Combined into another sealed container"
    PropertyEventType.CREATED_IN_ERROR -> "Recorded in error"
    PropertyEventType.REMOVED -> "Removed from the establishment"
    PropertyEventType.REACTIVATED -> "Returned to storage"
  }

internal val ContainerType.sarLabel: String
  get() = when (this) {
    ContainerType.STANDARD -> "Standard property"
    ContainerType.EXCESS -> "Excess property"
    ContainerType.VALUABLES -> "Valuables"
    ContainerType.CONFISCATED -> "Confiscated property"
  }

internal val ContainerStatus.sarLabel: String
  get() = when (this) {
    ContainerStatus.STORED -> "In storage"
    ContainerStatus.DUE_FOR_TRANSFER_OUT -> "Due to be sent to another establishment"
    ContainerStatus.DUE_FOR_RETURN -> "Due to be returned"
    ContainerStatus.DISPOSAL_REQUIRED -> "Due for disposal"
    ContainerStatus.DISPOSED -> "Disposed of"
    ContainerStatus.RETURNED -> "Returned"
    ContainerStatus.TRANSFER -> "Sent to another establishment"
    ContainerStatus.COMBINED -> "Combined into another sealed container"
    ContainerStatus.CREATED_IN_ERROR -> "Recorded in error"
    ContainerStatus.REMOVED -> "Removed from the establishment"
  }

internal val RemovalOutcome.sarLabel: String
  get() = when (this) {
    RemovalOutcome.DISPOSED -> "Disposed of"
    RemovalOutcome.RETURNED -> "Returned"
    RemovalOutcome.TRANSFERRED -> "Sent to another establishment"
    RemovalOutcome.COMBINED -> "Combined into another sealed container"
    RemovalOutcome.CREATED_IN_ERROR -> "Recorded in error"
    RemovalOutcome.REMOVED -> "Removed from the establishment"
  }

internal val StorageLocationType.sarLabel: String
  get() = when (this) {
    StorageLocationType.INTERNAL -> "In the establishment"
    StorageLocationType.BRANSTON -> "In the national property store"
  }
