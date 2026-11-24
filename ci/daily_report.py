"""Amilya daily status report in plain English.

Collects facts from GitHub (always), the public health endpoint (always), Sentry (when
SENTRY_AUTH_TOKEN is set) and Google Cloud (when gcloud is authenticated). Anything not
connected is reported as "not connected", never guessed. Posts one comment on the
"Amilya daily report" issue, or only prints it with --dry-run.

Privacy: reads counts, statuses and route shapes only. Encounter/patient IDs in URLs are
redacted, and no request bodies, transcripts, photos or error messages are read.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone

STABLE_REVISION = 'woundcopilot-rel-ef3d1a8-c'
SERVICE, REGION = 'woundcopilot', 'us-central1'
HEALTH_URL = 'https://woundcopilot-1039151887397.us-central1.run.app/api/v1/health'
SENTRY_ORG, SENTRY_PROJECTS = 'amilya', ('amilya-android', 'amilya-backend')
SENTRY_API = 'https://us.sentry.io/api/0'
REPORT_LABEL, APPROVAL_LABEL = 'daily-report', 'needs-approval'
APK_ARTIFACT = 'amilya-apks'
# Production 5xx count in 24 h that is treated as serious.
SERIOUS_PROD_ERRORS = 5
LOG_LIMIT = 1000
ENCOUNTER_ROUTE = re.compile(
    r'/api/v1/encounters/[^/]+/(audio-parts|audio-complete|audio-verifications|quick-report|'
    r'documents|upload|consult|refine-report|transcribe-correction|transcribe-message|improve-report|improve-assessment)'
)


def http_json(url, token=None, method='GET', body=None, auth='Bearer', timeout=30):
    headers = {'Accept': 'application/json', 'User-Agent': 'amilya-daily-report'}
    if token:
        headers['Authorization'] = f'{auth} {token}'
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode() or 'null')


class GitHub:
    def __init__(self, repo, token, api='https://api.github.com'):
        self.repo, self.token, self.api = repo, token, api

    def get(self, path, **params):
        query = ('?' + urllib.parse.urlencode(params)) if params else ''
        return http_json(f'{self.api}/repos/{self.repo}{path}{query}', self.token)

    def post(self, path, body):
        return http_json(f'{self.api}/repos/{self.repo}{path}', self.token, 'POST', body)


def route_shape(url):
    try:
        path = urllib.parse.urlsplit(url or '').path.rstrip('/')
    except ValueError:
        return 'other route'
    if path in ('/api/v1/health', '/api/v1/encounters'):
        return path
    match = ENCOUNTER_ROUTE.fullmatch(path)
    return f'/api/v1/encounters/{{id}}/{match[1]}' if match else 'other route'


def collect_github(gh, since):
    runs = gh.get('/actions/runs', per_page=50, created=f'>={since:%Y-%m-%dT%H:%M:%SZ}').get('workflow_runs', [])
    finished = [r for r in runs if r.get('status') == 'completed' and r.get('conclusion') != 'skipped']
    apk = None
    for artifact in gh.get('/actions/artifacts', name=APK_ARTIFACT, per_page=10).get('artifacts', []):
        if artifact.get('expired'):
            continue
        run = gh.get(f"/actions/runs/{artifact['workflow_run']['id']}")
        if run.get('conclusion') == 'success':
            apk = {'created': artifact['created_at'], 'branch': run.get('head_branch'), 'url': run.get('html_url'),
                   'commit': (run.get('head_sha') or '')[:7]}
            break
    pulls = gh.get('/pulls', state='open', per_page=30)
    approvals = [i for i in gh.get('/issues', state='open', labels=APPROVAL_LABEL, per_page=30) if 'pull_request' not in i]
    return {
        'runs': [{'name': r.get('name'), 'branch': r.get('head_branch'), 'conclusion': r.get('conclusion'),
                  'url': r.get('html_url')} for r in finished],
        'running': sum(1 for r in runs if r.get('status') != 'completed'),
        'apk': apk,
        'pulls': [{'number': p['number'], 'title': p['title'], 'draft': p.get('draft'), 'url': p['html_url']} for p in pulls],
        'approvals': [{'number': i['number'], 'title': i['title'], 'url': i['html_url']} for i in approvals],
    }


def collect_health(url=HEALTH_URL):
    try:
        body = http_json(url, timeout=60)
        return {'ok': body.get('status') == 'ok', 'detail': f"version {body.get('version')}"}
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        return {'ok': False, 'detail': type(error).__name__}


def collect_sentry(token, since):
    if not token:
        return None
    result = {}
    for project in SENTRY_PROJECTS:
        try:
            issues = http_json(f'{SENTRY_API}/projects/{SENTRY_ORG}/{project}/issues/?'
                               + urllib.parse.urlencode({'query': 'is:unresolved', 'statsPeriod': '24h'}), token)
        except urllib.error.HTTPError as error:
            result[project] = {'error': f'HTTP {error.code}'}
            continue
        recent = [i for i in issues if (i.get('lastSeen') or '') >= since.strftime('%Y-%m-%dT%H:%M:%S')]
        # Controlled monitoring tests are not real problems.
        recent = [i for i in recent if not any(t in (i.get('title') or '') for t in ('monitoring_test', 'MonitoringSelfTest', 'ControlledTestCrash'))]
        result[project] = {
            'issues': len(recent),
            'new': sum(1 for i in recent if (i.get('firstSeen') or '') >= since.strftime('%Y-%m-%dT%H:%M:%S')),
            'fatal': sum(1 for i in recent if i.get('level') == 'fatal'),
            'top': [{'title': i.get('title', '')[:90], 'count': i.get('count'), 'url': i.get('permalink'),
                     'level': i.get('level') or 'error'} for i in recent[:5]],
        }
    return result


def collect_deepgram(token, project, now):
    """Remaining prepaid balance and audio hours used. Deepgram reports hours, not dollars."""
    if not (token and project):
        return None
    base = f'https://api.deepgram.com/v1/projects/{urllib.parse.quote(project)}'

    def hours(start):
        query = urllib.parse.urlencode({'start': start.strftime('%Y-%m-%d'), 'end': now.strftime('%Y-%m-%d')})
        results = http_json(f'{base}/usage/breakdown?{query}', token, auth='Token').get('results', [])
        return round(sum(float(r.get('hours') or 0) for r in results), 2)

    try:
        balances = http_json(f'{base}/balances', token, auth='Token').get('balances', [])
        return {
            'balances': [{'amount': b.get('amount'), 'units': b.get('units')} for b in balances],
            'hours_today': hours(now),
            'hours_month': hours(now.replace(day=1)),
        }
    except urllib.error.HTTPError as error:
        return {'error': f'HTTP {error.code}'}


def gcloud(*args, fields):
    exe = shutil.which('gcloud')
    if not exe:
        return None
    try:
        out = subprocess.run([exe, *args, f'--format=json({fields})'], capture_output=True,
                             text=True, encoding='utf-8', timeout=180)
        return json.loads(out.stdout) if out.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None


def collect_gcp():
    service = gcloud('run', 'services', 'describe', SERVICE, f'--region={REGION}',
                     fields='status.traffic,status.latestReadyRevisionName')
    if service is None:
        return {'traffic': [], 'errors': None, 'prod_routes': []}
    status = service.get('status', service)
    traffic = []
    for target in status.get('traffic', []):
        if target.get('percent', 0) > 0:
            revision = target.get('revisionName')
            if not revision and target.get('latestRevision'):
                revision = status.get('latestReadyRevisionName')
            traffic.append({'revision': revision, 'percent': target['percent']})
    entries = gcloud('logging', 'read', '--freshness=1d', f'--limit={LOG_LIMIT}',
                     f'resource.type="cloud_run_revision" AND resource.labels.service_name="{SERVICE}" '
                     f'AND resource.labels.location="{REGION}" AND log_id("run.googleapis.com/requests") '
                     'AND httpRequest.status>=500 AND httpRequest.status<600',
                     fields='resource.labels.revision_name,httpRequest.status,httpRequest.requestUrl')
    if entries is None:
        return {'traffic': traffic, 'errors': None, 'prod_routes': []}
    live = {t['revision'] for t in traffic if t['revision']}
    errors = Counter()
    routes = Counter()
    for entry in entries:
        revision = entry.get('resource', {}).get('labels', {}).get('revision_name') or entry.get('revision_name', 'unknown')
        errors[revision] += 1
        if revision in live:
            request = entry.get('httpRequest', entry)
            routes[f"{request.get('status')} {route_shape(request.get('requestUrl'))}"] += 1
    return {'traffic': traffic, 'errors': dict(errors), 'prod_routes': routes.most_common(5),
            'limit_reached': len(entries) >= LOG_LIMIT}


def live_error_count(gcp):
    revisions = {t['revision'] for t in gcp['traffic'] if t['percent'] > 0}
    if gcp['errors'] is None or not revisions or None in revisions:
        return None
    return sum(gcp['errors'].get(revision, 0) for revision in revisions)


def assess(data):
    """Return the list of serious problems that should notify the owner immediately."""
    serious = []
    if not data['health']['ok']:
        serious.append('The live server did not answer its health check.')
    gcp = data.get('gcp')
    if gcp:
        live = [t['revision'] for t in gcp['traffic'] if t['percent'] == 100]
        if not gcp['traffic'] or any(t['revision'] is None for t in gcp['traffic']):
            serious.append('Could not identify which versions are receiving live traffic.')
        elif live != [STABLE_REVISION]:
            serious.append(f'Live traffic is not on the approved version {STABLE_REVISION}: {gcp["traffic"]}.')
        prod = live_error_count(gcp)
        if gcp['errors'] is None:
            serious.append('The server error count is unavailable.')
        elif prod is not None and prod >= SERIOUS_PROD_ERRORS:
            serious.append(f'Found {prod} server errors in 24 hours on versions currently receiving live traffic.')
    for project, info in (data.get('sentry') or {}).items():
        if info.get('fatal'):
            serious.append(f'{project}: {info["fatal"]} crash type(s) marked fatal in Sentry.')
    if any(r['branch'] == 'main' and r['conclusion'] == 'failure' for r in data['github']['runs']):
        serious.append('Automatic tests failed on the main version of the code.')
    return serious


def render(data, owner=None):
    gh, gcp, sentry = data['github'], data.get('gcp'), data.get('sentry')
    serious = assess(data)
    lines = [f"# Amilya daily report: {data['date']}", '']
    if serious:
        lines += [f"**Needs your attention{f' @{owner}' if owner else ''}:**", *[f'- {s}' for s in serious], '']
    else:
        lines += ['**Nothing serious today.**', '']

    lines += ['## Is Amilya working?',
              f"- Live server health check: {'working' if data['health']['ok'] else 'NOT answering'} ({data['health']['detail']}).",]
    if gcp:
        traffic = ', '.join(f"{t['revision'] or 'unknown version'} {t['percent']}%" for t in gcp['traffic'])
        lines.append(f"- Live traffic: {traffic or 'unavailable'}.")
        prod = live_error_count(gcp)
        if prod is None:
            lines.append('- Live server error count: unavailable.')
        else:
            lines.append(f'- Server errors in the last 24 hours on versions currently receiving live traffic: {prod}.')
            for route, count in gcp['prod_routes']:
                lines.append(f'  - {count} x {route}')
            current = {t['revision'] for t in gcp['traffic'] if t['percent'] > 0}
            others = {k: v for k, v in gcp['errors'].items() if k not in current}
            if others:
                lines.append('- Errors on other versions during this period: '
                             + ', '.join(f'{k} {v}' for k, v in sorted(others.items())) + '.')
        if gcp.get('limit_reached'):
            lines.append(f'- The log search reached its limit of {LOG_LIMIT} entries. Counts may be higher.')
    else:
        lines.append('- Google Cloud details: not connected yet (one-time setup waiting for approval).')

    lines += ['', '## Crashes and errors (Sentry)']
    if sentry is None:
        lines.append('- Not connected yet. Needs a Sentry read-only token saved as a GitHub secret.')
    else:
        for project, info in sentry.items():
            if 'error' in info:
                lines.append(f"- {project}: could not read ({info['error']}).")
                continue
            lines.append(f"- {project}: {info['issues']} problem type(s) seen in 24 hours, {info['new']} new.")
            lines += [f"  - {t.get('level', 'error')}: [{t['title']}]({t['url']}) ({t['count']} times in total)" for t in info['top']]

    lines += ['', '## Automatic tests and fixes (last 24 hours)']
    if gh['runs']:
        passed = sum(1 for r in gh['runs'] if r['conclusion'] == 'success')
        lines.append(f"- {passed} of {len(gh['runs'])} test runs passed.")
        lines += [f"  - [{r['name']}]({r['url']}) on `{r['branch']}`: {r['conclusion']}"
                  for r in gh['runs'] if r['conclusion'] != 'success'][:8]
    else:
        lines.append('- No test runs finished.')
    if gh['running']:
        lines.append(f"- {gh['running']} run(s) still in progress.")

    lines += ['', '## Pilot APK']
    apk = gh['apk']
    lines.append(f"- Latest tested APK: built {apk['created'][:16].replace('T', ' ')} UTC from `{apk['branch']}` "
                 f"({apk['commit']}). [Download from the run page]({apk['url']}) under Artifacts > {APK_ARTIFACT}."
                 if apk else '- No tested APK is available (none built recently, or downloads expired).')

    lines += ['', '## Clinical AI quality tests', '- Not set up yet (planned: synthetic-case evaluation).',
              '', '## Spending', '- Google Cloud: not connected yet. Real billing figures need the billing export (waiting for approval).',
              '- OpenRouter (all AI models) and Groq (transcription): no read-only access exists. Check their own dashboards and spend limits.']
    deepgram = data.get('deepgram')
    if deepgram is None:
        lines.append('- Deepgram (transcription): not connected yet.')
    elif 'error' in deepgram:
        lines.append(f"- Deepgram (transcription): could not read ({deepgram['error']}).")
    else:
        left = ', '.join(f"{b['amount']} {b['units']}" for b in deepgram['balances']) or 'none listed'
        lines.append(f"- Deepgram (transcription): {deepgram['hours_today']} audio hours today, "
                     f"{deepgram['hours_month']} this month. Prepaid balance left: {left}.")
    lines += ['', '## Waiting for you']
    waiting = [f"- [Approval #{a['number']}: {a['title']}]({a['url']})" for a in gh['approvals']]
    waiting += [f"- [{'Draft' if p['draft'] else 'Ready for review'} change #{p['number']}: {p['title']}]({p['url']})"
                for p in gh['pulls']]
    lines += waiting or ['- Nothing.']
    lines += ['', '_Production is never changed by this report. Facts marked "not connected" are not guessed._']
    return '\n'.join(lines) + '\n'


def post(gh, body):
    issues = gh.get('/issues', state='open', labels=REPORT_LABEL, per_page=5)
    issue = next((i for i in issues if 'pull_request' not in i), None)
    if issue is None:
        issue = gh.post('/issues', {'title': 'Amilya daily report', 'labels': [REPORT_LABEL],
                                    'body': 'One comment per day. Subscribe to this issue to get each report by email.'})
    return gh.post(f"/issues/{issue['number']}/comments", {'body': body})['html_url']


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--dry-run', action='store_true', help='print the report instead of posting it')
    parser.add_argument('--repo', default=os.environ.get('GITHUB_REPOSITORY', 'MacroBuildStealABrainrot/Amilya'))
    args = parser.parse_args(argv)
    now = datetime.now(timezone.utc)
    since = now - timedelta(days=1)
    gh = GitHub(args.repo, os.environ.get('GITHUB_TOKEN'))
    try:
        github = collect_github(gh, since)
    except urllib.error.HTTPError as error:
        if not args.dry_run:
            raise
        print(f'GitHub not readable here (HTTP {error.code}); using an empty GitHub section.')
        github = {'runs': [], 'running': 0, 'apk': None, 'pulls': [], 'approvals': []}
    data = {
        'date': now.strftime('%Y-%m-%d'),
        'github': github,
        'health': collect_health(),
        'sentry': collect_sentry(os.environ.get('SENTRY_AUTH_TOKEN'), since),
        'deepgram': collect_deepgram(os.environ.get('DEEPGRAM_USAGE_KEY'), os.environ.get('DEEPGRAM_PROJECT_ID'), now),
        'gcp': collect_gcp() if os.environ.get('AMILYA_REPORT_GCP', '1') == '1' else None,
    }
    body = render(data, owner=args.repo.split('/')[0])
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as handle:
            handle.write(body)
    if args.dry_run:
        print(body)
    else:
        print('Posted:', post(gh, body))


if __name__ == '__main__':
    main()
