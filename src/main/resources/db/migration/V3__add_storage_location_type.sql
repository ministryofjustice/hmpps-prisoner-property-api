-- Marks the storage location type of a location-bearing event - BRANSTON for the offsite warehouse
-- (which has no internal location id); INTERNAL is implied by to_internal_location_id.
ALTER TABLE property_event
    ADD COLUMN to_storage_location_type VARCHAR(20);
