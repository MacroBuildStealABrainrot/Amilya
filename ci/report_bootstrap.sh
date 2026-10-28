#!/usr/bin/env bash
# One-time, owner-approved setup: a READ-ONLY Google Cloud identity for the daily report workflow.
# Grants only Cloud Run Viewer and Logs Viewer. It cannot deploy, change traffic, read secrets,
# touch the database or billing. Trust is limited to this repository's daily-report workflow on main.
# Run in Cloud Shell (or an authenticated gcloud) as a project administrator after reviewing it.
set -euo pipefail

PROJECT=woundcopilot-20260905
PROJECT_NUMBER=1039151887397
REPO_ID=1426546615            # MacroBuildStealABrainrot/Amilya
REPO=MacroBuildStealABrainrot/Amilya
POOL=github-readonly
PROVIDER=amilya-daily-report
SA=amilya-report-ci@${PROJECT}.iam.gserviceaccount.com
WORKFLOW_REF="${REPO}/.github/workflows/amilya-daily-report.yml@refs/heads/main"

gcloud iam workload-identity-pools create "$POOL" --project="$PROJECT" --location=global \
  --display-name="GitHub read-only reports"
gcloud iam workload-identity-pools providers create-oidc "$PROVIDER" --project="$PROJECT" --location=global \
  --workload-identity-pool="$POOL" --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.workflow_ref=assertion.job_workflow_ref" \
  --attribute-condition="assertion.repository_id=='${REPO_ID}' && assertion.job_workflow_ref=='${WORKFLOW_REF}'"
gcloud iam service-accounts create amilya-report-ci --project="$PROJECT" \
  --display-name="Amilya daily report (read-only)"
for role in roles/run.viewer roles/logging.viewer; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member="serviceAccount:${SA}" --role="$role" --condition=None
done
gcloud iam service-accounts add-iam-policy-binding "$SA" --project="$PROJECT" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/attribute.repository_id/${REPO_ID}"

echo
echo "Add these as GitHub repository VARIABLES (Settings > Secrets and variables > Actions > Variables):"
echo "  AMILYA_REPORT_WIF_PROVIDER = projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/providers/${PROVIDER}"
echo "  AMILYA_REPORT_ACCOUNT      = ${SA}"
