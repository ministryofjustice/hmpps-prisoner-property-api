package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import java.util.UUID

class PropertyContainersNotFoundException(ids: List<UUID?>) : RuntimeException("Property containers not found: $ids")
