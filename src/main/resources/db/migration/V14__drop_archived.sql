-- The `archived` flag is retired in favour of the reversible REMOVED removal outcome. It was never set in
-- production (always false), so dropping it changes no visible data.
ALTER TABLE property_container
    DROP COLUMN archived;
