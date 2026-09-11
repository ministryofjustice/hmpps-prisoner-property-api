package uk.gov.justice.digital.hmpps.prisonerpropertyapi.domain

/**
 * The reserved `event_user_id` values for changes the service makes itself rather than a member of staff.
 * Anything reading a user id off an event (the timeline, the UI's history page) treats these as "the system";
 * a new automated writer must be added here or its events will read as if a user called that performed them.
 */
object PropertySystemUsers {
  /** Changes driven by an inbound prisoner movement or merge event, or by the NOMIS sync. */
  const val PRISONER_PROPERTY_API = "PRISONER_PROPERTY_API"

  /** Closures written by an admin-requested legacy clean-up job (see `docs/legacy-cleanup.md`). */
  const val LEGACY_CLEANUP = "LEGACY_CLEANUP"

  val ALL = setOf(PRISONER_PROPERTY_API, LEGACY_CLEANUP)

  fun isSystem(userId: String): Boolean = userId in ALL
}
