package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

/** The HMPPS domain event types this service publishes to the `domainevents` SNS topic. */
enum class PropertyDomainEventType(val value: String) {
  CONTAINER_CREATED("prison-property.container.created"),
  CONTAINER_UPDATED("prison-property.container.updated"),
}
