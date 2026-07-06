package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * Where a prisoner is, from prisoner-search, for describing their property's whereabouts. Distinguishes a
 * prisoner held in an establishment from one who has left it - in transit between prisons (prisonId TRN,
 * lastMovementTypeCode TRN) or released (prisonId OUT, lastMovementTypeCode REL).
 */
enum class PrisonerMovementStatus {
  IN_ESTABLISHMENT,
  IN_TRANSIT,
  RELEASED,
}
