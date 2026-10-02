-- The legacy property clean-up applies the fixed 13-month retention rule (MAPB-854) instead of a look-back window
-- an admin chose, and marks the property it closes REMOVED rather than returned or transferred: once the person
-- has been gone 13 months, removed is all that can be said for certain about what happened to it.
--
-- older_than_days is kept for jobs run under the old window and is null for every job since. Jobs from then also
-- keep their returned / transferred counts; a new removed_records counts the REMOVE items of jobs since.

ALTER TABLE legacy_cleanup_job ALTER COLUMN older_than_days DROP NOT NULL;
ALTER TABLE legacy_cleanup_job ADD COLUMN removed_records INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN legacy_cleanup_job.older_than_days IS 'The look-back window in days an admin chose, for jobs run before the fixed 13-month retention rule (MAPB-854); null for every job since. [Example: 28] [SAR: N] [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.cutoff_date IS 'A person must have left on or before this date for their property to be closed: 13 months before the request date (the request date minus older_than_days for jobs run before the 13-month rule). Stored so the rule the job applied is reproducible. [Example: 2025-09-02] [SAR: N] [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.removed_records IS 'How many items have been marked REMOVED so far. [Example: 404] [SAR: N] [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.returned_records IS 'How many items were marked RETURNED, by jobs run before the 13-month rule; always 0 since. [Example: 118] [SAR: N] [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.transferred_records IS 'How many items were marked TRANSFERRED, by jobs run before the 13-month rule; always 0 since. [Example: 286] [SAR: N] [Sensitivity: NONE]';

COMMENT ON COLUMN legacy_cleanup_item.action IS 'REMOVE (the person left more than 13 months ago: mark the container removed). RETURN (mark returned) and TRANSFER (mark transferred to where the person is now) are from jobs run before the 13-month rule. [Example: REMOVE] [SAR: N] [Sensitivity: NONE]';

COMMENT ON COLUMN property_event.legacy_cleanup_job_id IS 'Set when a legacy clean-up job wrote the event rather than staff or the NOMIS sync: a REMOVED event under the 13-month retention rule, or a RETURNED or TRANSFERRED event from jobs run before it; null on every other event. It marks the removal as an automatic archive of a legacy record, which the NOMIS sync never reverses. A TRANSFERRED event carrying it records where the person went for the history, but is never treated as property awaiting arrival at that prison. [Example: 66666666-6666-6666-6666-666666666666] [SAR: N] [Sensitivity: NONE]';

COMMENT ON COLUMN property_container.removal_outcome IS 'Why the container left active storage: DISPOSED, RETURNED, TRANSFERRED, COMBINED, CREATED_IN_ERROR or REMOVED. Null while the container is in active storage. REMOVED means NOMIS marked the container inactive, or the legacy clean-up archived it under the 13-month retention rule (its REMOVED event carries legacy_cleanup_job_id). All are terminal except a REMOVED from NOMIS, which a REACTIVATED event reverses - which is why there is no archived flag. [Example: TRANSFERRED] [SAR: Y] [Sensitivity: NONE]';
