-- Links a COMBINED event on a source container to the new container its property was combined into.
ALTER TABLE property_event ADD COLUMN related_container_id UUID;
