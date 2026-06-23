package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import java.util.UUID

/**
 * Builds the [HmppsDomainEvent]s this service publishes for property container changes, so the NOMIS
 * sync path and the DPS change path produce a consistent envelope. The two paths differ only in the
 * human-readable [description] and whether `additionalInformation` carries a `nomisPropertyContainerId`.
 */
object PropertyContainerEventFactory {

  private const val SYNC_DESCRIPTION = "A prisoner property container was synchronised from NOMIS"
  private const val CHANGE_DESCRIPTION = "A prisoner property container was changed in DPS"

  /** Event for a NOMIS-driven sync change (carries the originating NOMIS container id). */
  fun syncEvent(
    eventType: PropertyDomainEventType,
    dpsId: UUID,
    nomisPropertyContainerId: Long,
    prisonerNumber: String,
    changedFields: List<String>?,
  ): HmppsDomainEvent = event(
    eventType,
    dpsId,
    prisonerNumber,
    SYNC_DESCRIPTION,
    changedFields,
    additional = mapOf("nomisPropertyContainerId" to nomisPropertyContainerId),
  )

  /** Event for a change originating in DPS (staff write or internal event handler). */
  fun changeEvent(
    eventType: PropertyDomainEventType,
    dpsId: UUID,
    prisonerNumber: String,
    changedFields: List<String>?,
  ): HmppsDomainEvent = event(eventType, dpsId, prisonerNumber, CHANGE_DESCRIPTION, changedFields)

  private fun event(
    eventType: PropertyDomainEventType,
    dpsId: UUID,
    prisonerNumber: String,
    description: String,
    changedFields: List<String>?,
    additional: Map<String, Any?> = emptyMap(),
  ): HmppsDomainEvent = HmppsDomainEvent(
    eventType = eventType.value,
    description = description,
    prisonerNumber = prisonerNumber,
    additionalInformation = buildMap {
      put("dpsId", dpsId.toString())
      putAll(additional)
      changedFields?.let { put("changedFields", it) }
    },
  )
}
