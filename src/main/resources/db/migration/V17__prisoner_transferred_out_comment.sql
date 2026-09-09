-- PRISONER_TRANSFERRED_OUT joins the movement-driven event types.
--
-- The sending prison's half of a transfer. Recorded when the person leaves on transfer, hours before any
-- reception, so that property they leave behind reads as due to follow them without depending on a live
-- prisoner-search lookup. It carries no to_prison_id: the transfer-out movement does not say where the
-- person is going, and nobody knows until they are received somewhere.
--
-- One move can therefore produce two events against a container - PRISONER_TRANSFERRED_OUT when they leave,
-- PRISONER_RECEIVED when they arrive - and both are true. The second is what records the destination.
-- See V15__schema_comments.sql for the conventions; a later migration may replace any comment.

COMMENT ON COLUMN property_event.event_type IS 'What happened. One of CREATED_SEALED, SEAL_CHANGED, CONTAINER_TYPE_CHANGE, MOVED, TRANSFERRED, RETURNED, DISPOSAL_REQUIRED, DISPOSED, COMBINED, CREATED_IN_ERROR, REMOVED, REACTIVATED, or the four driven by prisoner movements - PRISONER_TRANSFERRED_OUT, PRISONER_RECEIVED, PRISONER_RELEASED, DIED_IN_CUSTODY. Each type implies a status. [Sensitivity: NONE]';
