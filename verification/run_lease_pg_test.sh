#!/bin/sh
# Starts a THROW-AWAY PostgreSQL server (needs the PostgreSQL 16 server programs and root to switch to the 'postgres' user),
# runs the lease test against it, then stops and deletes the server. Nothing outside the temporary folder is touched.
# On a PC with Docker instead:  docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16
#                                python3 verification/prototype/lease_pg_test.py 127.0.0.1 54329     (needs the psql program)
set -e
PGBIN="${PGBIN:-/usr/lib/postgresql/16/bin}"; D="$(mktemp -d /var/tmp/wcp-pg-XXXXXX)"; chown postgres:postgres "$D"; chmod 755 "$D"
HERE="$(cd "$(dirname "$0")" && pwd)"
cleanup() { runuser -u postgres -- "$PGBIN/pg_ctl" -D "$D/data" -m immediate stop >/dev/null 2>&1 || true; rm -rf "$D"; }
trap cleanup EXIT
runuser -u postgres -- "$PGBIN/initdb" -D "$D/data" -A trust -U postgres >/dev/null
runuser -u postgres -- "$PGBIN/pg_ctl" -D "$D/data" -o "-p 54329 -k $D -c listen_addresses='' -c fsync=off -c max_connections=100" -l "$D/server.log" -w start >/dev/null
psql -X -h "$D" -p 54329 -U postgres -d postgres -tAc "select 'server: ' || version()" | cut -c1-80
python3 -I "$HERE/prototype/lease_pg_test.py" "$D" 54329
