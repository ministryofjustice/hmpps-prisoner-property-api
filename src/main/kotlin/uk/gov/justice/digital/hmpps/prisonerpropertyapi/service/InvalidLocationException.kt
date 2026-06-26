package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import java.util.UUID

class InvalidLocationException(id: UUID, reason: String) : RuntimeException("Internal location $reason: $id")
