package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import java.util.UUID

class PropertyContainerNotFoundException(id: UUID) : RuntimeException("Property container not found: $id")
