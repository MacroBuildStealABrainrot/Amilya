#!/bin/bash
# Authorized schema/data backup and isolated restore; target is created by this script.
set -euo pipefail
umask 077
ROOT=/mnt/stateful_partition/wcp
mountpoint -q "$ROOT/data"
BACKUP_DIR="$ROOT/data/backups"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/woundcopilot-$(date -u +%Y%m%dT%H%M%SZ).dump"
TARGET=wcp_restore_schema_20260905
VERSION=$(docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d woundcopilot -Atc 'SELECT version_num FROM alembic_version')
test -n "$VERSION"
docker exec --user postgres wcp-postgres createdb -U wcp_owner "$TARGET"
docker exec --user postgres wcp-postgres pg_dump -U wcp_owner -Fc --no-owner --no-privileges woundcopilot > "$BACKUP"
docker exec -i --user postgres wcp-postgres pg_restore -U wcp_owner --exit-on-error -d "$TARGET" < "$BACKUP"
RESTORED_VERSION=$(docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d "$TARGET" -Atc 'SELECT version_num FROM alembic_version')
test "$VERSION" = "$RESTORED_VERSION"
SOURCE_TABLES=$(docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d woundcopilot -Atc "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")
RESTORED_TABLES=$(docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d "$TARGET" -Atc "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")
test "$SOURCE_TABLES" = "$RESTORED_TABLES"
SOURCE_USERS=$(docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d woundcopilot -Atc 'SELECT count(*) FROM users')
RESTORED_USERS=$(docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d "$TARGET" -Atc 'SELECT count(*) FROM users')
test "$SOURCE_USERS" = "$RESTORED_USERS"
docker exec --user postgres wcp-postgres dropdb -U wcp_owner "$TARGET"
sha256sum "$BACKUP"
printf 'Verified restored migration %s, matching public tables and user count %s; target removed, backup retained on data disk\n' "$VERSION" "$SOURCE_USERS"
echo 'This local retained-disk backup does not survive loss of the disk; off-VM backup remains a clinical-release gate'
