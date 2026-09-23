-- Whether each column is disclosed in a prisoner's subject access request (MAPB-767).
--
-- Added as a third tag on each column comment, between the example and the sensitivity tags:
--
--   '... description text. [Example: SEAL12345] [SAR: Y] [Sensitivity: NONE]'
--
-- This is set deliberately per column rather than inferred from the sensitivity classification. The two
-- answer different questions and inferring one from the other produces a misleading document. Sensitivity
-- describes whether a column's own content is personal data; this describes whether its value reaches the
-- report. Most of what a prisoner's property report contains - the type of property, where it is held, its
-- status, its seal number, the dates, every event in its history - is not personal data in itself, because
-- the personal data is the link to the prisoner that every row already carries. Deriving SAR impact from
-- sensitivity would have marked 8 of 59 elements as in scope when the true figure is 25.
--
-- The rule applied:
--
--   Y  the value reaches the report, whether rendered from this column or from the event it mirrors
--   N  it never does - internal identifiers, and the tables that hold service configuration or the
--      working state of an administrative job
--
-- The source of truth for Y is the SAR response itself: dto/sar/SarPropertyContainer.kt and
-- SarPropertyEvent.kt define exactly what is disclosed, so these values are transcribed from them rather
-- than judged. Change the response and this needs changing with it.
--
-- Two calls worth stating plainly, because the Offender SAR Team may take a different view at the data
-- review (MAPB-768) and that is their call to make:
--
--   * property_event.related_container_id is N. The related container's *seal number* is disclosed
--     because it means something to the person; its internal id is deliberately withheld.
--   * legacy_cleanup_item.prisoner_number is N. It is personal data about the prisoner, but what the
--     clean-up did to their property already reaches the report as an event on the container itself, so
--     the item row adds nothing but the working state of an administrative job.

DO $$
DECLARE
  rec      record;
  existing text;
BEGIN
  FOR rec IN
    SELECT * FROM (VALUES
      -- Service configuration: which prisons have been switched on. Nothing about a prisoner.
      ('active_agency', 'agency_id', 'N'),
      ('active_agency', 'active', 'N'),
      ('active_agency', 'updated_at', 'N'),
      ('active_agency', 'updated_by', 'N'),

      -- The working state of an administrative clean-up run. Its effect on a person's property is
      -- disclosed as the RETURNED or TRANSFERRED event it writes to the container.
      ('legacy_cleanup_job', 'id', 'N'),
      ('legacy_cleanup_job', 'prison_id', 'N'),
      ('legacy_cleanup_job', 'status', 'N'),
      ('legacy_cleanup_job', 'older_than_days', 'N'),
      ('legacy_cleanup_job', 'cutoff_date', 'N'),
      ('legacy_cleanup_job', 'requested_by', 'N'),
      ('legacy_cleanup_job', 'requested_at', 'N'),
      ('legacy_cleanup_job', 'start_time', 'N'),
      ('legacy_cleanup_job', 'last_activity_at', 'N'),
      ('legacy_cleanup_job', 'end_time', 'N'),
      ('legacy_cleanup_job', 'total_records', 'N'),
      ('legacy_cleanup_job', 'returned_records', 'N'),
      ('legacy_cleanup_job', 'transferred_records', 'N'),
      ('legacy_cleanup_job', 'skipped_records', 'N'),
      ('legacy_cleanup_job', 'failed_records', 'N'),

      ('legacy_cleanup_item', 'id', 'N'),
      ('legacy_cleanup_item', 'legacy_cleanup_job_id', 'N'),
      ('legacy_cleanup_item', 'property_container_id', 'N'),
      ('legacy_cleanup_item', 'prisoner_number', 'N'),
      ('legacy_cleanup_item', 'action', 'N'),
      ('legacy_cleanup_item', 'planned_event_date', 'N'),
      ('legacy_cleanup_item', 'planned_to_prison_id', 'N'),
      ('legacy_cleanup_item', 'status', 'N'),
      ('legacy_cleanup_item', 'message', 'N'),
      ('legacy_cleanup_item', 'processed_at', 'N'),

      -- The container itself. Everything but the internal id is in the report.
      ('property_container', 'id', 'N'),
      ('property_container', 'prisoner_number', 'Y'),
      ('property_container', 'prison_id', 'Y'),
      ('property_container', 'container_type', 'Y'),
      ('property_container', 'create_datetime', 'Y'),
      ('property_container', 'created_by_user_id', 'Y'),
      ('property_container', 'proposed_disposal_date', 'Y'),
      ('property_container', 'current_seal_number', 'Y'),
      ('property_container', 'removal_outcome', 'Y'),
      ('property_container', 'removal_date', 'Y'),
      ('property_container', 'current_status', 'Y'),
      ('property_container', 'current_internal_location_id', 'Y'),
      ('property_container', 'current_storage_location_type', 'Y'),
      ('property_container', 'receiving_prison_id', 'Y'),

      -- The history. Every event is disclosed in full apart from the two internal identifiers.
      ('property_event', 'id', 'N'),
      ('property_event', 'property_container_id', 'N'),
      ('property_event', 'event_type', 'Y'),
      ('property_event', 'seal_number', 'Y'),
      ('property_event', 'event_datetime', 'Y'),
      ('property_event', 'event_date', 'Y'),
      ('property_event', 'event_user_id', 'Y'),
      ('property_event', 'from_internal_location_id', 'Y'),
      ('property_event', 'to_internal_location_id', 'Y'),
      ('property_event', 'to_storage_location_type', 'Y'),
      ('property_event', 'from_prison_id', 'Y'),
      ('property_event', 'to_prison_id', 'Y'),
      ('property_event', 'related_container_id', 'N'),
      ('property_event', 'related_container_seal_number', 'Y'),
      ('property_event', 'container_type', 'Y'),
      ('property_event', 'legacy_cleanup_job_id', 'N')
    ) AS t(table_name, column_name, sar_impact)
  LOOP
    SELECT col_description(a.attrelid, a.attnum)
      INTO existing
      FROM pg_attribute a
     WHERE a.attrelid = format('%I', rec.table_name)::regclass
       AND a.attname = rec.column_name
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF existing IS NULL THEN
      RAISE EXCEPTION 'No comment on %.% - describe the column before classifying it',
        rec.table_name, rec.column_name;
    END IF;

    IF position(' [Sensitivity:' IN existing) = 0 THEN
      RAISE EXCEPTION 'Comment on %.% has no sensitivity tag to place the SAR tag before',
        rec.table_name, rec.column_name;
    END IF;

    EXECUTE format(
      'COMMENT ON COLUMN %I.%I IS %L',
      rec.table_name,
      rec.column_name,
      replace(existing, ' [Sensitivity:', ' [SAR: ' || rec.sar_impact || '] [Sensitivity:')
    );
  END LOOP;
END $$;
