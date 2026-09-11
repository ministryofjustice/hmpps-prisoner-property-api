-- Legacy property clean-up: an admin-run job that closes the backlog of NOMIS-migrated containers a
-- prison still holds for people who were released or transferred out long ago, so the establishment
-- list is usable on the day the property service is switched on there.
--
-- A job snapshots its candidates as items when it is requested, then a listener works through the items
-- one committed transaction at a time, so a redelivery or restart resumes from the unprocessed rows and
-- the counts an admin watches are stable. The RETURNED / TRANSFERRED event each item writes carries the
-- job id: that is what lets the timeline say the closure was automatic, and what stops a clean-up
-- transfer being advertised at the destination as property still awaiting arrival.
-- See V15__schema_comments.sql for the comment conventions.

CREATE TABLE legacy_cleanup_job
(
    id                  UUID        NOT NULL PRIMARY KEY,
    prison_id           VARCHAR(6)  NOT NULL,
    status              VARCHAR(16) NOT NULL,
    older_than_days     INTEGER     NOT NULL,
    cutoff_date         DATE        NOT NULL,
    requested_by        VARCHAR(64) NOT NULL,
    requested_at        TIMESTAMP   NOT NULL,
    start_time          TIMESTAMP,
    last_activity_at    TIMESTAMP,
    end_time            TIMESTAMP,
    total_records       INTEGER     NOT NULL DEFAULT 0,
    returned_records    INTEGER     NOT NULL DEFAULT 0,
    transferred_records INTEGER     NOT NULL DEFAULT 0,
    skipped_records     INTEGER     NOT NULL DEFAULT 0,
    failed_records      INTEGER     NOT NULL DEFAULT 0
);

CREATE INDEX idx_legacy_cleanup_job_prison ON legacy_cleanup_job (prison_id, requested_at DESC);

-- One job in flight per prison, enforced across pods rather than only in the service pre-check.
CREATE UNIQUE INDEX idx_legacy_cleanup_job_active_prison ON legacy_cleanup_job (prison_id)
    WHERE status IN ('PENDING', 'STARTED');

CREATE TABLE legacy_cleanup_item
(
    id                    UUID        NOT NULL PRIMARY KEY,
    legacy_cleanup_job_id UUID        NOT NULL REFERENCES legacy_cleanup_job (id) ON DELETE CASCADE,
    property_container_id UUID        NOT NULL REFERENCES property_container (id) ON DELETE CASCADE,
    prisoner_number       VARCHAR(10) NOT NULL,
    action                VARCHAR(16) NOT NULL,
    planned_event_date    DATE        NOT NULL,
    planned_to_prison_id  VARCHAR(6),
    status                VARCHAR(16) NOT NULL,
    message               VARCHAR(255),
    processed_at          TIMESTAMP
);

CREATE INDEX idx_legacy_cleanup_item_job_status ON legacy_cleanup_item (legacy_cleanup_job_id, status);
CREATE INDEX idx_legacy_cleanup_item_container ON legacy_cleanup_item (property_container_id);

ALTER TABLE property_event
    ADD COLUMN legacy_cleanup_job_id UUID REFERENCES legacy_cleanup_job (id);

------------------------------------------------------------------------------------------------
-- Data dictionary
------------------------------------------------------------------------------------------------

COMMENT ON TABLE legacy_cleanup_job IS 'One admin-requested run of the legacy property clean-up for a prison: closes containers still held there for people released or transferred out before a cut-off date, marking each RETURNED or TRANSFERRED. Processed asynchronously from an SQS work queue, one container per transaction; the counters are updated as it goes so progress can be watched.';
COMMENT ON COLUMN legacy_cleanup_job.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.prison_id IS 'The prison whose held property is being cleaned up. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.status IS 'PENDING (requested, not yet claimed by a listener), STARTED (being processed) or FINISHED. A partial unique index allows one PENDING or STARTED job per prison. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.older_than_days IS 'The look-back window the admin chose: only people who left at least this many days before the request are in scope. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.cutoff_date IS 'The request date minus older_than_days; a person must have left on or before this date to be in scope. Stored so the rule the job applied is reproducible. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.requested_by IS 'DPS username of the admin who requested the run. The property events the run writes are attributed to the LEGACY_CLEANUP system user, so this is the only record of who asked for them. [Sensitivity: STAFF]';
COMMENT ON COLUMN legacy_cleanup_job.requested_at IS 'When the run was requested. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.start_time IS 'When a listener first claimed the job and began processing. Null while PENDING. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.last_activity_at IS 'When the job last made progress. A STARTED job with no activity for 30 minutes is treated as abandoned (its pod died) and may be re-claimed by a redelivered message; only unprocessed items are then resumed. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.end_time IS 'When processing finished. Null until FINISHED. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.total_records IS 'How many containers were snapshotted as items when the job was requested. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.returned_records IS 'How many items have been marked RETURNED so far. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.transferred_records IS 'How many items have been marked TRANSFERRED so far. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.skipped_records IS 'How many items were skipped: the container had already been removed by staff, or the person no longer qualified when re-checked against prisoner-search at processing time. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_job.failed_records IS 'How many items failed with an error; the container was left unchanged and the message on the item says why. [Sensitivity: NONE]';

COMMENT ON TABLE legacy_cleanup_item IS 'One container a legacy clean-up job intends to close, snapshotted with the action decided at request time. Processed one per transaction; the status records what happened to it.';
COMMENT ON COLUMN legacy_cleanup_item.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_item.legacy_cleanup_job_id IS 'The job this item belongs to. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_item.property_container_id IS 'The container to close. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_item.prisoner_number IS 'The prisoner number the container belonged to when the job was requested, so the item can be re-checked against prisoner-search without loading the container. Identifies a prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN legacy_cleanup_item.action IS 'RETURN (the person was released: mark returned) or TRANSFER (the person is at another prison: mark transferred there). [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_item.planned_event_date IS 'The date the closing event will be dated to: the release date, or the date the person left this prison. Taken from prisoner-search when the job was requested. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN legacy_cleanup_item.planned_to_prison_id IS 'For TRANSFER, the prison the person is now at, which becomes the destination on the TRANSFERRED event. Null for RETURN. Locates a prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN legacy_cleanup_item.status IS 'PENDING, PROCESSED (the container was closed), SKIPPED (already removed, or no longer qualified on re-check) or FAILED (an error; the container is unchanged). [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_item.message IS 'Why the item was skipped or failed, or a note that the domain event could not be published after the change was committed. Null when processed cleanly. [Sensitivity: NONE]';
COMMENT ON COLUMN legacy_cleanup_item.processed_at IS 'When the item reached its final status. Null while PENDING. [Sensitivity: NONE]';

COMMENT ON COLUMN property_event.legacy_cleanup_job_id IS 'Set on the RETURNED or TRANSFERRED event when the closure was written by a legacy clean-up job rather than by staff; null on every other event. A TRANSFERRED event carrying it records where the person went for the history, but is never treated as property awaiting arrival at that prison. [Sensitivity: NONE]';

-- event_user_id gains a second reserved system value.
COMMENT ON COLUMN property_event.event_user_id IS 'DPS username of the member of staff who performed the action, or a reserved system user: PRISONER_PROPERTY_API for events raised by NOMIS sync or the prisoner movement listener, LEGACY_CLEANUP for closures written by a legacy clean-up job. Identifies a member of staff. [Sensitivity: STAFF]';
