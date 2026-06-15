-- Denormalised current seal number, maintained on every seal write, to back the staff
-- seal-uniqueness check and future seal search. The index is non-unique: legacy/migrated data may
-- contain duplicates; uniqueness is enforced in code on staff actions only.
ALTER TABLE property_container
    ADD COLUMN current_seal_number VARCHAR(60);

UPDATE property_container c
SET current_seal_number = (
    SELECT e.seal_number
    FROM property_event e
    WHERE e.property_container_id = c.id
      AND e.seal_number IS NOT NULL
    ORDER BY e.event_datetime DESC
    LIMIT 1
);

CREATE INDEX idx_property_container_current_seal ON property_container (current_seal_number);
