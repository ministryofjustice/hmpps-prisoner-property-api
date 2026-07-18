-- The seal number of the container a COMBINED source was combined into, snapshotted at combine time so the
-- timeline/history names the destination by the seal it had then (not any later reseal of that container).
ALTER TABLE property_event
    ADD COLUMN related_container_seal_number VARCHAR(60);
