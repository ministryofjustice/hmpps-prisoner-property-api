package uk.gov.justice.digital.hmpps.prisonerpropertyapi.event

/**
 * The App Insights custom-event names this service raises.
 *
 * All kebab-case with a `prison-property-` prefix, so the whole set is queryable as one:
 * `customEvents | where name startswith "prison-property-"`. Names for *published* domain events are
 * not listed here - they are derived from the event type by [telemetryNameFor], so the two
 * vocabularies cannot drift apart.
 */
object PropertyTelemetry {
  /** A write, sync or inbound event that turned out to change nothing, so no domain event was published. */
  const val WRITE_NO_CHANGE = "prison-property-write-no-change"
  const val SYNC_NO_CHANGE = "prison-property-sync-no-change"
  const val PRISONER_EVENT_NO_CHANGE = "prison-property-prisoner-event-no-change"

  /** An inbound domain event we received but deliberately did nothing with; carries a `reason`. */
  const val PRISONER_EVENT_IGNORED = "prison-property-prisoner-event-ignored"

  /** A NOMIS bulk migrate, which raises no domain event by design and is otherwise invisible. */
  const val MIGRATED = "prison-property-migrated"

  /** A prison switched on or off for property. Raises no domain event either. */
  const val PRISON_ROLLOUT_CHANGED = "prison-property-prison-rollout-changed"

  /**
   * The SNS publish failed *after* the transaction committed - the data change is durable but the event
   * is gone. Without this the loss is invisible in both logs and telemetry.
   */
  const val EVENT_PUBLISH_FAILED = "prison-property-event-publish-failed"
}

/**
 * The telemetry name for a published domain event, derived from its type rather than written out:
 * `prison-property.container.created` becomes `prison-property-container-created`. Deriving it means a
 * new event type gets a correctly-named custom event for free, and the two can never disagree.
 */
fun telemetryNameFor(eventType: String): String = eventType.replace('.', '-')
