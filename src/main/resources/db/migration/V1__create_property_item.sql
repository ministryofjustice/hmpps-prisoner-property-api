CREATE TABLE property_item
(
    id              UUID         NOT NULL PRIMARY KEY,
    prisoner_number VARCHAR(10)  NOT NULL,
    description     VARCHAR(240) NOT NULL,
    location        VARCHAR(40)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_property_item_prisoner_number ON property_item (prisoner_number);
