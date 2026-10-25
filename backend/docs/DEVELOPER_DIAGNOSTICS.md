# Developer diagnostics

This incremental implementation requires Alembic revision 0008. It adds `diagnostic_requests` and `diagnostic_events`; it does not change clinician profession storage or clinical-document tables. Use the existing environment migration process before deploying the updated API.

Authenticated clients POST allowlisted device observations to `/api/v1/diagnostics/events`. Only platform administrators can GET `/api/v1/admin/diagnostics` and `/api/v1/admin/diagnostics/{request_id}`. Device observations are not trusted as server verification. All clinical routes continue using their existing authorization.

Each provider attempt records the actual transport-selected model, not a model inferred from the task. Generation, source QA, repair, replay and speech fallback remain separate events. Missing tokens/cost are unknown. Billing-normalized cost is distinct from provider-reported charge. Never add raw exception strings, response bodies, prompts, source text, file paths or patient identifiers to this schema.

Client and server durations overlap: the generation round trip includes network, server orchestration and provider work. Do not sum it with the provider generation duration. Opaque job and request IDs connect the device timeline to the server attempts. Device timestamps can differ from server time; monotonic durations are local to each measuring process.

Metadata retention is 30 days with activity-driven pruning. Device outbox is seven days and at most 1,000 events. A diagnostic storage outage can create gaps; server write failures are exposed as a process health counter. Clinical processing is not failed merely because a diagnostic write failed. This is not an immutable audit archive.

Android test artifacts use the explicit `-PwcpAllowScreenCapture=true` setting. It defaults to false. Keep this test-only exemption out of a production delivery that requires PHI screen protection. Normal signed release variants are not Android DEBUG builds.

Documentation layers are owned by the application under `server/prompts/documentation`, selected through `server/documentation_layers.py`. Clinician role comes from the existing profile profession; report format is independent. Clinical source content stays in the existing untrusted data message. Update prompt manifest hashes when changing a layer.

Validation: `pytest tests/test_developer_diagnostics.py tests/test_documentation_layers.py`. These tests intercept all provider requests and use synthetic information. They verify technical/prompt contracts, not live model quality. Apply the migration against a staging PostgreSQL environment and compare repeated synthetic provider runs before making clinical or performance claims.
