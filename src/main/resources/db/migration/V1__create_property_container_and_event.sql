CREATE TABLE property_container
(
    id                 UUID         NOT NULL PRIMARY KEY,
    prisoner_number    VARCHAR(10)  NOT NULL,
    prison_id          VARCHAR(6)   NOT NULL,
    container_type     VARCHAR(20)  NOT NULL,
    create_datetime    TIMESTAMP    NOT NULL,
    created_by_user_id VARCHAR(32)  NOT NULL
);

CREATE INDEX idx_property_container_prisoner_number ON property_container (prisoner_number);

CREATE TABLE property_event
(
    id                        UUID         NOT NULL PRIMARY KEY,
    property_container_id     UUID         NOT NULL REFERENCES property_container (id),
    event_type                VARCHAR(30)  NOT NULL,
    seal_number               VARCHAR(60),
    event_datetime            TIMESTAMP    NOT NULL,
    from_internal_location_id UUID,
    to_internal_location_id   UUID,
    from_prison_id            VARCHAR(6),
    to_prison_id              VARCHAR(6),
    event_user_id             VARCHAR(32)  NOT NULL
);

CREATE INDEX idx_property_event_container ON property_event (property_container_id);
