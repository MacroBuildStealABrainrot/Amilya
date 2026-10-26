# Amilya pasted-conversation failure — 26 October 2026

## Confirmed observations

The user supplied three screenshots of a synthetic pasted conversation. The app reports that AI extraction is unavailable, then opens a source-only recovered draft containing the conversation. The on-screen warning explicitly states that AI generation or checking did not complete. This is not evidence of successful clinical report generation. The screenshots show 2:50–2:51 but do not establish AM/PM, an exact request ID, or the provider response.

Read-only checks against the existing Cloud Run service on 26 October found:

- Public `/api/v1/config`: AI, photo, document and audio capabilities enabled. Identifiable clinical data remains disabled. Capability flags establish configuration presence only, not provider authentication, funding, model availability or successful inference.
- The signed-in app account's Plans & credits page displays unlimited credits and no subscription requirement. No balance or subscription was changed. This does not establish the AI provider account balance.
- `/api/v1/admin/diagnostics` returns `Unknown API route.` The diagnostic endpoint implemented in the development candidate is not exposed by the live service.
- `/api/v1/admin/providers` returns `Owner access is required.` The current authenticated account cannot inspect provider configuration through this route. No access controls were bypassed or changed.
- Google Cloud Console again returned Site Unavailable in the task browser.
- The connected PostHog organization is Amilya, project 688485. Project metadata reports no ingested event. A read-only error query over the preceding 48 hours, including test accounts and all statuses, returned no issues: https://us.posthog.com/project/688485/error-tracking
- Sentry's installed read-only skill requires SENTRY_AUTH_TOKEN; that credential is not configured in this execution environment. No token or DSN was printed or changed.

## Source trace

At candidate `a939c7fcf64658e9de96cf2635a99a77dd0eda00`, Android `AppState.draftQuick` catches HTTP 402, 429, 502, 503 and 504, requests a source-only projection with `use_ai=false`, and adds the exact recovery warning shown in the screenshot. It intentionally preserves the source instead of inventing a report.

The backend also has a source-only recovery path in `quick_workflows.quick_report`. Multiple failure classes reach these paths. Consequently, the recovered text alone cannot identify an authentication, quota, model, timeout or schema failure. The live HTTP status and deployed revision were not established.

## Clarification: the sole app login and administrator access

The user confirms this is the only app login and nobody else has used the app. The previous suggestion that a separate owner login existed was unsupported and is withdrawn.

The repository distinguishes workspace ownership from platform administration:

- `identity.signup` creates a membership with `role='owner'` but explicitly creates the user with `is_platform_admin=False`.
- `identity.require_admin` checks only `user.is_platform_admin` and returns the misleadingly broad message `Owner access is required.` if it is false.
- `billing.has_unlimited_credits` can exempt an explicitly configured user ID without granting platform privileges.
- `identity.bootstrap_owner` creates a new platform administrator but deliberately refuses to promote an existing account. It must not be used to create a replacement account or bypass the existing account's controls.
- The declared `admin_email` setting is not referenced by the inspected application code. Changing that setting alone is not an established remedy.

These source facts explain how the sole workspace owner can encounter the observed diagnostic-access denial. They do not establish how this production account was originally provisioned, the full live account inventory, or the cause of the AI failure.

Continue diagnosis through authorized read-only access to the existing Google Cloud project and request logs. No second app login is required as a diagnostic assumption. Any correction to the existing account's production administrator flag requires a reviewed, explicitly approved production change; the denial is not permission to bypass it.

## Status and next access requirement

The pasted source was preserved, but the AI workflow failed. The underlying provider/server cause remains **unconfirmed**. The absence of the newer diagnostic endpoint establishes a live/development capability difference; it does not identify the deployed revision or prove that a particular code change will resolve this request.

Owner-authorized read access to provider settings and request logs is the remaining blocker. Do not promote the current app account, change balances, relax privacy controls, trigger paid live requests or deploy merely to diagnose this failure. After obtaining evidence, reproduce the confirmed failure with synthetic data, correct it on a development branch, and seek explicit production approval for the reviewed change.

No new APK, application-code change, deployment, provider request, recording deletion, patient-data enablement or billing change was performed during this follow-up. Earlier automated build success is not live AI-service acceptance.
