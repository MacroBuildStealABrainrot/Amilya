#!/bin/bash
# Expire only transient provider replay/session/rate records, in bounded batches.
# No patients, encounters, facts, documents, audits, payment or credit records are deleted.
set -euo pipefail
docker exec -i --user postgres wcp-postgres psql --no-psqlrc -v ON_ERROR_STOP=1 -U wcp_owner -d woundcopilot <<'SQL'
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '10s';
WITH expired AS (SELECT id FROM provider_results WHERE created_at < now() - interval '24 hours' ORDER BY created_at LIMIT 1000)
DELETE FROM provider_results WHERE id IN (SELECT id FROM expired);
WITH expired AS (SELECT id FROM login_sessions WHERE expires_at < now() ORDER BY expires_at LIMIT 1000)
DELETE FROM login_sessions WHERE id IN (SELECT id FROM expired);
WITH expired AS (SELECT key FROM rate_windows WHERE expires < extract(epoch FROM now())::bigint ORDER BY expires LIMIT 1000)
DELETE FROM rate_windows WHERE key IN (SELECT key FROM expired);
COMMIT;
SQL
