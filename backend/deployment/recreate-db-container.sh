#!/bin/bash
# Explicit operator maintenance window. Removes only the named container object.
# Database files stay on the already-mounted retained disk.
set -euo pipefail
ROOT=/mnt/stateful_partition/wcp
mountpoint -q "$ROOT/data"
test -s "$ROOT/data/pgdata/PG_VERSION"
EXPECTED=$(cat "$ROOT/postgres-image.txt")
test "$(docker container inspect --format '{{.Config.Image}}' wcp-postgres)" = "$EXPECTED"
test "$(docker container inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Source}}{{end}}{{end}}' wcp-postgres)" = "$ROOT/data"
docker stop --time=30 wcp-postgres >/dev/null
docker container rm wcp-postgres >/dev/null
bash "$ROOT/postgres-startup.sh"
echo 'Database container recreated with retained files and reviewed image; startup readiness still requires verification'
