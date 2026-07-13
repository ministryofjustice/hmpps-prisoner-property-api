package uk.gov.justice.digital.hmpps.prisonerpropertyapi.service

import java.util.UUID

/** A property storage location was not found (or is no longer a property location). Maps to 404. */
class PropertyLocationNotFoundException(id: UUID) : RuntimeException("Property location not found: $id")

/** A property storage location cannot be removed because it still holds containers. Maps to 409. */
class PropertyLocationInUseException(id: UUID, containersHeld: Long) : RuntimeException("Property location $id still holds $containersHeld container(s) and cannot be removed")

/** A property storage location's capacity cannot be set below the number of containers it already holds. Maps to 409. */
class PropertyLocationCapacityBelowUsageException(id: UUID, capacity: Int, containersHeld: Long) : RuntimeException("Property location $id holds $containersHeld container(s), so its capacity cannot be set to $capacity")

/** A property storage location with this name already exists in the prison. Maps to 409. */
class DuplicatePropertyLocationNameException(localName: String) : RuntimeException("A storage location named '$localName' already exists in this prison")
