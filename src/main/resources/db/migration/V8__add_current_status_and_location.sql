-- Denormalised derived state (current status and current internal location), maintained on every
-- write, so the establishment-wide list can filter and paginate by status and storage location with a
-- plain indexed query instead of loading and deriving from each container's events. The container's
-- currentStatus()/currentLocation() remain the authoritative event-derived source; these columns mirror
-- them. Status (DUE_FOR_TRANSFER_OUT etc.) is not stored, only derived, so we recompute it here.
ALTER TABLE property_container
    ADD COLUMN current_status                VARCHAR(40),
    ADD COLUMN current_internal_location_id  UUID,
    ADD COLUMN current_storage_location_type VARCHAR(20);

-- Backfill current_status with the same precedence as PropertyContainer.currentStatus():
-- a removal outcome wins, then a proposed disposal date, then the latest event (only PRISONER_RECEIVED
-- yields DUE_FOR_TRANSFER_OUT; every other event type maps to STORED), defaulting to STORED.
UPDATE property_container c
SET current_status = CASE
    WHEN c.removal_outcome = 'DISPOSED' THEN 'DISPOSED'
    WHEN c.removal_outcome = 'RETURNED' THEN 'RETURNED'
    WHEN c.removal_outcome = 'TRANSFERRED' THEN 'TRANSFER'
    WHEN c.removal_outcome = 'COMBINED' THEN 'COMBINED'
    WHEN c.proposed_disposal_date IS NOT NULL THEN 'DISPOSAL_REQUIRED'
    WHEN (
        SELECT e.event_type
        FROM property_event e
        WHERE e.property_container_id = c.id
        ORDER BY e.event_datetime DESC
        LIMIT 1
    ) = 'PRISONER_RECEIVED' THEN 'DUE_FOR_TRANSFER_OUT'
    ELSE 'STORED'
END;

-- Backfill the current location from the latest location-bearing event, mirroring currentLocationType()'s
-- fallback to INTERNAL when an internal id was recorded without an explicit type. Removed containers
-- report no location.
UPDATE property_container c
SET current_internal_location_id  = loc.to_internal_location_id,
    current_storage_location_type = COALESCE(
        loc.to_storage_location_type,
        CASE WHEN loc.to_internal_location_id IS NOT NULL THEN 'INTERNAL' END
    )
FROM (
    SELECT DISTINCT ON (e.property_container_id)
        e.property_container_id,
        e.to_internal_location_id,
        e.to_storage_location_type
    FROM property_event e
    WHERE e.to_internal_location_id IS NOT NULL
       OR e.to_storage_location_type IS NOT NULL
    ORDER BY e.property_container_id, e.event_datetime DESC
) loc
WHERE loc.property_container_id = c.id
  AND c.removal_outcome IS NULL;

ALTER TABLE property_container
    ALTER COLUMN current_status SET NOT NULL;

CREATE INDEX idx_property_container_prison_prisoner ON property_container (prison_id, prisoner_number);
CREATE INDEX idx_property_container_current_status ON property_container (current_status);
CREATE INDEX idx_property_container_current_location ON property_container (current_internal_location_id);
