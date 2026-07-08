-- Snapshot the container type onto each event so the history is a self-contained, audit-durable record
-- (it may be the only surviving detail of a container once a person is released). Backfill existing rows
-- from the owning container's current type, then enforce NOT NULL.
ALTER TABLE property_event
    ADD COLUMN container_type VARCHAR(20);

UPDATE property_event e
SET container_type = c.container_type
FROM property_container c
WHERE e.property_container_id = c.id;

ALTER TABLE property_event
    ALTER COLUMN container_type SET NOT NULL;
