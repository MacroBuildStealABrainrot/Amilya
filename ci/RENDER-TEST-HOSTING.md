# Isolated Render test hosting — 27 October 2026

This is a separate test-hosting setup, not a production migration or a verified
fix for the Samsung photo failure. The existing Android repair APK still targets
the Google Cloud repair URL. No APK endpoint, Cloud Run traffic, existing database,
recording, billing setting, or clinical-data permission is changed here.

## Account and resource verified

- The owner confirmed **hazem's workspace**, `tea-dbgcgn59fdbs73ddfahg`.
- The workspace initially had no services or Postgres databases.
- Created only the empty **Free** Postgres instance `amilya-repair-test-db`,
  ID `dpg-dbgcjuu7bikc73fn7v30-a`, Ohio, PostgreSQL 16.
- Database name: `amilya_repair_test_db`. Public database IP allowlist is empty.
- Render reports expiration on **26 November 2026**. Free Postgres is temporary,
  has no backups, and must never contain patient records or irreplaceable work.
- No paid compute plan or billing setting was selected.

## Prepared configuration

`render.yaml` defines one Free Docker web service, `amilya-repair-test`, in Ohio.
It references the existing test database by name. Render resolves the internal
connection string and generates a new application secret without committing either
value to GitHub. The Blueprint must be applied in the same confirmed workspace.

The Docker build uses the source branch `fix/server-diagnosis-20261025`, the existing
backend Dockerfile, locked frontend install, and pinned backend requirements. This
is a **fresh source build**, not the narrow immutable-image patch used on Cloud Run.
It does not assume that private Google Artifact Registry is accessible from Render.

The Render-only startup script validates the dedicated database and Render hostname
before running migrations. It derives the web origin and trusted host from Render's
documented runtime hostname. It rejects clinical data, billing, provider execution,
test-provider output, automatic schema creation, and open signup. Secrets and database
connection strings are not printed if startup fails. Database migrations finish
before the HTTP listener starts. HTTP health checks use `/api/v1/health`.

After initial setup, `autoDeployTrigger: checksPass` makes Render wait for GitHub CI
before deploying updates. This avoids repeated Cloud Shell deployment commands.
Free web services sleep after inactivity and have no durable local upload storage;
they are unsuitable for nursing production use.

## Access limitations observed

The connected Render tools can create resources, inspect deploys/logs, and merge
environment variables. They do not expose a database-connection retrieval operation
or a Blueprint creation/sync operation. Reading the database returns metadata only.
The dashboard fallback currently shows a sign-in screen. Connector authorization
does not sign the cloud browser into the dashboard.

The Blueprint wires database secrets inside Render, avoiding a manual password copy.
Applying it still requires authenticated dashboard access and Render access to the
private Amilya repository. No service has yet been created by this preparation.

Provider API keys are not available through the GitHub connection or the existing
Google Cloud secret store. Do not invent them, publish them in Git, reuse an unrelated
key, or label a keyless startup as a successful AI/photo test. Provider activation,
an isolated test account with owner-managed legal acceptance, and synthetic end-to-end
tests remain separate steps. Do not send a real wound image for diagnostics.

## Validation

- 19 Render startup tests pass locally.
- The Blueprint passes Render's current public JSON Schema.
- Full platform-aware validation and actual deploy/health verification must be
  recorded separately; source/schema checks do not establish a running server.

Current references were retrieved directly from Render's official documentation:
`blueprint-spec`, `mcp-server`, `compute-plans`, `free`, `deploys`, `health-checks`,
`configure-environment-variables`, `environment-variables`, and `private-network`.
