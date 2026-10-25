#!/bin/bash
# COS bootstrap. It refuses unformatted disks/missing TLS/config; never formats or deletes data.
# Operator creates the prerequisites documented in docs/DEPLOYMENT.md through IAP.
set -euo pipefail
umask 077
ROOT=/mnt/stateful_partition/wcp
DISK=/dev/disk/by-id/google-wcp-data
RUNTIME=/run/wcp
mkdir -p "$ROOT" "$RUNTIME" "$RUNTIME/docker" "$RUNTIME/secrets" "$ROOT/data"
chmod 700 "$ROOT" "$RUNTIME" "$RUNTIME/docker" "$RUNTIME/secrets"
test -b "$DISK" || { echo 'Required retained data disk is missing'; exit 1; }
test "$(blkid -o value -s TYPE "$DISK")" = ext4 || { echo 'Operator must initialize the new data disk explicitly'; exit 1; }
mountpoint -q "$ROOT/data" || mount -o nodev,nosuid "$DISK" "$ROOT/data"
for required in postgres-image.txt tls/server.crt tls/server.key postgresql.conf pg_hba.conf pg_ident.conf; do
  test -s "$ROOT/$required" || { echo 'Database configuration or TLS material is missing'; exit 1; }
done
IMAGE=$(cat "$ROOT/postgres-image.txt")
PROJECT=$(curl --fail --silent --show-error -H 'Metadata-Flavor: Google' http://metadata.google.internal/computeMetadata/v1/project/project-id)
[[ "$PROJECT" =~ ^[a-z][a-z0-9-]+$ ]] || exit 1
[[ "$IMAGE" =~ ^us-(central1|east1|west1)-docker\.pkg\.dev/$PROJECT/wcp/postgres@sha256:[a-f0-9]{64}$ ]] || { echo 'Database image must be a reviewed project-local digest'; exit 1; }
REGISTRY=${IMAGE%%/*}
TOKEN=$(curl --fail --silent --show-error -H 'Metadata-Flavor: Google' http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
test -n "$TOKEN"
printf '%s' "$TOKEN" | docker --config="$RUNTIME/docker" login -u oauth2accesstoken --password-stdin "https://$REGISTRY" >/dev/null
curl --fail --silent --show-error -H "Authorization: Bearer $TOKEN" "https://secretmanager.googleapis.com/v1/projects/$PROJECT/secrets/wcp-db-owner/versions/latest:access" |
  sed -n 's/.*"data"[[:space:]]*:[[:space:]]*"\([A-Za-z0-9+/=]*\)".*/\1/p' | base64 --decode > "$RUNTIME/secrets/owner-password"
unset TOKEN
test -s "$RUNTIME/secrets/owner-password"
chmod 400 "$RUNTIME/secrets/owner-password"
docker --config="$RUNTIME/docker" pull "$IMAGE"
# postgres:17-alpine uses UID/GID 70. Operator confirms digest/image before provisioning TLS.
test "$(docker run --rm --entrypoint id "$IMAGE" -u postgres)" = 70 || { echo 'Unexpected image user; review file ownership'; exit 1; }
chown 70:70 "$ROOT/tls" "$ROOT/tls/server.key" "$ROOT/tls/server.crt"
chmod 750 "$ROOT/tls"
chmod 600 "$ROOT/tls/server.key"
chmod 644 "$ROOT/tls/server.crt" "$ROOT/postgresql.conf" "$ROOT/pg_hba.conf" "$ROOT/pg_ident.conf"
if test -s "$ROOT/install-maintenance.sh" && test -s "$ROOT/maintenance.sh"; then
  bash "$ROOT/install-maintenance.sh"
fi
wait_ready() {
  for attempt in {1..30}; do
    if docker exec --user postgres wcp-postgres pg_isready -U wcp_owner -d woundcopilot >/dev/null 2>&1; then
      if test -s "$ROOT/install-backup.sh" && test -s "$ROOT/backup-to-gcs.sh"; then
        bash "$ROOT/install-backup.sh"
      fi
      return 0
    fi
    sleep 1
  done
  echo 'Database did not become ready within the startup check window'
  return 1
}
if docker container inspect wcp-postgres >/dev/null 2>&1; then
  test "$(docker container inspect --format '{{.Config.Image}}' wcp-postgres)" = "$IMAGE" || { echo 'Existing database container uses another image; follow reviewed upgrade procedure'; exit 1; }
  docker update --restart=on-failure:5 wcp-postgres >/dev/null
  docker start wcp-postgres >/dev/null
  wait_ready
  exit 0
fi
# on-failure does not auto-start on daemon/VM restart: the host startup must mount
# the retained disk and fetch the tmpfs secret before starting PostgreSQL.
docker run -d --name wcp-postgres --restart=on-failure:5 --memory=700m --memory-swap=700m \
  --log-driver=local --log-opt max-size=5m --log-opt max-file=2 \
  -p 10.30.0.10:5432:5432 \
  -e POSTGRES_USER=wcp_owner -e POSTGRES_DB=woundcopilot \
  -e PGDATA=/var/lib/postgresql/data/pgdata \
  -e POSTGRES_PASSWORD_FILE=/run/secrets/owner-password -e POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 \
  -v "$RUNTIME/secrets:/run/secrets:ro" \
  -v "$ROOT/data:/var/lib/postgresql/data" -v "$ROOT/tls:/etc/postgresql/tls:ro" \
  -v "$ROOT/postgresql.conf:/etc/postgresql/postgresql.conf:ro" \
  -v "$ROOT/pg_hba.conf:/etc/postgresql/pg_hba.conf:ro" \
  -v "$ROOT/pg_ident.conf:/etc/postgresql/pg_ident.conf:ro" \
  "$IMAGE" postgres -c config_file=/etc/postgresql/postgresql.conf >/dev/null
wait_ready
