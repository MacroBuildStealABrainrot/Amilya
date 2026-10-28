# Isolated Render test hosting — verified handoff

## Account-setup correction — 28 October 2026

Render Test14 initially could not sign in because this separate database had no
app account and registration was fully closed. The server now offers one-use,
username-restricted enrollment; no new APK is needed.

- Code: `ab98b566a928b14c111b2e680214da1a8cb10521`.
- CI 40769822128 passed: backend 889 passed, 1 skipped; Android and safety checks
  passed. All Google Cloud authentication/deploy steps skipped.
- Live deploy: `dep-dbh6escfibcs73bnodo0`.
- Registration expires 29 October, 16:21 EDT, or closes on the first user.
- Live GET config: HTTP 200, `allow_signup=true`, `signup_scope=owner_test`.
- A different username was rejected with HTTP 403. The configured username with
  `accept_terms=false` was rejected with HTTP 422. Neither issued a session.
  Enrollment remained open afterwards, confirming these checks created no user.
- AI, billing and clinical-data processing remained false.
- The owner chooses the password and accepts policies inside the existing app.
  No credential is copied from Google Cloud, and no acceptance is fabricated.
- See [restricted account-enrollment details](RENDER-OWNER-ENROLLMENT.md).


This is a separate synthetic test environment. Production Cloud Run, existing
apps, recordings, billing and clinical-data permissions were preserved.

## Current resources

- Owner-confirmed workspace: hazem's workspace, `tea-dbgcgn59fdbs73ddfahg`.
- GitHub installation is restricted to Amilya only.
- Blueprint: `exs-dbgh6kbl550s73b5jnjg`.
- Web service: `srv-dbgh73o8v5cc73ccufk0`, Free, Ohio.
- URL: https://amilya-repair-test.onrender.com.
- Live backend code last verified: `ab98b566a928b14c111b2e680214da1a8cb10521`.
- Latest settings deploy: `dep-dbh6escfibcs73bnodo0`, LIVE.
- Auto-deploy waits for GitHub checks to pass. Root directory: `backend`.
- Database: `amilya-repair-test-db`, Free PostgreSQL 16, Ohio,
  `dpg-dbgcjuu7bikc73fn7v30-a`, database `amilya_repair_test_db`.
  Public database IP allowlist remains empty. Render resolves the internal
  connection string through Blueprint references.
- The free database expires 26 November 2026 and has no backups. The free web
  service sleeps after inactivity and has ephemeral local storage. Neither is
  prepared for nursing production or irreplaceable records.

Public AI, audio, billing and clinical-data processing remain disabled.
General signup stays closed; the bounded owner enrollment above is the only exception.
No actual owner account, login session or legal acceptance was created by the agent.
The diagnostic trigger was cleared after the fixed-input checks.
Dashboard-owned `MODEL_EXTRACTION` selects `openai/gpt-6.1-sol`.

## Approved provider key

The owner approved `Amilya Render synthetic test`: US$1 total cap, no reset,
expiry one day after creation (28 October, 20:52 EDT). Secure email verification
succeeded; the key was transferred directly to Render. Its value was not placed
in source, chat, artifacts or these notes. OpenRouter reported US$0.023 used
after the tests. No purchase, recurring payment or production key change occurred.

`OPENROUTER_API_KEY`, `MODEL_EXTRACTION` and the diagnostic opt-in are managed
in Render settings and omitted from the Blueprint. Blueprint syncs preserve
omitted environment variables. Do not commit their secret values.

## Actual provider integration results

| Check | Actual result |
| --- | --- |
| Nonclinical geometric image, Sol | PASS: insufficient quality, retake required, no invented wound findings |
| Fictional visit report, Sol | PASS including the complete report QA pipeline |
| Fictional text extraction, Luna | HTTP 502; precise cause remains unproven |
| Same fictional text extraction, Sol | PASS in a separate once-only check |

No patient photo or record was sent for these checks. A non-wound photo pass
does not establish sufficient-quality wound assessment or Samsung UI behavior.
No validation rule was relaxed and no unsupported output was accepted.

