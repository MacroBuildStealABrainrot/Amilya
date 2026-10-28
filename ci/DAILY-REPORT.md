# Amilya daily report

`.github/workflows/amilya-daily-report.yml` runs every morning at 11:00 UTC (07:00 Toronto in summer, 06:00 in winter). It posts one plain-English comment on the open issue labelled `daily-report`, and creates that issue the first time. Subscribe to the issue to receive each report by email. If something is serious, the owner is @mentioned. Serious means: the health check fails, live traffic leaves `woundcopilot-00021-lrx`, the live version has 5 or more server errors in 24 h, a fatal Sentry crash appears, or tests fail on `main`.

**Read-only.** It never deploys or changes traffic, billing, databases or app settings. Encounter IDs in URLs are redacted. Sections that aren't connected say "not connected", never a guess. On pull requests that touch these files it runs its own tests and writes a preview to the run summary without posting.

| Section | Source | Needs |
|---|---|---|
| Health check | public `/api/v1/health` | nothing |
| Tests, Pilot APK, open changes, approvals | GitHub API | nothing (`GITHUB_TOKEN`) |
| Live traffic and server errors | Cloud Run and Cloud Logging | one-time `ci/report_bootstrap.sh` (owner approval), then repository variables `AMILYA_REPORT_WIF_PROVIDER` and `AMILYA_REPORT_ACCOUNT` |
| Crashes | Sentry org `amilya` | repository secret `SENTRY_AUTH_TOKEN` (scopes `org:read`, `project:read`, `event:read`) |
| Deepgram audio hours and prepaid balance | Deepgram management API | repository secret `DEEPGRAM_USAGE_KEY` (a separate key with only the Member role or usage/billing read scopes, never the app's transcription key) and repository variable `DEEPGRAM_PROJECT_ID` |
| Google Cloud spending, clinical quality | not built yet | billing export approval; synthetic evaluation suite |

OpenRouter (all AI models) and Groq (transcription) offer no read-only spending access. OpenRouter's figures need a spend-capable or key-managing key, and Groq has no billing API. Neither is placed in GitHub. Use their dashboards, spend limits and low-balance alerts instead.

Open an issue with the label `needs-approval` to make a decision appear under "Waiting for you".

`ci/report_bootstrap.sh` creates a dedicated identity with only Cloud Run Viewer and Logs Viewer. Its trust is limited to repository ID 1426546615 and this workflow file on `main`. It is separate from the repair-deployment identity in `ci/REPAIR-AUTOMATION.md`.

Local preview, using your own gcloud login: `python ci/daily_report.py --dry-run`.
