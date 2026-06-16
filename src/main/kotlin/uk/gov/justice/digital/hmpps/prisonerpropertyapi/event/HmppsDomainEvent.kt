package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import java.time.Instant

data class HmppsDomainEvent(
  val eventType: String,
  val version: Int = 1,
  val description: String? = null,
  val detailUrl: String? = null,
  val occurredAt: String = Instant.now().toString(),
  val prisonerNumber: String? = null,
  val additionalInformation: Map<String, Any?>? = null,
)
