package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

class DuplicateSealNumberException(sealNumber: String) : RuntimeException("Seal number is already in use by another active container: $sealNumber")
