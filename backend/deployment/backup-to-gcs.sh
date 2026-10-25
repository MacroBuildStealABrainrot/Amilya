#!/bin/bash
# Existing-VM backup. Only encrypted bytes leave the VM; no object is deleted/overwritten.
set -euo pipefail
umask 077
ROOT=/mnt/stateful_partition/wcp
BUCKET=woundcopilot-20260905-db-backups
EXPECTED_PROJECT=woundcopilot-20260905
MAX_DUMP_BYTES=95000000
mkdir -p /run/wcp
exec 9>/run/wcp/backup.lock
flock -n 9 || { echo 'Another backup is already running'; exit 1; }
mountpoint -q "$ROOT/data"
test -s "$ROOT/backup-public.crt"
test -s "$ROOT/backup_capacity.py"
PROJECT=$(curl --fail --silent --show-error --max-time 10 -H 'Metadata-Flavor: Google' http://metadata.google.internal/computeMetadata/v1/project/project-id)
test "$PROJECT" = "$EXPECTED_PROJECT"
TOKEN=$(curl --fail --silent --show-error --max-time 10 -H 'Metadata-Flavor: Google' http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token | jq -er .access_token)
inventory() {
  curl --fail --silent --show-error --max-time 30 -H "Authorization: Bearer $TOKEN" \
    "https://storage.googleapis.com/storage/v1/b/$BUCKET/o?maxResults=31&fields=items(name,size),nextPageToken"
}
inventory | python3 "$ROOT/backup_capacity.py"
STAGING="$ROOT/data/.backup-staging"
mkdir -p "$STAGING"
chmod 700 "$STAGING"
DUMP="$STAGING/current.dump"
ENCRYPTED="$STAGING/current.cms"
test ! -L "$DUMP" && test ! -L "$ENCRYPTED"
cleanup() { rm -f -- "$DUMP" "$ENCRYPTED"; }
trap cleanup EXIT
# Both staging files live on the already allocated encrypted data disk, not VM RAM.
# pg_dump custom format uses gzip compression and supports pg_restore.
(
  # Bound the staging write itself, even if the database has outgrown pilot limits.
  ulimit -f 96000
  timeout 300 docker exec --user postgres wcp-postgres pg_dump -U wcp_owner \
    --format=custom --compress=gzip:6 --no-owner --no-privileges woundcopilot > "$DUMP"
)
DUMP_BYTES=$(stat -c %s "$DUMP")
test "$DUMP_BYTES" -gt 0 && test "$DUMP_BYTES" -lt "$MAX_DUMP_BYTES" || { echo 'Backup exceeds the reviewed single-dump cap'; exit 1; }
openssl cms -encrypt -binary -aes-256-gcm -in "$DUMP" -outform DER -out "$ENCRYPTED" \
  -recip "$ROOT/backup-public.crt" -keyopt rsa_padding_mode:oaep -keyopt rsa_oaep_md:sha256
BYTES=$(stat -c %s "$ENCRYPTED")
inventory | python3 "$ROOT/backup_capacity.py" "$BYTES"
NAME="woundcopilot-$(date -u +%Y%m%dT%H%M%SZ)-$(cat /proc/sys/kernel/random/uuid).dump.cms"
RESPONSE=$(curl --fail --silent --show-error --max-time 120 \
  -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary "@$ENCRYPTED" \
  "https://storage.googleapis.com/upload/storage/v1/b/$BUCKET/o?uploadType=media&name=$NAME&ifGenerationMatch=0")
unset TOKEN
test "$(printf '%s' "$RESPONSE" | jq -er .size)" = "$BYTES"
HASH=$(sha256sum "$ENCRYPTED" | cut -d ' ' -f 1)
printf 'Verified encrypted backup gs://%s/%s bytes=%s sha256=%s\n' "$BUCKET" "$NAME" "$BYTES" "$HASH"
# Deliberately no delete capability or rotating retention. At 30 objects/100MB this job
# fails closed and an operator must review retention and shared Free Tier usage.
