-- Data dictionary for the prisoner property schema.
--
-- These comments are read by SchemaSpy (published to GitHub Pages) and by anything else that reads
-- pg_description, including the CSV export for the MOJ Data Catalogue / Glue. Keep them updated when
-- columns are added or their meaning changes - SchemaCommentsTest fails the build if a table or column
-- has no comment.
--
-- Every column comment ends with a sensitivity classification:
--
--   [Sensitivity: NONE]                - not personal data in itself
--   [Sensitivity: PERSONAL]            - personal data about a prisoner: identifies or locates them,
--                                        or is free text that could
--   [Sensitivity: STAFF]               - personal data about a member of staff, typically the username
--                                        that performed an action
--   [Sensitivity: SPECIAL-CATEGORY]    - UK GDPR Article 9 data (health, sexuality, religion, race),
--                                        or criminal offence data under Article 10
--   [Sensitivity: OFFICIAL-SENSITIVE]  - not personal data, but damaging if disclosed (e.g. security
--                                        arrangements)
--
-- STAFF is still personal data and still in scope for a staff member's own subject access request. It is
-- separated from PERSONAL so that an extract about prisoners can be reasoned about without staff columns
-- inflating the count, and so staff data can be dropped or pseudonymised independently.
--
-- Two things to understand before using these classifications:
--
--   1. They describe the column's own content, not the row's. Every row in property_container and
--      property_event belongs to a prisoner via property_container.prisoner_number, so the whole record
--      is personal data about that prisoner whatever an individual column is marked - that is what
--      matters for a subject access request.
--   2. Nothing in this schema is special category. Property records describe belongings and where they
--      are stored; they carry no health, religion or offence data. The free-text fields here are seal
--      numbers, not narrative.

------------------------------------------------------------------------------------------------
-- property_container - the sealed containers themselves
------------------------------------------------------------------------------------------------

COMMENT ON TABLE property_container IS 'A sealed container of one prisoner''s property. The record is event-sourced: its history lives in property_event and its current status and location are derived from the most recent relevant event. Five values are mirrored into columns here (seal number, status, internal location, storage location type, receiving prison) so the establishment-wide list can filter and page in SQL; the derivation stays authoritative and every write refreshes the mirrors.';

