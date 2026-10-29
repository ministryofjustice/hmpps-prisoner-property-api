-- When a person last changed each container (MAPB-989). The "Last updated" column on the property screens showed
-- create_datetime, so it only moved when a new record was made - adding, combining - and never for a move, a
-- reseal or a transfer.
--
-- Changes the service makes by itself do not count, by decision of the product team: flagging property due for
-- return or transfer when its owner moves (event_user_id PRISONER_PROPERTY_API) and legacy clean-up closures
-- (LEGACY_CLEANUP). Staff read the date as "someone has looked at this", so an automatic status change moving it
-- would suggest the record had already been dealt with. Prisoner-number merges write no event and so are left out
-- too. Changes made in NOMIS, synced across, do count.
--
-- Back-filled from the latest event a person wrote, which every container has (its CREATED_SEALED), never earlier
-- than create_datetime. A few past changes wrote no event - a cleared disposal date - and are not recoverable, so
-- for those containers the date is the last event before them. From here on the service records those too.

ALTER TABLE property_container ADD COLUMN last_updated_datetime TIMESTAMP;

UPDATE property_container c
SET last_updated_datetime = GREATEST(c.create_datetime, e.latest)
FROM (
    SELECT property_container_id, MAX(event_datetime) AS latest
    FROM property_event
    WHERE event_user_id NOT IN ('PRISONER_PROPERTY_API', 'LEGACY_CLEANUP')
    GROUP BY property_container_id
) e
WHERE e.property_container_id = c.id;

UPDATE property_container
SET last_updated_datetime = create_datetime
WHERE last_updated_datetime IS NULL;

ALTER TABLE property_container ALTER COLUMN last_updated_datetime SET NOT NULL;

COMMENT ON COLUMN property_container.last_updated_datetime IS 'When a person last changed the container, in this service or in NOMIS; starts at create_datetime and only moves forward. Changes the service makes by itself (flagging property due for return or transfer when its owner moves, legacy clean-up closures, prisoner-number merges) do not move it. Shown to staff as "Last updated". [Example: 2026-03-02T14:30:00] [SAR: N] [Sensitivity: NONE]';
