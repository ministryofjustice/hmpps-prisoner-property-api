package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

import java.time.Instant

data class HmppsDomainEvent(
  val eventType: String,
  val version: Int = 1,
  val description: String? = null,
  val detailUrl: String? = null,
  val occurredAt: String = Instant.now().toString(),
  val personReference: PersonReference? = null,
  val additionalInformation: Map<String, Any?>? = null,
)

data class PersonReference(val identifiers: List<Identifier> = emptyList()) {
  fun nomsNumber(): String? = identifiers.firstOrNull { it.type == "NOMS" }?.value

  companion object {
    fun withPrisonerNumber(prisonerNumber: String) =
      PersonReference(listOf(Identifier("NOMS", prisonerNumber)))
  }
}

data class Identifier(val type: String, val value: String)
