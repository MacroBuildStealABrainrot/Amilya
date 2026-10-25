#!/bin/bash
# Creates and removes exactly two synthetic probe databases; never drops woundcopilot.
set -euo pipefail
umask 077
SOURCE=wcp_restore_20260905_source
TARGET=wcp_restore_20260905_target
BACKUP=/run/wcp/synthetic-restore.dump
docker exec --user postgres wcp-postgres createdb -U wcp_owner "$SOURCE"
docker exec --user postgres wcp-postgres createdb -U wcp_owner "$TARGET"
docker exec --user postgres wcp-postgres psql --no-psqlrc -v ON_ERROR_STOP=1 -U wcp_owner -d "$SOURCE" -c "CREATE TABLE synthetic_restore_probe (id integer PRIMARY KEY, value text NOT NULL); INSERT INTO synthetic_restore_probe VALUES (1, 'synthetic-no-patient-data');" >/dev/null
docker exec --user postgres wcp-postgres pg_dump -U wcp_owner -Fc "$SOURCE" > "$BACKUP"
docker exec -i --user postgres wcp-postgres pg_restore -U wcp_owner --exit-on-error -d "$TARGET" < "$BACKUP"
RESTORED=$(docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d "$TARGET" -Atc "SELECT value FROM synthetic_restore_probe WHERE id = 1")
test "$RESTORED" = synthetic-no-patient-data
docker exec --user postgres wcp-postgres dropdb -U wcp_owner "$SOURCE"
docker exec --user postgres wcp-postgres dropdb -U wcp_owner "$TARGET"
sha256sum "$BACKUP"
echo 'Synthetic PostgreSQL custom-format dump and isolated restore verified; this is not an off-VM backup'
