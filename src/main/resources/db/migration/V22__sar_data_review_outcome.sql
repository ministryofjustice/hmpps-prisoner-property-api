-- The outcome of the Offender SAR Team's data review (MAPB-768), applied to the SAR tags set in V20.
--
-- The team went through the data dictionary row by row and marked which elements a prisoner should be given
-- in their report. They agreed with every call in V20 except four, which they want left out:
--
--   * property_event.from_internal_location_id and to_internal_location_id - where in the establishment the
--     property was stored. The report still says whether it was held in the establishment or sent to the
--     national property store (to_storage_location_type), and which establishment held it.
--   * property_container.current_internal_location_id - the same location, mirrored onto the container.
--   * property_container.prisoner_number - personal data, but the SAR tool already prints the prisoner's
--     name and number at the top of every page, so the report body does not repeat it.
--
-- From V2 of the report template the two locations are no longer rendered. The SAR response still carries
-- them while V1 is the registered template in preprod and production, and is due to lose them once V2 has
-- been signed off and rolled out there. The tag records what reaches the report, so it changes now.

DO $$
DECLARE
  rec      record;
  existing text;
BEGIN
  FOR rec IN
    SELECT * FROM (VALUES
      ('property_container', 'prisoner_number'),
      ('property_container', 'current_internal_location_id'),
      ('property_event', 'from_internal_location_id'),
      ('property_event', 'to_internal_location_id')
    ) AS t(table_name, column_name)
  LOOP
    SELECT col_description(a.attrelid, a.attnum)
      INTO existing
      FROM pg_attribute a
     WHERE a.attrelid = format('%I', rec.table_name)::regclass
       AND a.attname = rec.column_name
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF existing IS NULL OR position(' [SAR: Y]' IN existing) = 0 THEN
      RAISE EXCEPTION 'Comment on %.% has no [SAR: Y] tag to change', rec.table_name, rec.column_name;
    END IF;

    EXECUTE format(
      'COMMENT ON COLUMN %I.%I IS %L',
      rec.table_name,
      rec.column_name,
      replace(existing, ' [SAR: Y]', ' [SAR: N]')
    );
  END LOOP;
END $$;
