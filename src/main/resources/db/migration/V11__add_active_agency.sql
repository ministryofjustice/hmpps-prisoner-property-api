-- Which agencies (prisons) have the property service switched on. Exposed under /info as
-- "activeAgencies" to gate the DPS home tile and (later) DPS-vs-NOMIS behaviour. A stable
-- boolean row per prison keeps deactivation auditable and the toggle idempotent.
CREATE TABLE active_agency (
  agency_id  VARCHAR(6)  NOT NULL PRIMARY KEY,
  active     BOOLEAN     NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP   NOT NULL,
  updated_by VARCHAR(64) NOT NULL
);
