# Bounded owner synthetic AI test

The Render APK intentionally keeps the same interface. Moving hosting does not
repair photo assessment by itself. Registration created a separate account, but
the public server still had AI disabled and no account entitlement.

This change supports the previously approved synthetic-only allowance without
turning on general signup, clinical-data processing or paid subscriptions. No new
APK is needed. `AMILYA_RENDER_OWNER_TEST_UNTIL` is an opt-in Unix deadline; the
launcher still refuses `PROVIDER_EXECUTION_ENABLED=true` as an input setting.
After the isolated database migrations, it reads only the sole active nonadmin
owner's immutable ID, owner membership in that user's workspace, and existing
current-policy acceptance. It neither creates nor modifies acceptance/accounts.

The already stored OpenRouter key must identify itself via `/api/v1/key` as a
non-management key, with a positive remaining balance, total cap at most US$1,
no recurring reset, and expiry before the existing approval's end. Unknown or
unavailable metadata fails closed. No key, label, user ID or provider body is
logged. Runtime-only ID and deadline settings bind provider execution and the
credit exemption to that account. Other users/providers are denied. Every
provider call checks the deadline, so access expires without requiring a deploy.
The server stops admitting calls by 2026-10-29 00:50 UTC (Oct 28 20:50 EDT), before
the approved key's expiry. It cannot silently renew the allowance.

The provider's $1 cap includes prior probe spend. This is not a paid plan or
unlimited vendor spending. Billing exposes separate synthetic allowance metadata;
the legacy `is_unlimited` field means exemption from app credits only. Existing
billing/admin semantics outside this isolated test are unchanged. The approval
permits made-up data only, not uploading real patient photographs.

`native-http-v3` is a separately claimed, once-only startup probe. It exercises
normal native signup/logout/login, session and CSRF checks, quick workspace,
multipart upload of geometric shapes, typed fictional evidence and a genuinely
AI-checked report through real HTTP routes in a disposable local SQLite fixture.
The live provider is used; no authentication/dependency overrides. Only a durable
nonclinical marker goes into Render's real test DB. The temporary test account,
fixture acceptance and fixture credit balance never enter that database. A
source-only report recovery is a failed probe, never a false pass.

The geometric image must return retake-required and no facts. This verifies the
upload/validation contract, not sufficient-quality wound accuracy or physical
Samsung behavior. Keep the existing Test14 APK: later debug builds may have a
different signing certificate and must not be offered as an in-place update.
Cloud Run, existing recordings, clinical settings, billing and the accidental
Cloud SQL instance are outside this change.

## Live verification, October 28, 2026

Functional commit: `19d5d109e057b82de3349ec5ac898d8f21dab991`.
CI run `40773839810` passed: backend 921 passed / 1 skipped, Android build,
unit tests, lint, flags and signature checks, and deployment safety checks.
The Cloud Run job reported readiness only; its credential/deploy steps remained
skipped. Locally, 287 focused tests passed before the push.

Deployment order preserved the activation gate:

1. Automatic code deployment `dep-dbh6rf2jnfac73avr4c0`: live, public AI paused.
2. Opt in to `native-http-v3`, without any owner allowance. Deployment
   `dep-dbh6rrrpkm4c73do9d9g` became live. At 21:54:45 UTC the fixed probe
   logged photo PASS, extract PASS and report PASS. The photo was geometric
   shapes with no wound: it returned a retake decision and zero findings.
3. Only after those explicit PASS results, clear the probe trigger and set
   `AMILYA_RENDER_OWNER_TEST_UNTIL=1793235000`. Deployment
   `dep-dbh6upifngtc738iifh0` logged `AMILYA_OWNER_TEST status=READY` at
   21:56:29 UTC and became live at 21:56:44 UTC. The read-only eligibility and
   capped-key checks therefore passed for the existing registered test account.
4. Public `/api/v1/config` returned HTTP 200, signup closed, synthetic test
   enabled, photo/document/AI enabled, clinical-data and billing disabled.
   Expiry is 2026-10-29 00:50 UTC / October 28 20:50 EDT. Existing APK Test14
   remains the intended APK; no later CI debug APK was handed over.
   `/api/v1/health` returned HTTP 200 / ok; an anonymous auth-session request
   returned HTTP 401.

Audio/transcription capability is still false: no speech provider is configured
on Render. Recording on the device is a separate function. Do not call this a
complete app migration, a successful Samsung wound assessment, or production
certification. The completed live tests used synthetic inputs and a temporary
fixture account. There was no patient-data migration or production traffic change.
