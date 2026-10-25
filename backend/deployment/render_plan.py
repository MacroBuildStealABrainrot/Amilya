"""Render an unapplied, secret-free Google Cloud deployment plan. No SDK/network calls."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ELIGIBLE_REGIONS = {"us-central1", "us-east1", "us-west1"}


def make_plan(project="woundcopilot-20260905", region="us-central1", zone="us-central1-a",
              app_image=None, postgres_image=None, boot_gb=10, data_gb=20):
    if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project):
        raise ValueError("Invalid project ID")
    if region not in ELIGIBLE_REGIONS or not zone.startswith(region + "-"):
        raise ValueError("Use an eligible Free Tier region and a zone within it")
    if boot_gb < 10 or data_gb < 1 or boot_gb + data_gb > 30:
        raise ValueError("Boot plus data disks must total at most 30 GB; boot minimum 10 GB")
    image_pattern = rf"{region}-docker\.pkg\.dev/{re.escape(project)}/wcp/[a-z0-9._/-]+@sha256:[a-f0-9]{{64}}"
    for value in (app_image, postgres_image):
        if value and not re.fullmatch(image_pattern, value):
            raise ValueError("Images must use an immutable digest in this project's regional wcp repository")
    run_sa = f"wcp-run@{project}.iam.gserviceaccount.com"
    db_sa = f"wcp-db@{project}.iam.gserviceaccount.com"
    steps = []

    def step(name, args, gates=()):
        steps.append({"name": name, "program": "gcloud", "args": [*args, f"--project={project}", "--quiet"],
                      "requires": ["owner_review_and_billing_authorization", *gates]})

    step("Enable selected APIs", ["services", "enable", "compute.googleapis.com", "run.googleapis.com",
         "artifactregistry.googleapis.com", "secretmanager.googleapis.com", "iap.googleapis.com"])
    step("Create isolated VPC", ["compute", "networks", "create", "wcp-vpc", "--subnet-mode=custom"])
    step("Create private subnet", ["compute", "networks", "subnets", "create", "wcp-private",
         "--network=wcp-vpc", "--range=10.30.0.0/26", f"--region={region}", "--enable-private-ip-google-access"])
    step("Create runtime identity", ["iam", "service-accounts", "create", "wcp-run"])
    step("Create database identity", ["iam", "service-accounts", "create", "wcp-db"])
    step("Create regional image repository", ["artifacts", "repositories", "create", "wcp",
         "--repository-format=docker", f"--location={region}", "--immutable-tags"], ["account_registry_storage_checked"])
    step("Allow database VM to read regional images", ["artifacts", "repositories", "add-iam-policy-binding", "wcp",
         f"--location={region}", f"--member=serviceAccount:{db_sa}", "--role=roles/artifactregistry.reader"])
    step("Allow database VM to access only its password", ["secrets", "add-iam-policy-binding", "wcp-db-owner",
         f"--member=serviceAccount:{db_sa}", "--role=roles/secretmanager.secretAccessor"], ["secrets_created_out_of_band"])
    for secret in ("wcp-app-env", "wcp-db-ca"):
        step(f"Allow runtime access to {secret}", ["secrets", "add-iam-policy-binding", secret,
             f"--member=serviceAccount:{run_sa}", "--role=roles/secretmanager.secretAccessor"], ["secrets_created_out_of_band"])
    step("Allow PostgreSQL from Cloud Run only", ["compute", "firewall-rules", "create", "wcp-run-to-db",
         "--network=wcp-vpc", "--direction=INGRESS", "--action=ALLOW", "--rules=tcp:5432",
         "--source-tags=wcp-run", "--target-tags=wcp-db"])
    step("Allow operator SSH through IAP", ["compute", "firewall-rules", "create", "wcp-iap-ssh",
         "--network=wcp-vpc", "--direction=INGRESS", "--action=ALLOW", "--rules=tcp:22",
         "--source-ranges=35.235.240.0/20", "--target-tags=wcp-db"])
    step("Create retained PostgreSQL data disk", ["compute", "disks", "create", "wcp-data", f"--zone={zone}",
         "--type=pd-standard", f"--size={data_gb}GB"], ["account_compute_and_disk_allowance_checked"])
    step("Create private database VM", ["compute", "instances", "create", "wcp-db", f"--zone={zone}",
         "--machine-type=e2-micro", "--subnet=wcp-private", "--private-network-ip=10.30.0.10", "--no-address",
         "--image-family=cos-stable", "--image-project=cos-cloud", "--boot-disk-type=pd-standard",
         f"--boot-disk-size={boot_gb}GB", "--disk=name=wcp-data,device-name=wcp-data,mode=rw,auto-delete=no",
         f"--service-account={db_sa}", "--scopes=https://www.googleapis.com/auth/cloud-platform", "--tags=wcp-db",
         "--shielded-secure-boot", "--shielded-vtpm", "--shielded-integrity-monitoring", "--deletion-protection",
         "--metadata=enable-oslogin=TRUE,block-project-ssh-keys=TRUE",
         "--metadata-from-file=startup-script=deployment/postgres-startup.sh"],
         ["account_compute_and_disk_allowance_checked", "reviewed_cos_image", "postgres_image_mirrored"])
    step("Deploy private application for validation", ["run", "deploy", "woundcopilot", f"--region={region}",
         f"--image={app_image or '<REQUIRED_APP_DIGEST>'}", f"--service-account={run_sa}",
         "--network=wcp-vpc", "--subnet=wcp-private", "--vpc-egress=private-ranges-only", "--network-tags=wcp-run",
         "--min=0", "--max=1", "--min-instances=0", "--max-instances=1", "--cpu=1", "--memory=1Gi",
         "--cpu-throttling", "--no-cpu-boost", "--concurrency=8", "--timeout=300", "--port=8080",
         "--no-allow-unauthenticated", "--set-env-vars=WOUNDCOPILOT_ENV_FILE=/run/secrets/app.env",
         "--set-secrets=/run/secrets/app.env=wcp-app-env:2,/run/db-ca/ca.crt=wcp-db-ca:1"],
         ["image_digests_verified", "account_run_and_network_allowance_checked", "database_tls_and_roles_verified",
          "migrations_applied", "runtime_env_prepared", "restore_drill_passed"])
    return {
        "status": "UNAPPLIED_PROPOSAL", "project": project, "region": region, "zone": zone,
        "generated_network_calls": 0, "automatic_apply_supported": False, "zero_cost_guaranteed": False,
        "disk_total_gb": boot_gb + data_gb, "postgres_image": postgres_image,
        "exclusions": ["Cloud SQL", "external IPv4", "Cloud NAT", "VPC connector", "load balancer", "Cloud DNS",
                       "paid image scanning", "automatic Cloud Build", "clinical use before release approval"],
        "unresolved": ["No billing account linked or allowance inventory verified by this plan",
                       "Mirror both digest-pinned images; sum all retained registry layers across billing-account projects",
                       "Create secrets without putting values in arguments, repository, metadata, logs, or this plan",
                       "Bootstrap data filesystem, PostgreSQL TLS files and runtime role using docs/DEPLOYMENT.md",
                       "Replace default boot image family with a reviewed exact COS image before applying",
                       "Review actual project IAM and Google service-agent permissions; no broad editor grants",
                       "Complete migration, backup/restore, cookie/domain, privacy and operational readiness gates",
                       "Public service IAM and billing webhooks require a separate verified release step"],
        "steps": steps,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="woundcopilot-20260905")
    parser.add_argument("--region", default="us-central1")
    parser.add_argument("--zone", default="us-central1-a")
    parser.add_argument("--app-image")
    parser.add_argument("--postgres-image")
    parser.add_argument("--out", type=Path, default=Path("deployment/plan.json"))
    args = parser.parse_args()
    data = make_plan(args.project, args.region, args.zone, args.app_image, args.postgres_image)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote unapplied proposal to {args.out}; no cloud APIs called")
