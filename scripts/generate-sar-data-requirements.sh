#!/usr/bin/env bash
#
# Exports the SAR Data Requirements extract that the Offender SAR Team review.
#
# This is the document behind the data review checkpoint of the Central SAR Change Control Process
# (MAPB-767/MAPB-768). It is generated rather than hand-written for the same reason the data dictionary
# is: the schema comments are the one source of truth, so the extract cannot drift from the schema.
#
# Columns are those the Offender SAR Team ask for:
#
#   Product Name, Type of Change, Entity, Element, Description, Example Value, Mandatory, SAR Impact, Impact
#
# SAR Impact is derived from the [Sensitivity: ...] tag on each column comment:
#
#   PERSONAL, SPECIAL-CATEGORY  -> Y   personal data about the prisoner
#   STAFF                       -> N   personal data, but about a member of staff, so out of scope of a
#                                      prisoner's request (it remains in scope of that staff member's own)
#   NONE, OFFICIAL-SENSITIVE    -> N   not personal data in itself
#
# Read that column carefully before quoting it. It describes whether the column's *own content* is
# personal data about the prisoner, not whether the column appears in a report. Every row in
# property_container and property_event belongs to a prisoner through property_container.prisoner_number,
# so the whole record is that prisoner's personal data and is disclosed, whatever an individual column is
# marked. V15__schema_comments.sql makes the same point at greater length.
#
# Impact is NO CHANGE throughout, which is correct for a new product - nothing existed before to be
# added to, changed or deleted. The column is kept so the same script serves the Enhancement and
# Maintenance reviews later, where it will need to be set per column.
#
# Usage:
#   scripts/generate-sar-data-requirements.sh [output-file]
#
# Expects a database built by Flyway. Connection details are taken from the environment, defaulting
# to the container in docker-compose-schema-spy.yml:
#   DB_HOST (localhost) DB_PORT (5432) DB_NAME (prisoner_property) DB_USER (prisoner_property)
#   DB_PASSWORD (prisoner_property) DB_SCHEMA (public)
#
# PRODUCT_NAME and CHANGE_TYPE can be overridden for a later change of a different type.

set -euo pipefail

OUTPUT="${1:-sar-data-requirements.csv}"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-prisoner_property}"
DB_USER="${DB_USER:-prisoner_property}"
DB_PASSWORD="${DB_PASSWORD:-prisoner_property}"
DB_SCHEMA="${DB_SCHEMA:-public}"

PRODUCT_NAME="${PRODUCT_NAME:-Prisoner Property}"
CHANGE_TYPE="${CHANGE_TYPE:-NEW PRODUCT}"
IMPACT="${IMPACT:-NO CHANGE}"

export PGPASSWORD="$DB_PASSWORD"

read -r -d '' QUERY <<SQL || true
SELECT
  '${PRODUCT_NAME}'                                      AS "Product Name",
  '${CHANGE_TYPE}'                                       AS "Type of Change",
  c.table_name                                           AS "Entity",
  c.column_name                                          AS "Element",
  regexp_replace(
    regexp_replace(
      col_description(pc.oid, c.ordinal_position),
      '\s*\[Sensitivity: [A-Z-]+\]$', ''
    ),
    '\s*\[Example: [^\]]*\]$', ''
  )                                                      AS "Description",
  substring(
    col_description(pc.oid, c.ordinal_position)
    from '\[Example: ([^\]]*)\]'
  )                                                      AS "Example Value",
  CASE WHEN c.is_nullable = 'NO' THEN 'Y' ELSE 'N' END   AS "Mandatory",
  CASE
    WHEN col_description(pc.oid, c.ordinal_position)
           ~ '\[Sensitivity: (PERSONAL|SPECIAL-CATEGORY)\]$' THEN 'Y'
    ELSE 'N'
  END                                                    AS "SAR Impact",
  '${IMPACT}'                                            AS "Impact"
FROM information_schema.columns c
JOIN pg_class pc
  ON pc.relname = c.table_name
 AND pc.relnamespace = '${DB_SCHEMA}'::regnamespace
 AND pc.relkind = 'r'
WHERE c.table_schema = '${DB_SCHEMA}'
  AND c.table_name <> 'flyway_schema_history'
ORDER BY c.table_name, c.ordinal_position
SQL

COPY_COMMAND="COPY ($QUERY) TO STDOUT WITH (FORMAT csv, HEADER true)"

if command -v psql > /dev/null 2>&1; then
  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -c "$COPY_COMMAND" > "$OUTPUT"
else
  # No local client - use the postgres image. host.docker.internal resolves on Docker Desktop,
  # and --add-host makes it resolve on Linux too.
  docker run --rm --add-host=host.docker.internal:host-gateway \
    -e PGPASSWORD="$DB_PASSWORD" postgres:18 \
    psql -h "$([ "$DB_HOST" = "localhost" ] && echo host.docker.internal || echo "$DB_HOST")" \
    -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -c "$COPY_COMMAND" > "$OUTPUT"
fi

echo "Wrote $(($(wc -l < "$OUTPUT") - 1)) elements to $OUTPUT"
