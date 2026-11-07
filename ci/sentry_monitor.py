"""Amilya hourly Sentry monitor: posts to GitHub only what is new, reopened or getting worse.

Reads the Android and backend Sentry projects with the read-only SENTRY_AUTH_TOKEN, looks at issues seen in the last
60 minutes, compares them with what earlier runs already reported, and writes a short plain-English comment on the
"Amilya hourly monitor" issue for ChatGPT and the owner to read. A run with nothing new only refreshes the "last
checked" line at the top of that issue, so the issue never fills with noise.

A failed Sentry call is reported as a MONITORING FAILURE, never as "no errors".

PRIVACY: only closed technical metadata is read and written: issue short ID, link, times, counts, level, status,
environment and Amilya's own category tags (component, stage, code, http status, route shape, kind). No event
messages, breadcrumbs, request data, transcripts, notes, photos or user details are requested or published. A title
that is not in Amilya's fixed "Amilya <component>: <stage> <code>" shape is withheld.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

SENTRY_API = 'https://us.sentry.io/api/0'
ORG = 'amilya'
PROJECTS = ('amilya-android', 'amilya-backend')
ISSUE_TITLE = 'Amilya hourly monitor'
LABEL = 'sentry-monitor'
STATE_MARK = 'amilya-monitor-state'
SAFE_TAGS = ('environment', 'component', 'stage', 'code', 'http_status', 'route', 'kind', 'flow_type', 'retries',
             'recording_exists', 'storage', 'release', 'defects')
SAFE_VALUE = re.compile(r'^[A-Za-z0-9_.@/{}:+-]{1,80}$')
SAFE_TITLE = re.compile(r'^Amilya (android|backend): [a-z0-9_]{1,60}( [A-Za-z0-9_]{1,60})?$')
# "Materially worse": at least this many new events since the last report AND at least half again as many.
WORSE_MIN_NEW, WORSE_RATIO = 5, 0.5
LEVEL_RANK = {'info': 0, 'warning': 1, 'error': 2, 'fatal': 3}


class SentryUnavailable(Exception):
    """Any failure to read Sentry. Never turned into "zero errors"."""


def http_json(url, token=None, method='GET', body=None, timeout=30):
    headers = {'Accept': 'application/json', 'User-Agent': 'amilya-sentry-monitor'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    data = json.dumps(body).encode() if body is not None else None
    if data:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode() or 'null')


# ---------- Classification ----------
CATEGORIES = [
    ('App crash or freeze', lambda t: t['stage'] in {'app_crash', 'app_not_responding', 'app_restart'}),
    ('Monitoring self-test', lambda t: t['stage'] in {'monitoring_test', 'monitoring_self_test'} or t['code'] in {'monitoring_self_test'}),
    ('Clinical accuracy check', lambda t: t['kind'] == 'clinical_check' or t['stage'].startswith('source_faithfulness')),
    ('AI fallback', lambda t: t['kind'] == 'fallback' or 'fallback' in t['stage']),
    ('Backend exception', lambda t: t['code'] == 'unexpected_exception' or (t['component'] == 'backend' and t['stage'] in {'server', 'request'})),
    ('Recording', lambda t: t['stage'] in {'recording_captured', 'recording_stopped', 'local_save', 'audio_finalization', 'audio_preparation', 'recording'}),
    ('Multipart handover', lambda t: t['stage'] in {'upload', 'upload_pending', 'request_upload', 'transcript_assembly', 'audio_parts', 'audio_complete'}),
    ('Transcription', lambda t: t['stage'].startswith(('transcri', 'speech')) or t['stage'] == 'transcription_request'),
    ('Image upload', lambda t: t['stage'].startswith('photo') or t['stage'] in {'external_failed', 'image', 'media_upload'}),
    ('Ask Consultant', lambda t: t['stage'].startswith(('consult', 'ask_consult')) or 'consult' in t['route']),
    ('Report saving', lambda t: t['stage'] in {'report_save', 'report_save_network', 'document_save'}),
    ('Report generation', lambda t: t['stage'] in {'generation_request', 'report_network', 'report_state', 'report_display', 'processing_total'}
                                    or t['stage'].startswith(('quick_', 'report'))),
]


def classify(tags: dict) -> str:
    t = {k: str(tags.get(k) or '') for k in ('stage', 'code', 'component', 'kind', 'route')}
    for name, rule in CATEGORIES:
        if rule(t):
            return name
    return 'Other'


def where(environment: str) -> str:
    return {'isolated-test': 'Test server', 'production': 'Production'}.get(environment or '', f'Unknown ({environment or "not set"})')


def severity(level: str, category: str, events_last_hour: int) -> str:
    base = {'fatal': 'Critical', 'error': 'High', 'warning': 'Medium', 'info': 'Low'}.get(level or '', 'Medium')
    if category == 'Monitoring self-test':
        return 'Low'
    if events_last_hour >= 10 and base in {'Medium', 'Low'}:
        return 'High'
    return base


def safe_tags(raw) -> dict:
    out = {}
    for item in raw or []:
        key, value = (item.get('key'), item.get('value')) if isinstance(item, dict) else (item[0], item[1]) if isinstance(item, (list, tuple)) and len(item) == 2 else (None, None)
        if key in SAFE_TAGS and isinstance(value, str) and SAFE_VALUE.match(value):
            out[key] = value
    return out


def safe_title(title: str) -> str:
    return title if isinstance(title, str) and SAFE_TITLE.match(title) else '(title withheld for privacy)'


# ---------- Reading Sentry ----------
def events_since(stats, now: datetime) -> int:
    """Events in the hourly buckets that overlap the last 60 minutes (Sentry counts per whole hour)."""
    total = 0
    for bucket in (stats or {}).get('24h') or []:
        if isinstance(bucket, (list, tuple)) and len(bucket) == 2 and bucket[0] > now.timestamp() - 7200:
            total += int(bucket[1] or 0)
    return total


def fetch(token: str, now: datetime, get=http_json, hours: int = 1) -> list[dict]:
    """Issues seen in the last `hours` (normally 1) in both projects, with safe metadata only. Raises SentryUnavailable."""
    if not token:
        raise SentryUnavailable('the read-only Sentry token is not available to this run')
    found = []
    for project in PROJECTS:
        url = (f'{SENTRY_API}/projects/{ORG}/{project}/issues/?'
               + urllib.parse.urlencode({'query': f'lastSeen:-{int(hours)}h', 'statsPeriod': '24h', 'limit': 100}))
        try:
            issues = get(url, token)
        except urllib.error.HTTPError as error:
            raise SentryUnavailable(f'{project}: Sentry answered HTTP {error.code}') from None
        except (urllib.error.URLError, TimeoutError, ValueError, OSError) as error:
            raise SentryUnavailable(f'{project}: Sentry could not be reached ({type(error).__name__})') from None
        if not isinstance(issues, list):
            raise SentryUnavailable(f'{project}: Sentry sent an unreadable answer')
        for issue in issues:
            try:
                latest = get(f"{SENTRY_API}/organizations/{ORG}/issues/{urllib.parse.quote(str(issue['id']))}/events/latest/", token)
                tags = safe_tags((latest or {}).get('tags'))
            except (urllib.error.URLError, TimeoutError, ValueError, OSError):
                tags = {}  # the issue is still reported; only its category may be "Other"
            category = classify(tags)
            last_hour = events_since(issue.get('stats'), now)
            found.append({
                'id': str(issue.get('id')), 'short_id': issue.get('shortId'), 'project': project,
                'title': safe_title(issue.get('title')), 'link': issue.get('permalink'),
                'first_seen': issue.get('firstSeen'), 'last_seen': issue.get('lastSeen'),
                'count': int(issue.get('count') or 0), 'last_hour': last_hour, 'level': issue.get('level'),
                'status': issue.get('status'), 'substatus': issue.get('substatus'), 'environment': tags.get('environment', ''),
                'where': where(tags.get('environment', '')), 'category': category,
                'severity': severity(issue.get('level'), category, last_hour),
                'tags': {k: v for k, v in tags.items() if k not in {'environment'}},
            })
    return found


# ---------- Deciding what is worth reporting ----------
def decide(issues: list[dict], state: dict) -> list[dict]:
    """Marks each issue new / reopened / worse, or None when it was already reported and is not materially worse."""
    seen = state.get('issues', {})
    for issue in issues:
        before = seen.get(issue['id'])
        issue['change'] = None
        if before is None:
            issue['change'] = 'New'
        elif issue.get('substatus') == 'regressed' or before.get('status') in {'resolved', 'ignored'} and issue.get('status') == 'unresolved':
            issue['change'] = 'Reopened'
        else:
            added = issue['count'] - int(before.get('count') or 0)
            louder = LEVEL_RANK.get(issue.get('level'), 1) > LEVEL_RANK.get(before.get('level'), 1)
            if louder or issue.get('substatus') == 'escalating' and before.get('substatus') != 'escalating' or \
                    added >= WORSE_MIN_NEW and added >= WORSE_RATIO * max(1, int(before.get('count') or 0)):
                issue['change'] = 'Getting worse'
    return [i for i in issues if i['change']]


def next_state(issues: list[dict], state: dict, now: datetime, failure: str | None) -> dict:
    remembered = dict(state.get('issues', {}))
    for issue in issues:
        remembered[issue['id']] = {'count': issue['count'], 'level': issue.get('level'), 'status': issue.get('status'),
                                   'substatus': issue.get('substatus'), 'short_id': issue.get('short_id')}
    return {'issues': remembered, 'last_check': now.strftime('%Y-%m-%dT%H:%M:%SZ'), 'failing': failure}


# ---------- Writing ----------
def render(changed: list[dict], now: datetime, failure: str | None = None, recovered: bool = False, hours: int = 1) -> str:
    stamp = now.strftime('%Y-%m-%d %H:%M UTC')
    window = '60 minutes' if hours == 1 else f'{hours} hours (preview)'
    if failure:
        return (f'## ⚠️ Monitoring failure, {stamp}\n\n'
                f'The hourly check could **not** read Sentry: {failure}.\n\n'
                'This is **not** a report of zero errors. Errors from the last hour are unknown until the next check works.')
    lines = [f'## Amilya monitor, {stamp} (last {window})', '']
    if recovered:
        lines += ['✅ Sentry can be read again after the earlier monitoring failure.', '']
    for issue in changed:
        t = issue['tags']
        detail = ', '.join(f'{k} `{t[k]}`' for k in ('stage', 'code', 'http_status', 'route', 'kind', 'retries', 'recording_exists') if k in t)
        lines += [f"### {issue['change']}: [{issue['short_id']}]({issue['link']}), {issue['category']}, {issue['severity']} severity",
                  f"- Where: **{issue['where']}**, {issue['project']}",
                  f"- What: {issue['title']}" + (f' ({detail})' if detail else ''),
                  f"- Events: about {issue['last_hour']} in the last hour, {issue['count']} in total",
                  f"- First seen {issue['first_seen']}, last seen {issue['last_seen']}, status {issue['status']}"
                  + (f" ({issue['substatus']})" if issue.get('substatus') else ''), '']
    return '\n'.join(lines).rstrip()


def state_comment(state: dict) -> str:
    return f'<!-- {STATE_MARK} {json.dumps(state, separators=(",", ":"))} -->'


def read_state(text: str) -> dict:
    match = re.search(r'<!-- ' + STATE_MARK + r' (\{.*?\}) -->', text or '', re.S)
    try:
        return json.loads(match.group(1)) if match else {}
    except ValueError:
        return {}


def issue_body(state: dict, now: datetime) -> str:
    status = '⚠️ monitoring failing: ' + state['failing'] if state.get('failing') else 'working'
    return ('Hourly Sentry monitor for ChatGPT and the owner. Comments appear only for new, reopened or worsening problems, '
            'or when monitoring itself fails.\n\n'
            f'**Last checked:** {now.strftime("%Y-%m-%d %H:%M UTC")} ({status}). '
            f'**Problems tracked:** {len(state.get("issues", {}))}.\n\n'
            'Privacy: technical categories only; no patient details, notes, transcripts, audio, photos or credentials.\n\n'
            + state_comment(state))


class GitHub:
    def __init__(self, repo, token, api='https://api.github.com'):
        self.repo, self.token, self.api = repo, token, api

    def call(self, path, method='GET', body=None, **params):
        query = ('?' + urllib.parse.urlencode(params)) if params else ''
        return http_json(f'{self.api}/repos/{self.repo}{path}{query}', self.token, method, body)

    def monitor_issue(self):
        found = self.call('/issues', state='open', labels=LABEL, per_page=5)
        if found:
            return found[0]
        return self.call('/issues', 'POST', {'title': ISSUE_TITLE, 'labels': [LABEL], 'body': issue_body({}, datetime.now(timezone.utc))})


def run(now: datetime, token: str, state: dict, get=http_json, hours: int = 1):
    """Returns (comment or None, new state). Pure apart from the Sentry reads, so tests can drive it."""
    try:
        issues = fetch(token, now, get, hours)
    except SentryUnavailable as error:
        failure = str(error)
        first_failure = state.get('failing') != failure
        return (render([], now, failure) if first_failure else None), {**state, 'failing': failure,
                                                                        'last_check': now.strftime('%Y-%m-%dT%H:%M:%SZ')}
    changed = decide(issues, state)
    recovered = bool(state.get('failing'))
    comment = render(changed, now, recovered=recovered, hours=hours) if changed or recovered else None
    return comment, next_state(issues, state, now, None)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--dry-run', action='store_true', help='print only; never write to GitHub')
    parser.add_argument('--hours', type=int, default=1, choices=range(1, 169), help='preview window for a dry run (default 1)')
    args = parser.parse_args(argv)
    now = datetime.now(timezone.utc)
    repo, gh_token = os.environ.get('GITHUB_REPOSITORY'), os.environ.get('GITHUB_TOKEN')
    gh = GitHub(repo, gh_token) if repo and gh_token else None
    issue = None
    state = {}
    if gh:
        try:
            issue = gh.monitor_issue() if not args.dry_run else (gh.call('/issues', state='open', labels=LABEL, per_page=5) or [None])[0]
            state = read_state((issue or {}).get('body', ''))
        except urllib.error.URLError:
            state = {}
    if args.hours != 1 and not args.dry_run:
        parser.error('--hours is only for dry-run previews')
    comment, new_state = run(now, os.environ.get('SENTRY_AUTH_TOKEN', ''), state, hours=args.hours)
    print(comment or f'Nothing new, reopened or worse in the last 60 minutes ({now:%Y-%m-%d %H:%M UTC}).')
    if args.dry_run or not gh or not issue:
        return 0
    if comment:
        gh.call(f"/issues/{issue['number']}/comments", 'POST', {'body': comment})
    gh.call(f"/issues/{issue['number']}", 'PATCH', {'body': issue_body(new_state, now)})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
