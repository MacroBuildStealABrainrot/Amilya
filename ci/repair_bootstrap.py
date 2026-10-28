#!/usr/bin/env python3
"""One-time administrator bootstrap. Defaults to a read-only printed plan.

No keys are created. Does not deploy, read application secrets, or access SQL.
Run with --apply only from an already authorized administrator environment.
"""
import argparse
import json
import subprocess

PROJECT='woundcopilot-20260905'
REGION='us-central1'
SERVICE='woundcopilot'
REPO='MacroBuildStealABrainrot/Amilya'
BRANCH='refs/heads/fix/server-diagnosis-20261025'
ACCOUNT=f'amilya-repair-ci@{PROJECT}.iam.gserviceaccount.com'
POOL='amilya-repair-github'
PROVIDER='github-repair'
WORKFLOW=f'{REPO}/.github/workflows/amilya-sandbox-ci.yml@{BRANCH}'
CONDITION=("assertion.repository_id=='1426546615' && assertion.repository_owner_id=='217506124'"
    f" && assertion.ref=='{BRANCH}' && assertion.workflow_ref=='{WORKFLOW}'"
    f" && assertion.sub=='repo:{REPO}:environment:amilya-repair' && assertion.event_name=='push'")
ROLES={
    'amilyaRepairService': ['run.services.get','run.services.update','run.revisions.get','run.revisions.list'],
    'amilyaRepairOperations': ['run.operations.get'],
}


def call(*args, missing=False):
    p=subprocess.run(['gcloud',*args,'--project='+PROJECT,'--format=json','--quiet'],capture_output=True,text=True)
    if p.returncode:
        if missing and ('NOT_FOUND' in p.stderr or 'not found' in p.stderr): return None
        raise RuntimeError('Administrator operation failed; command output suppressed.')
    return json.loads(p.stdout) if p.stdout.strip() else {}


def apply():
    call('services','enable','iam.googleapis.com','iamcredentials.googleapis.com','sts.googleapis.com')
    project=call('projects','describe',PROJECT)
    number=str(project['projectNumber'])
    service=call('run','services','describe',SERVICE,'--region='+REGION)
    runtime=service['spec']['template']['spec'].get('serviceAccountName')
    if not runtime: raise RuntimeError('Runtime service account must be explicit before granting actAs.')
    if not call('iam','service-accounts','describe',ACCOUNT,missing=True):
        call('iam','service-accounts','create','amilya-repair-ci','--display-name=Amilya repair CI')
    if not call('iam','workload-identity-pools','describe',POOL,'--location=global',missing=True):
        call('iam','workload-identity-pools','create',POOL,'--location=global','--display-name=Amilya repair GitHub')
    provider=call('iam','workload-identity-pools','providers','describe',PROVIDER,'--workload-identity-pool='+POOL,'--location=global',missing=True)
    mapping={'google.subject':'assertion.sub','attribute.repository_id':'assertion.repository_id'}
    if provider:
        if provider.get('attributeCondition')!=CONDITION or provider.get('attributeMapping')!=mapping or provider.get('oidc',{}).get('issuerUri')!='https://token.actions.githubusercontent.com':
            raise RuntimeError('Existing provider trust differs; refusing to broaden or overwrite it.')
    else:
        call('iam','workload-identity-pools','providers','create-oidc',PROVIDER,'--workload-identity-pool='+POOL,
            '--location=global','--issuer-uri=https://token.actions.githubusercontent.com',
            '--attribute-mapping='+','.join(k+'='+v for k,v in mapping.items()),'--attribute-condition='+CONDITION)
    for name,permissions in ROLES.items():
        old=call('iam','roles','describe',name,missing=True)
        if old:
            if set(old.get('includedPermissions',[]))!=set(permissions):
                raise RuntimeError('Existing custom role differs; administrator review required.')
        else:
            call('iam','roles','create',name,'--title='+name,'--stage=GA','--permissions='+','.join(permissions))
    member='serviceAccount:'+ACCOUNT
    call('run','services','add-iam-policy-binding',SERVICE,'--region='+REGION,'--member='+member,'--role=projects/'+PROJECT+'/roles/amilyaRepairService')
    call('projects','add-iam-policy-binding',PROJECT,'--member='+member,'--role=projects/'+PROJECT+'/roles/amilyaRepairOperations','--condition=None')
    call('artifacts','repositories','add-iam-policy-binding','wcp','--location='+REGION,'--member='+member,'--role=roles/artifactregistry.writer')
    call('iam','service-accounts','add-iam-policy-binding',runtime,'--member='+member,'--role=roles/iam.serviceAccountUser')
    principal=f'principalSet://iam.googleapis.com/projects/{number}/locations/global/workloadIdentityPools/{POOL}/attribute.repository_id/1426546615'
    call('iam','service-accounts','add-iam-policy-binding',ACCOUNT,'--member='+principal,'--role=roles/iam.workloadIdentityUser')
    print(json.dumps({'AMILYA_GCP_WIF_PROVIDER':f'projects/{number}/locations/global/workloadIdentityPools/{POOL}/providers/{PROVIDER}',
        'AMILYA_GCP_DEPLOY_ACCOUNT':ACCOUNT},indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--apply',action='store_true')
    args=p.parse_args()
    if args.apply:
        try: apply()
        except (RuntimeError,KeyError,ValueError):
            raise SystemExit('Bootstrap stopped; no credentials or API bodies were printed.') from None
    else:
        print(json.dumps({'mode':'plan_only','project':PROJECT,'service':SERVICE,'account':ACCOUNT,
            'trust_condition':CONDITION,'roles':ROLES,
            'additional_scopes':['Artifact Registry writer on repository wcp only',
                'Service Account User on existing runtime account only','Workload Identity User on CI account only'],
            'not_granted':['Owner','Editor','Secret Manager secret accessor','SQL roles','service deletion','IAM policy administration']},indent=2))
