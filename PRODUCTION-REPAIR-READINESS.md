# Production repair readiness — resumed investigation

## Current result

The live repair remains blocked by Cloud Console access. A fresh read-only check of the existing project Logs tab again returned “Site Unavailable / Unable to access this site”, including one reload. Integration discovery returned no Google Cloud connector. This does not establish a Google Cloud outage or a defect in the owner's login.

The development branch is still at tested runtime source 04cb191fca646c822171a5437fe2a687a5afef89 plus documentation/comment-only commit dcb90c7e03f3b6888f4a7169edc497b5a5fc4480. Validation remains in VALIDATION-test12.md. No repeated tests were needed for this read-only investigation.

## Smallest safe server repair sequence

1. Through an authorized existing Google Cloud session, inspect Cloud Run woundcopilot in project woundcopilot-20260905, us-central1. Capture the active revision, exact image digest and current traffic allocation. Inspect only required configuration names and policy flags; do not export secret values.
2. Correlate the original photo request UUID and the extraction/report times in Cloud Logging. Retrieve the actual safe exception category before attributing the unresolved photo error to a model, privacy policy or funding.
3. Compare the active revision's source with the tested output-budget correction. Prepare the smallest compatible patch against that source. The repository's main branch has not been established as the deployed revision; do not assume they match.
4. Present that exact source/image change and the current revision as its rollback target for explicit production approval. A broad “finish the job” instruction does not replace the owner's earlier explicit production-approval requirement.
5. After approval, build a uniquely identified image, create a revision without changing traffic, and perform synthetic acceptance. Preserve current secrets, service identity, mounts, database connection and privacy flags. Do not enable APIs, link billing or run migrations based on historical preflight documentation.
6. Move traffic only after the agreed checks succeed; revert to the captured old revision if they fail. Verify pasted synthetic conversation extraction, report QA, non-patient photo input and recording/transcription, including requested/reported model provenance.

The existing backend/cloudbuild.yaml builds and publishes an image; it does not deploy Cloud Run or prove acceptance. Its fixed default release tag and historical cached image digest are not a verified production rollback target. The September DEPLOYMENT_PREFLIGHT.md describes an earlier provisioning state and must not be followed as current live instructions.

## Pilot signing blocker

Android Pilot inherits debug signing through initWith(debug). Its test11 and test12 public signer certificates differ, as recorded in VALIDATION-test12.md. The GitHub source tree and available local repository contain no JKS/keystore/P12/PFX file. The original private signing key is not available in this execution session; it is not known to be permanently lost.

Use the original matching key through secure build configuration to create an in-place update. Do not invent a key, commit a private key/password, uninstall the installed app, clear its data or assume a new key can sign an update accepted under the old certificate. If the original key is unavailable, a separately reviewed installation/data-preservation plan is required.

## Remaining access requirements

- An authorized execution environment that can reach Google Cloud and inspect the actual service and incident logs.
- Secure access to the installed Pilot's matching signing key/build configuration if an in-place Android upgrade is required.
- Explicit approval for the exact reviewed production change after the live revision and rollback target are known.

No live configuration, billing, balances, permissions, clinical-data flags, recordings or production traffic were changed.
