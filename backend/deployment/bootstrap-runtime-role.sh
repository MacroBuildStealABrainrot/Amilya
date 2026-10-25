#!/bin/bash
# Host-local administrator action. Reads the generated password only from tmpfs.
set -euo pipefail
umask 077
PASSWORD=$(cat /run/wcp/app-password)
[[ "$PASSWORD" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo 'Unexpected generated password format'; exit 1; }
if ! {
  printf '%s\n' "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'wcp_app') THEN CREATE ROLE wcp_app; END IF; END \$\$;"
  printf "ALTER ROLE wcp_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '%s';\n" "$PASSWORD"
  printf '%s\n' 'GRANT CONNECT ON DATABASE woundcopilot TO wcp_app;' 'GRANT USAGE ON SCHEMA public TO wcp_app;'
  printf '%s\n' 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO wcp_app;'
  printf '%s\n' 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO wcp_app;'
  printf '%s\n' 'ALTER DEFAULT PRIVILEGES FOR ROLE wcp_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO wcp_app;'
  printf '%s\n' 'ALTER DEFAULT PRIVILEGES FOR ROLE wcp_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO wcp_app;'
  # Existing append-only tables retain the same protections when this role setup is rerun.
  # Run harden_runtime.py after initial migrations create these tables.
  for TABLE in clinical_evidence fact_revisions clinical_documents document_signatures clinical_reviews clinical_timeline audit_events credit_ledger; do
    printf "DO \$\$ BEGIN IF to_regclass('public.%s') IS NOT NULL THEN REVOKE UPDATE, DELETE ON public.%s FROM wcp_app; END IF; END \$\$;\n" "$TABLE" "$TABLE"
  done
} | docker exec -i --user postgres wcp-postgres psql --no-psqlrc -v ON_ERROR_STOP=1 -U wcp_owner -d woundcopilot --quiet > /run/wcp/role-setup.log 2>&1; then
  echo 'Runtime role setup failed; inspect root-only /run/wcp/role-setup.log without publishing credentials'
  exit 1
fi
unset PASSWORD
echo 'Runtime role and migration default privileges configured; credentials omitted'
docker exec --user postgres wcp-postgres psql --no-psqlrc -U wcp_owner -d woundcopilot -Atc 'SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = $$wcp_app$$'
