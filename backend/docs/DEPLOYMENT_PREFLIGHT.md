# Read-only account preflight — 2026-09-05

This is the initial pre-provisioning snapshot. The owner later approved billing linkage and bounded Free Tier resources in the new project; subsequent status is in `CLOUD_EXECUTION.md`.

The authorized Google Cloud CLI inventory ran using the owner's existing authenticated configuration. It did not enable APIs, link billing, retrieve secret payloads, upload images, or create resources. Detailed account metadata remains in the ignored `deployment/preflight.local.json`.

Observed results:

| Check | Result | Meaning |
|---|---|---|
| New project `woundcopilot-20260905` | Active, billing disabled, no billing account linked | Resource deployment has not been enabled through this plan. |
| Selected billing account's project list | Exactly one existing linked project | Free allowances must be assessed together with that existing project. |
| Existing project's enabled APIs | `generativelanguage.googleapis.com`, `telemetry.googleapis.com` | No enabled Compute, Cloud Run, Artifact Registry, Secret Manager or Cloud Build API was returned. |
| Compute/registry/Run/secrets/build resource listings | Unavailable; direct Compute and Registry diagnostics confirm `SERVICE_DISABLED` | This is evidence of disabled services, not proof of zero historical consumption or no retained storage. No API was auto-enabled. |
| Storage buckets in the existing and new project | Empty lists | No bucket was returned within the caller's inventory scope. |

The evidence is consistent with an existing account used for the Gemini API and no currently enabled infrastructure services in its linked project. This is an inference, not a verified statement that every monthly Free Tier allowance is unused. Disabled services can prevent inventory access, and monthly usage can precede the current resource state.

The next concrete operational step, if the owner proceeds under the normal Free Tier conditions, is to link the new project to the selected billing account, enable only the reviewed API list **in the new project**, then verify its empty inventories and quotas before creating the eligible resources. Account-wide historical usage, retained image storage and secret versions still need reconciliation in Billing Reports or an expressly authorized inventory of the existing project's disabled services. Enabling an API is not included in the read-only preflight.

The no-external-IPv4 e2-micro and 30 GB standard-disk plan is in `deployment/plan.json`; it deliberately omits a billing-link command. The application and PostgreSQL image digests/storage, TLS provisioning, backup restore, database migrations and final deployment checks remain prerequisites. This preflight is not a deployment success report or a guarantee of a zero invoice.
