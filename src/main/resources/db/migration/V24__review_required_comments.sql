-- PRISONER_ADMITTED and LOCATION_CONFIRMED join the event types, and REVIEW_REQUIRED the statuses (MAPB-849).
--
-- When a prisoner is admitted - a new period of custody or a recall - any property still held for them from
-- before may not be where the record says, so it is put under review (PRISONER_ADMITTED) until staff confirm
-- its location (LOCATION_CONFIRMED) or remove it. No data changes: it applies to admissions from now on.
-- See V15__schema_comments.sql for the conventions; this replaces the comments V17 and V20 left.

COMMENT ON COLUMN property_event.event_type IS 'What happened. One of CREATED_SEALED, SEAL_CHANGED, CONTAINER_TYPE_CHANGE, MOVED, TRANSFERRED, RETURNED, DISPOSAL_REQUIRED, DISPOSED, COMBINED, CREATED_IN_ERROR, REMOVED, REACTIVATED, LOCATION_CONFIRMED, or the five driven by prisoner movements - PRISONER_TRANSFERRED_OUT, PRISONER_RECEIVED, PRISONER_RELEASED, DIED_IN_CUSTODY, PRISONER_ADMITTED. Each type implies a status. [Example: SEAL_CHANGED] [SAR: Y] [Sensitivity: NONE]';

COMMENT ON COLUMN property_container.current_status IS 'Denormalised mirror of the derived status, excluding the time-based disposal overlay so the column stays stable over time. One of STORED, DUE_FOR_TRANSFER_OUT, DUE_FOR_RETURN, REVIEW_REQUIRED, DISPOSAL_REQUIRED, DISPOSED, RETURNED, TRANSFER, COMBINED, CREATED_IN_ERROR, REMOVED. Read the events, not this column, if you need the authoritative status. [Example: STORED] [SAR: Y] [Sensitivity: NONE]';
