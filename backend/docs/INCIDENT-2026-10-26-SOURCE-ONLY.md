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

## Confirmed provider metadata and code defect (authenticated follow-up)

After the owner completed sign-in, OpenRouter's read-only activity and request views showed:

- At **2026-10-26 06:50:20.987 UTC (02:50:20 Eastern)**, generation `gen-1792997421-ATteCDQGIQmKPfUAG9Zz` requested `openai/gpt-5.6-luna`, reported `openai/gpt-5.6-luna-20260709`, and used Azure. There were 2,426 prompt tokens and exactly 1,800 completion tokens. The finish reason was **length**, with native reason **max_output_tokens**.
- The inspected main-branch source sets the low-effort completion allowance to 1,800. Its validator rejects a length-limited completion. This is a confirmed truncation defect that matches the extraction-unavailable warning and the screenshot time. OpenRouter metadata does not contain the app request UUID, so exact linkage to that specific screen remains a time/source correlation.
- Subsequent Terra/Sol calls at 02:50–02:51 show `stop`. Transport completion is not proof that the application's clinical/schema checks passed. Prompt/completion logging is disabled and was left disabled. The final report-check failure cannot be reconstructed from metadata alone.
- The Luna upstream attempt at **Oct 25, 17:30 Eastern**, `gen-1792963836-L5XU4x4tJ4HwttLppKDg`, shows Azure transport HTTP 200 and one attempt. Opening the generation reports **No generation found with that ID**. The same occurs for the Oct 26, 03:12 Luna attempt `gen-1792998743-kU54bmppUoDlDgkm0bPp`. Neither supplies the final error body. These cannot be conclusively joined to app request `2a9bc999-5629-43c8-9bca-b5e273febe67`.
- The logs do **not** establish a privacy-policy rejection, exhausted account balance, unsupported vision model, or invalid API key as the root cause. Consultant success does not independently rule those causes out for every route.

Google Cloud Console remained unavailable in this task browser after one reload. The photo HTTP 502 and final report-check root causes therefore remain **unconfirmed**. No guessed diagnosis should be promoted to a confirmed finding.

## Development correction

The separate development branch now provides:

- Structured text extraction output allowance of 8,000–16,000 tokens, scaled by bounded source length; photo extraction gets 8,000. Low reasoning effort, zero-retention filtering, data-collection denial and schema/clinical checks remain enforced.
- A distinct `OUTPUT_LIMIT` failure. Truncated content is never admitted as facts or stored as a successful result. Failed operations waive the app charge while retaining reported provider cost. Retrying the same request and budget cannot repeatedly spend.
- Closed metadata fields for output allowance and finish reason, without prompts, transcripts, photos or arbitrary provider errors.
- Exact reviewed OpenRouter canonical snapshot aliases for the four candidate models, preventing a valid dated identifier from being incorrectly rejected by model-identity verification. No wildcard suffix acceptance or cross-model substitution.
- Android recognition and plain-language feedback for this failure; Pilot version `1.8.5-test12`.

Canonical IDs were checked against OpenRouter's public `/api/v1/models/{model}/endpoints` catalog on 26 October:

| Configured model | Reviewed reported snapshot |
| --- | --- |
| openai/gpt-6-luna | openai/gpt-6-luna-20260922 |
| openai/gpt-6.1-sol | openai/gpt-6.1-sol-20260929 |
| openai/gpt-6-astra | openai/gpt-6-astra-20260903 |
| anthropic/claude-sonnet-5.5 | anthropic/claude-sonnet-5.5-20260928 |

Catalog support does not establish successful execution under this account or approval for identifiable patient data. Live synthetic acceptance after an approved deployment is still required.

## Validation and production boundary

New regression cases cover response budgets, a completion larger than the old cap, refusal of partial results, replay without extra calls, preservation of actual provider costs, trusted snapshot identities and rejection of arbitrary metadata. Android tests cover the new category, user message and retry prohibition. CI run [40289058142](https://github.com/MacroBuildStealABrainrot/Amilya/actions/runs/40289058142) passed: 760 backend tests (one SQLite-specific skip), 151 Android app tests and 39 Wear contract tests. Local backend: 752 passed, one PostgreSQL-only skip. See [test12 validation and signing limits](../../VALIDATION-test12.md).

No production deployment, paid live AI request, administrator promotion, balance change, provider-setting change, recording deletion, prompt logging enablement or patient-data enablement occurred. The existing Pilot APK can exercise the old server, but installing an APK alone does not apply this backend correction. Production approval must name the reviewed commit and be followed by synthetic text/photo/report acceptance checks before any wider use.
