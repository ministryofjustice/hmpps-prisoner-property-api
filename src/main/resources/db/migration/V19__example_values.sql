-- Example values for the SAR Data Requirements extract (MAPB-767).
--
-- The Offender SAR Team review a document with an "Example Value" for every element, so they can see
-- what a column actually holds without being shown live data. This adds that value to each column
-- comment as a second tag, immediately before the sensitivity tag:
--
--   '... description text. [Example: SEAL12345] [Sensitivity: NONE]'
--
-- It goes *before* the sensitivity tag rather than after because SchemaCommentsTest anchors the
-- sensitivity pattern to the end of the comment. Appending an example after it would fail the build.
--
-- This migration carries only the examples, not the descriptions. It reads each existing comment and
-- splices the tag in, so V15__schema_comments.sql remains the one place the wording lives and the two
-- cannot drift apart. Later migrations adding a column should include its example in the same style;
-- SchemaCommentsTest fails the build if one is missing.
--
-- Every value here is invented. Prison numbers follow the standard A1234AA dummy form, UUIDs use
-- repeated digits so they are obviously synthetic, and seal numbers are made up - a realistic-looking
-- seal number matters because it is the field the person the report is about will recognise, but it
-- must not be one that could belong to a real container.

DO $$
DECLARE
  rec      record;
  existing text;
BEGIN
  FOR rec IN
    SELECT * FROM (VALUES
      ('active_agency', 'agency_id', 'LEI'),
      ('active_agency', 'active', 'true'),
      ('active_agency', 'updated_at', '2026-09-15T14:32:00'),
      ('active_agency', 'updated_by', 'AUSER_GEN'),

      ('legacy_cleanup_job', 'id', '66666666-6666-6666-6666-666666666666'),
      ('legacy_cleanup_job', 'prison_id', 'LEI'),
      ('legacy_cleanup_job', 'status', 'FINISHED'),
      ('legacy_cleanup_job', 'older_than_days', '90'),
      ('legacy_cleanup_job', 'cutoff_date', '2026-06-17'),
      ('legacy_cleanup_job', 'requested_by', 'AUSER_GEN'),
      ('legacy_cleanup_job', 'requested_at', '2026-09-15T09:05:00'),
      ('legacy_cleanup_job', 'start_time', '2026-09-15T09:05:12'),
      ('legacy_cleanup_job', 'last_activity_at', '2026-09-15T09:11:48'),
      ('legacy_cleanup_job', 'end_time', '2026-09-15T09:12:03'),
      ('legacy_cleanup_job', 'total_records', '412'),
      ('legacy_cleanup_job', 'returned_records', '118'),
      ('legacy_cleanup_job', 'transferred_records', '286'),
      ('legacy_cleanup_job', 'skipped_records', '6'),
      ('legacy_cleanup_job', 'failed_records', '2'),

      ('legacy_cleanup_item', 'id', '77777777-7777-7777-7777-777777777777'),
      ('legacy_cleanup_item', 'legacy_cleanup_job_id', '66666666-6666-6666-6666-666666666666'),
      ('legacy_cleanup_item', 'property_container_id', '11111111-1111-1111-1111-111111111111'),
      ('legacy_cleanup_item', 'prisoner_number', 'A1234AA'),
      ('legacy_cleanup_item', 'action', 'TRANSFER'),
      ('legacy_cleanup_item', 'planned_event_date', '2026-03-14'),
      ('legacy_cleanup_item', 'planned_to_prison_id', 'MDI'),
      ('legacy_cleanup_item', 'status', 'PROCESSED'),
      ('legacy_cleanup_item', 'message', 'Skipped - the container had already been removed'),
      ('legacy_cleanup_item', 'processed_at', '2026-09-15T09:11:48'),

      ('property_container', 'id', '11111111-1111-1111-1111-111111111111'),
      ('property_container', 'prisoner_number', 'A1234AA'),
      ('property_container', 'prison_id', 'LEI'),
      ('property_container', 'container_type', 'STANDARD'),
      ('property_container', 'create_datetime', '2026-01-10T09:15:00'),
      ('property_container', 'created_by_user_id', 'AUSER_GEN'),
      ('property_container', 'proposed_disposal_date', '2026-11-01'),
      ('property_container', 'current_seal_number', 'SEAL12345'),
      ('property_container', 'removal_outcome', 'TRANSFERRED'),
      ('property_container', 'removal_date', '2026-07-18'),
      ('property_container', 'current_status', 'STORED'),
      ('property_container', 'current_internal_location_id', '33333333-3333-3333-3333-333333333333'),
      ('property_container', 'current_storage_location_type', 'INTERNAL'),
      ('property_container', 'receiving_prison_id', 'MDI'),

      ('property_event', 'id', '22222222-2222-2222-2222-222222222222'),
      ('property_event', 'property_container_id', '11111111-1111-1111-1111-111111111111'),
      ('property_event', 'event_type', 'SEAL_CHANGED'),
      ('property_event', 'seal_number', 'SEAL12345'),
      ('property_event', 'event_datetime', '2026-02-14T11:30:00'),
      ('property_event', 'event_date', '2026-07-18'),
      ('property_event', 'event_user_id', 'AUSER_GEN'),
      ('property_event', 'from_internal_location_id', '33333333-3333-3333-3333-333333333333'),
      ('property_event', 'to_internal_location_id', '44444444-4444-4444-4444-444444444444'),
      ('property_event', 'to_storage_location_type', 'BRANSTON'),
      ('property_event', 'from_prison_id', 'LEI'),
      ('property_event', 'to_prison_id', 'MDI'),
      ('property_event', 'related_container_id', '55555555-5555-5555-5555-555555555555'),
      ('property_event', 'related_container_seal_number', 'SEAL99123'),
      ('property_event', 'container_type', 'VALUABLES'),
      ('property_event', 'legacy_cleanup_job_id', '66666666-6666-6666-6666-666666666666')
    ) AS t(table_name, column_name, example)
  LOOP
    SELECT col_description(a.attrelid, a.attnum)
      INTO existing
      FROM pg_attribute a
     WHERE a.attrelid = format('%I', rec.table_name)::regclass
       AND a.attname = rec.column_name
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF existing IS NULL THEN
      RAISE EXCEPTION 'No comment on %.% - describe the column before giving it an example',
        rec.table_name, rec.column_name;
    END IF;

    IF position(' [Sensitivity:' IN existing) = 0 THEN
      RAISE EXCEPTION 'Comment on %.% has no sensitivity tag to place the example before',
        rec.table_name, rec.column_name;
    END IF;

    EXECUTE format(
      'COMMENT ON COLUMN %I.%I IS %L',
      rec.table_name,
      rec.column_name,
      replace(existing, ' [Sensitivity:', ' [Example: ' || rec.example || '] [Sensitivity:')
    );
  END LOOP;
END $$;
