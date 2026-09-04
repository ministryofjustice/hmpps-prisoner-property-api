-- prisoner_number is reassignable.
--
-- NOMIS merges two prisoner numbers when the same person is held under both; the oldest survives and the
-- newer is deleted outright. On prison-offender-events.prisoner.merged this service moves every container
-- from the retired number to the survivor, so a row's prisoner_number is not fixed for the life of the row.
-- Nothing else ever changes it.
--
-- The retired number is not stored anywhere: it appears only as removedNomsNumber on the
-- prison-property.container.updated event raised for each container moved. Anything that needs the history
-- of which numbers a container has been filed under has to reconstruct it from the domain-events archive.
-- See V15__schema_comments.sql for the conventions; a later migration may replace any comment.

COMMENT ON COLUMN property_container.prisoner_number IS 'NOMIS offender number (noms id) of the prisoner whose property this is. The link that makes every row here personal data about that prisoner. Reassigned when NOMIS merges two prisoner numbers for the same person: the containers move to the surviving number and the retired one survives only as removedNomsNumber on the resulting domain event. [Sensitivity: PERSONAL]';
