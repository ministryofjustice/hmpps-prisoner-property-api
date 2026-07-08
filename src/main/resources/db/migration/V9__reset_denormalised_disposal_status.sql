-- Disposal is now time-based: the denormalised current_status holds the container's base status and
-- DISPOSAL_REQUIRED is overlaid at read time from proposed_disposal_date vs today. Existing rows stored
-- DISPOSAL_REQUIRED whenever a disposal date was set (regardless of whether it had arisen), so reset them
-- to the base STORED. These are all active containers (a removal outcome overrides the status); a rare
-- transfer-out-and-disposal container is corrected to DUE_FOR_TRANSFER_OUT on its next write.
UPDATE property_container
SET current_status = 'STORED'
WHERE current_status = 'DISPOSAL_REQUIRED';
