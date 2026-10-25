#!/bin/bash
# Install only named operator-supplied files. No credentials are printed.
set -euo pipefail
umask 077
ROOT=/mnt/stateful_partition/wcp
INBOX=/tmp/wcp-inbox
mkdir -p "$ROOT/tls" /run/wcp
chmod 700 "$ROOT" /run/wcp
for file in postgresql.conf pg_hba.conf pg_ident.conf postgres-startup.sh; do
  install -o root -g root -m 600 "$INBOX/$file" "$ROOT/$file"
done
install -o root -g root -m 600 "$INBOX/server.key" "$ROOT/tls/server.key"
install -o root -g root -m 644 "$INBOX/server.crt" "$ROOT/tls/server.crt"
install -o root -g root -m 600 "$INBOX/app-password.txt" /run/wcp/app-password
# Exact file removal after installation, never recursive or computed from input.
rm -- "$INBOX/server.key" "$INBOX/app-password.txt"
echo 'Database configuration and TLS material staged; password content omitted'
