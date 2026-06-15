-- Disposal-related current attributes on the container (changes also recorded as events).
ALTER TABLE property_container
    ADD COLUMN proposed_disposal_date DATE,
    ADD COLUMN disposed_date          DATE;

-- The disposal date value carried by a disposal-related event, for audit.
ALTER TABLE property_event
    ADD COLUMN event_date DATE;
