-- Generalise the single terminal state (disposed) into a removal outcome covering disposal, return,
-- transfer and (later) combine. Backfill existing disposals, then drop the old column.
ALTER TABLE property_container
    ADD COLUMN removal_outcome VARCHAR(20),
    ADD COLUMN removal_date    DATE;

UPDATE property_container
SET removal_outcome = 'DISPOSED',
    removal_date    = disposed_date
WHERE disposed_date IS NOT NULL;

ALTER TABLE property_container DROP COLUMN disposed_date;
