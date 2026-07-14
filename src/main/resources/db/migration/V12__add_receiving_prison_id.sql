-- Denormalised "receiving prison" for a container that is due to transfer out: the establishment its
-- owner has moved to, and therefore the prison it is due to be transferred *in* to. Mirrors the latest
-- PRISONER_RECEIVED event's to_prison_id and is null for any container not currently due for transfer
-- out. Maintained on every write via PropertyContainer.refreshDerivedState(), the same as current_status.
-- It lets the establishment-wide list surface incoming property (held elsewhere, destined here) with a
-- plain indexed query instead of loading each container's events.
ALTER TABLE property_container
    ADD COLUMN receiving_prison_id VARCHAR(6);

-- Backfill from the latest PRISONER_RECEIVED event for containers currently due for transfer out (active
-- and holding that base status). Every other container leaves receiving_prison_id null.
UPDATE property_container c
SET receiving_prison_id = (
    SELECT e.to_prison_id
    FROM property_event e
    WHERE e.property_container_id = c.id
      AND e.event_type = 'PRISONER_RECEIVED'
    ORDER BY e.event_datetime DESC
    LIMIT 1
)
WHERE c.removal_outcome IS NULL
  AND c.current_status = 'DUE_FOR_TRANSFER_OUT';

CREATE INDEX idx_property_container_receiving_prison ON property_container (receiving_prison_id);
