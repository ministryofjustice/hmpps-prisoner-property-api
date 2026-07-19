package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

/**
 * The system a property container change originated in. Carried on the outbound [HmppsDomainEvent] so a
 * downstream sync-back can tell a NOMIS-driven change (that DPS merely synced in) apart from a genuine DPS
 * change, and ignore the former to avoid a NOMIS -> DPS -> NOMIS loop.
 */
enum class PropertyEventSource {
  NOMIS,
  DPS,
}