Private checks run in a bounded child process after validating the dedicated
Render context and database. They expose no HTTP or upload entry point and use
only built-in shapes and fictional text through the real gateway and validators.
The web process remains provider-disabled. A unique durable marker claims each
probe version before a request, preventing restart repeats. Temporary SQLite
contains only synthetic fixture users/accounting; no paid subscription, login
session or legal acceptance is fabricated. Only fixed technical result codes
reach deployment logs. The child has a 240-second overall bound.

First probe code: `bb15ff4d3872c2d0c108ea91213dd165730fd7ea`,
deploy `dep-dbgklm142hec73br8llg`, CI 40612219037 (871 backend tests passed).
Sol extraction check: code `2e9ce76745d99017b11f7eb00e48d1536fa9f6f2`,
deploy `dep-dbgkseou01pc73advje0`, CI 40613486277 (874 backend tests passed).
The first deployment of the extraction check reused the old selection and
skipped as already claimed; the corrected explicit Render setting ran and passed.
The opt-in was then cleared and the tested Sol extraction selection saved.

## Android candidate now prepared

[Render Android Test14 handoff](RENDER-ANDROID-TEST14.md) records the separate APK,
signature, checksums and exact endpoint checks.

- File: `Amilya-Render-Test14.apk`.
- Source: `b5c33932c062e9cad75f7ecb88eab2aea92492c8`.
- Full CI: 40618434623 passed Android and backend checks (874 passed, 1 skipped).
- Label: Amilya Render Test.
- Package: `com.woundcopilot.android.rendertest`.
- API: `https://amilya-repair-test.onrender.com/api/v1`.
- It installs beside existing apps and retains the original recorder. Crash-safe
  recorder service and Wear remote control are disabled in this candidate.
- It contains no provider key or app credential. No phone installation occurred.
- SHA-256: `a37a8cb36047a1062e8446b25ba84b5b1d4c0a7ed009bf0773d605dccbe5fd82`.

The existing Repair Test13 APK still targets the Google Cloud repair URL.
Test14 is a connection candidate, not an in-place upgrade, production-final
release or certification that the original Samsung photo failure is fixed.

## Remaining gates

1. Prepare an isolated test account and obtain the owner's actual acceptance of
   the app's current policies. Signup is closed; no policy acceptance was forged.
   The served policies are marked drafts requiring review before commercial use.
2. Configure bounded, authenticated, synthetic-only provider access. The current
   launcher deliberately rejects public provider execution; the private probe
   did not remove that gate. Provider funding does not create app entitlement.
3. Test authenticated upload, extraction and report routes, then the phone's
   complete flow with synthetic data. Transcription still lacks a provider key.
4. Resolve production signing continuity and physical Samsung validation.
5. Any production migration requires separate authorization, durable storage,
   privacy/provider review and verified rollback. No normal Cloud Run traffic
   change, accidental MySQL deletion or clinical-data enable was performed.

## Hosting history and constraints

The initial deploy failed because migrations assumed the Cloud Run `wcp_app`
role. The launcher now supplies its validated Render-managed database role via
`WCP_DB_RUNTIME_ROLE`; the migration defaults remain unchanged for existing
deployments. CI upgraded a fresh PostgreSQL database through revision 0008 and
verified function permissions. Hosting HTTP 200 was observed for health, config,
frontend and assets; LIVE and application startup were verified after settings.

The startup script checks the dedicated database/hostname before migrations,
derives the exact HTTPS origin and trusted hosts, and rejects clinical data,
billing, test-provider output, auto-created schema, open signup and public AI.
It never prints secret connection strings on startup failure.

This is a fresh source Docker build, not the immutable-image Cloud Run patch.
No dependency-identical-to-production claim is made. The Cloud Run deployment
job only reports missing connection readiness; authentication and deploy steps
skipped in the recorded CI runs. No Google Cloud credential was copied.

Current official Render configuration, Blueprint, private-network, PostgreSQL,
free-plan, health-check and deployment documentation was retrieved during setup.
