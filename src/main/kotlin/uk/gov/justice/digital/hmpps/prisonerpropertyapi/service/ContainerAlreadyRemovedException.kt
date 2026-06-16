package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain.RemovalOutcome
import java.util.UUID

class ContainerAlreadyRemovedException(id: UUID, outcome: RemovalOutcome) : RuntimeException("Property container has already left active storage ($outcome): $id")
