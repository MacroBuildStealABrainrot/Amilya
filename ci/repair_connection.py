"""Report authorization readiness without exposing variable or secret values."""
import json
import os
import re
from pathlib import Path


def readiness(env):
    requirements = {
        'AMILYA_GCP_WIF_PROVIDER': bool(re.fullmatch(r'projects/[0-9]+/locations/global/workloadIdentityPools/[a-z0-9-]+/providers/[a-z0-9-]+', env.get('AMILYA_GCP_WIF_PROVIDER',''))),
        'AMILYA_GCP_DEPLOY_ACCOUNT': bool(re.fullmatch(r'amilya-repair-ci@woundcopilot-20260905\.iam\.gserviceaccount\.com', env.get('AMILYA_GCP_DEPLOY_ACCOUNT',''))),
        'AMILYA_SMOKE_WORKSPACE_ID': bool(re.fullmatch(r'[A-Za-z0-9-]{1,80}',env.get('AMILYA_SMOKE_WORKSPACE_ID',''))),
        'AMILYA_SMOKE_EMAIL': bool(re.fullmatch(r'[^@\s]+@example\.test',env.get('AMILYA_SMOKE_EMAIL',''))),
        'AMILYA_SMOKE_PASSWORD': len(env.get('AMILYA_SMOKE_PASSWORD','')) >= 12,
        'AMILYA_REPAIR_DEPLOY_ENABLED': env.get('AMILYA_REPAIR_DEPLOY_ENABLED') == 'true',
    }
    return {'ready': all(requirements.values()),'missing':[name for name,valid in requirements.items() if not valid]}


if __name__ == '__main__':
    result = readiness(os.environ)
    with open(os.environ['GITHUB_OUTPUT'],'a') as output:
        output.write('ready=' + str(result['ready']).lower() + '\n')
    Path('repair-connection-status.json').write_text(json.dumps(result, indent=2))
    text = 'Ready for authenticated zero-traffic repair deployment.' if result['ready'] else 'BLOCKED: one-time cloud authorization or dedicated synthetic-account configuration is incomplete. Missing: ' + ', '.join(result['missing']) + '. No cloud deployment was attempted.'
    print(text)
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as summary:
        summary.write('\n### Repair deployment connection\n\n' + text + '\n')
