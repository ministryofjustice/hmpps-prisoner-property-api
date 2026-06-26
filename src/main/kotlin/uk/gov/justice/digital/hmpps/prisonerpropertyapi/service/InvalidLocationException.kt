package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import java.util.UUID

class InvalidLocationException(id: UUID) : RuntimeException("Internal location not found: $id")