COMMENT ON COLUMN property_container.id IS 'Primary key. Time-ordered UUID v7, so insert order matches id order. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.prisoner_number IS 'NOMIS offender number (noms id) of the prisoner whose property this is. The link that makes every row here personal data about that prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN property_container.prison_id IS 'Agency (prison) code holding the container. Read with prisoner_number it indicates where that prisoner''s property is, and normally where the prisoner is. Not reassigned on a transfer out - the receiving prison creates its own record. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN property_container.container_type IS 'What kind of property the container holds. One of STANDARD, EXCESS, VALUABLES, CONFISCATED. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.create_datetime IS 'When the container record was created in this service. For containers migrated from NOMIS this is the migration run, not the original prison event. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.created_by_user_id IS 'DPS username of the member of staff who created the container. Identifies a member of staff. [Sensitivity: STAFF]';
COMMENT ON COLUMN property_container.proposed_disposal_date IS 'Date the container is proposed for disposal. Disposal status is derived by comparing this with today rather than being stored, so a container becomes DISPOSAL_REQUIRED without anything writing to it. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.current_seal_number IS 'Seal number currently on the container. Stored rather than derived because uniqueness across containers in storage is checked in SQL. Freed for re-use once the container leaves active storage. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.removal_outcome IS 'Why the container left active storage: DISPOSED, RETURNED, TRANSFERRED, COMBINED, CREATED_IN_ERROR or REMOVED. Null while the container is in active storage. All are terminal except REMOVED, which a REACTIVATED event reverses - which is why there is no archived flag. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.removal_date IS 'Date the container left active storage. Null while it is still held. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.current_status IS 'Denormalised mirror of the derived status, excluding the time-based disposal overlay so the column stays stable over time. One of STORED, DUE_FOR_TRANSFER_OUT, DUE_FOR_RETURN, DISPOSAL_REQUIRED, DISPOSED, RETURNED, TRANSFER, COMBINED, CREATED_IN_ERROR, REMOVED. Read the events, not this column, if you need the authoritative status. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.current_internal_location_id IS 'Denormalised mirror of the container''s current storage location - a location UUID in hmpps-locations-inside-prison-api. Null when the container is offsite at Branston, has no recorded location, or has left active storage. This is where the property is kept, not where the prisoner is. [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.current_storage_location_type IS 'Denormalised mirror of where the container is stored: INTERNAL (a location within the prison) or BRANSTON (the offsite warehouse, which has no internal location id). [Sensitivity: NONE]';
COMMENT ON COLUMN property_container.receiving_prison_id IS 'Agency code of the prison this container is due to be transferred in to, denormalised so the establishment list can surface incoming property. Set only while the container is due for transfer out, or transferred but not yet reconciled with the receiving prison''s record. Indicates where the owning prisoner has moved to. [Sensitivity: PERSONAL]';

------------------------------------------------------------------------------------------------
-- property_event - the container's history
------------------------------------------------------------------------------------------------

COMMENT ON TABLE property_event IS 'One thing that happened to a container, in an ordered history. This is the authoritative record: the container''s current seal, status and location are derived from the most recent relevant event, and the columns on property_container are mirrors of that derivation. Events are appended, never amended, so this table is also the audit trail.';

COMMENT ON COLUMN property_event.id IS 'Primary key. Time-ordered UUID v7. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.property_container_id IS 'Foreign key to property_container - the container this event happened to. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.event_type IS 'What happened. One of CREATED_SEALED, SEAL_CHANGED, CONTAINER_TYPE_CHANGE, MOVED, TRANSFERRED, RETURNED, DISPOSAL_REQUIRED, DISPOSED, COMBINED, CREATED_IN_ERROR, REMOVED, REACTIVATED, or the three driven by prisoner movements - PRISONER_RECEIVED, PRISONER_RELEASED, DIED_IN_CUSTODY. Each type implies a status. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.seal_number IS 'Seal number recorded by this event, on the event types that carry one (creation and reseal). Null on events that do not change the seal. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.event_datetime IS 'When the event happened. Events are ordered by this; where two share a timestamp the later-appended one wins. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.event_date IS 'The date the underlying prison activity happened, where it differs from when it was recorded (for example a disposal logged after the event). Null when the recorded timestamp is the date. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.event_user_id IS 'DPS username of the member of staff who performed the action, or the system user for events raised by NOMIS sync or the prisoner movement listener. Identifies a member of staff. [Sensitivity: STAFF]';
COMMENT ON COLUMN property_event.from_internal_location_id IS 'Storage location the container moved from - a location UUID in hmpps-locations-inside-prison-api. Null when the event does not move it. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.to_internal_location_id IS 'Storage location the container moved to - a location UUID in hmpps-locations-inside-prison-api. Null when the event does not move it, or when it moved offsite to Branston (which has no internal location id). [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.to_storage_location_type IS 'The kind of location the container moved to: INTERNAL or BRANSTON. Older events recorded an internal location id without an explicit type, and are read as INTERNAL. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.from_prison_id IS 'Agency code the container was held at before this event. Set on events that move it between prisons. Indicates the prisoner''s previous establishment. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN property_event.to_prison_id IS 'Agency code the container is going to. Set on a transfer out, and on the PRISONER_RECEIVED that flags property as due to follow its owner - so it indicates where the prisoner has moved to. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN property_event.related_container_id IS 'The other container involved: the one this container was combined into, or the matching record at the other end of a transfer. Filled in later on a transfer out, once the receiving prison logs the arrival and the two records are reconciled. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.related_container_seal_number IS 'The related container''s seal number as at this event, snapshotted so the history names it by the seal it had at the time rather than any later reseal. [Sensitivity: NONE]';
COMMENT ON COLUMN property_event.container_type IS 'The container''s type at the moment of the event, snapshotted so the history is a self-contained record. On a type change this holds the new type. [Sensitivity: NONE]';

------------------------------------------------------------------------------------------------
-- active_agency - prison rollout
------------------------------------------------------------------------------------------------

COMMENT ON TABLE active_agency IS 'One row per prison that has ever been switched on for managing property in DPS rather than NOMIS. The list is published on the public actuator /info payload, which is how the front end decides whether to offer the service at a prison. Switching a prison off flips the flag rather than deleting the row, so deactivation stays auditable and the toggle is idempotent.';

COMMENT ON COLUMN active_agency.agency_id IS 'Primary key. Agency (prison) code. [Sensitivity: NONE]';
COMMENT ON COLUMN active_agency.active IS 'Whether the prison is currently switched on. False means it was switched on at some point and has since been switched off. [Sensitivity: NONE]';
COMMENT ON COLUMN active_agency.updated_at IS 'When the prison was last switched on or off. [Sensitivity: NONE]';
COMMENT ON COLUMN active_agency.updated_by IS 'DPS username of the member of staff who last changed the switch. Identifies a member of staff. [Sensitivity: STAFF]';
