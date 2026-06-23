-- Archived containers map NOMIS OFFENDER_PPTY_CONTAINERS.ACTIVE_FLAG = 'N'. Archived records are
-- hidden from normal reads (only surfaced when fetched explicitly by id, e.g. a future archive screen).
ALTER TABLE property_container
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
