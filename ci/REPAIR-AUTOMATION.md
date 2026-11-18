# Amilya repair deployment automation

This workflow replaces repeated Cloud Shell commands after a one-time owner/admin connection. It does not authorize production promotion, billing changes, clinical-data activation, database deletion, or an APK signing change.

## Execution

On a push to `fix/server-diagnosis-20261025`, the existing backend (SQLite and PostgreSQL) and Android jobs must both pass, along with the deployment-controller safety tests. A superseded branch commit cannot authenticate for deployment. All external deployment actions are pinned by commit SHA.

The `repair-deploy` job then checks the `amilya-repair` GitHub environment. If configuration is absent, the job records **BLOCKED**, skips authentication and all cloud mutations, and publishes only a JSON file containing readiness and missing setting names. A green overall CI result with that message means source tests passed, not that a server was deployed.

When connected, it:

1. Uses GitHub OIDC / Workload Identity Federation to obtain short-lived Google credentials. No JSON service-account key is created.
2. Verifies `woundcopilot-00021-lrx` has 100% of normal traffic and every route is pinned to a revision. An unknown baseline, `LATEST` route, inherited address override, or changed endpoint stops the job.
3. Builds locally on GitHub's runner, using only the immutable original live image and `COPY providers.py`. It verifies original and built provider hashes, compiles the patched source, pushes to the existing `wcp` registry and deploys by digest. It never deploys the broader development backend from source.
4. Snapshots the service template into a private mode-0600 runner file before mutation. Copies the existing runtime account, secret references, network settings and resources unchanged. Adds explicit test addresses only to the candidate, with a zero-percent temporary tag.
5. Restores the complete prior service template immediately, before feature checks. It uses Cloud Run v2 etags, verifies normal traffic after every update, and refuses to overwrite a concurrent configuration change.
6. Uses a dedicated non-admin synthetic account for real API transcription, text-document extraction, AI report/QA and a nonclinical shapes-image check. A source-only report fallback is a failure. The image must safely return no findings and an insufficient-quality decision. This checks the provider path and safe rejection; it does not certify wound interpretation or physical Samsung recording.
7. Only if all feature checks pass, moves the existing `repair` tag to the candidate. The installed repair APK keeps its exact existing URL. Normal traffic stays on `00021-lrx`.
8. Checks the stable repair URL's health. If this fails, restores the previous repair tag. A `finally` recovery and a separate `always()` recovery step both protect template restoration. Forced runner loss or exhausted cloud permissions can still prevent recovery; this has not been exercised against live GCP yet.

No clinical image from Hazim's screenshot is used. No patient workspace is queried. Synthetic encounters use the app's normal temporary retention; the script never calls a delete endpoint. API response bodies, cookies, passwords, service configs and raw provider errors are not printed or uploaded. Only a closed error category and numeric HTTP status may be reported.

## One-time administrative connection

Prepared bootstrap: `python3 ci/repair_bootstrap.py` prints the permission plan without changing anything. `--apply` is for an already authenticated project administrator, after reviewing that plan. It creates a dedicated CI identity and scoped federation trust; it does not deploy the app. It has not been executed by this change.

Trust is restricted to repository ID `1426546615`, owner ID `217506124`, the exact repair branch, this exact workflow file, `push` events and the `amilya-repair` environment. An existing identity provider or custom role with different rules causes a stop rather than being overwritten. Changing the branch/workflow later requires an explicit trust update.

Permissions:

| Scope | Permission |
|---|---|
| Existing `woundcopilot` service only | Custom role: service get/update, revision get/list |
| Existing project | Custom role: Cloud Run operation get |
| Existing Artifact Registry repository `wcp` only | Artifact Registry Writer |
| Existing runtime service account only | Service Account User |
| Dedicated CI service account only | Workload Identity User for the restricted federation pool |

No Owner, Editor, SQL, Secret Manager accessor, service deletion, or IAM administration role is granted to CI. The bootstrap administrator needs IAM administration to establish these bindings once.

**Shared-service limitation:** Google IAM cannot restrict `run.services.update` to the `repair` tag. The identity can technically update the shared service; reviewed workflow guards enforce zero normal traffic. Acting as the existing runtime identity also permits running code with the app's existing runtime access. Separate staging infrastructure would provide stronger isolation but requires a separate endpoint/database plan and APK migration. This proposal preserves the current repair APK endpoint.

GitHub environment variables:

| Name | Value/source |
|---|---|
| `AMILYA_GCP_WIF_PROVIDER` | Bootstrap output, provider resource name |
| `AMILYA_GCP_DEPLOY_ACCOUNT` | `amilya-repair-ci@woundcopilot-20260905.iam.gserviceaccount.com` |
| `AMILYA_SMOKE_WORKSPACE_ID` | Existing isolated synthetic-only workspace ID |
| `AMILYA_REPAIR_DEPLOY_ENABLED` | `true` only after authorization and smoke-account setup are complete |

GitHub environment secrets: `AMILYA_SMOKE_EMAIL` and `AMILYA_SMOKE_PASSWORD`. The account must use `@example.test`, be non-admin, have at least a 12-character password, already have owner-approved legal acceptance and AI execution/credit access, and belong only to the configured synthetic workspace. No owner/admin login or production user credential should be stored for this workflow. The script does not create an account or accept legal terms on anyone's behalf.

The current connected GitHub tools cannot administer repository secrets/variables or Google IAM, and the Google Cloud browser endpoints were inaccessible. Those are access limitations, not a failed app test. Activation still needs this one-time authorized setup; adding workflow files alone does not establish cloud trust. There is no recurring Cloud Shell step after successful activation.

## Validation and remaining limits

Local checks: 19 controller/feature-gate tests passed. The backend compatibility selection (provider, error envelope, photo retry, Retry-After propagation) passed 217 tests after correcting header compatibility. Full GitHub CI must pass for the committed revision before deployment is eligible. A failed prior full run was 814 passed / 14 failed; those failures exposed changed header expectations and have been addressed without weakening rejection or automatic-retry guards.

No live deployment or provider-feature success is claimed until the connection gate reports ready and the deployment job completes its cloud checks. Existing recording data, production traffic, billing and clinical-data settings are unchanged by preparation.
