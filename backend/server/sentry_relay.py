"""Privacy-safe error reporting to Sentry, without the Sentry SDK.

WHAT IS SENT: only closed technical categories (component, workflow stage, error code, HTTP status,
route TEMPLATE such as /api/v1/quick/{encounter_id}/audio-parts, model id, app version), and for an
unexpected server exception its class name plus code locations (module, function, line number).

WHAT IS NEVER SENT: exception messages, local variables, request or response bodies, real URLs or
query strings, headers, cookies, user/workspace/patient identifiers, IP addresses, transcripts,
notes, photos, recordings or any free text. Every value is checked against a fixed list or a strict
character pattern; anything else becomes 'other'.

WHEN IT IS ON: only when a DSN is configured AND this process is the isolated Render test server.
The live server never sends, even with a DSN, until a separately approved switch is added.
Reporting can never break or slow a clinical request: it is queued to one background thread,
rate-limited, and every failure is swallowed.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import traceback
import uuid
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from urllib.parse import urlsplit

_SAFE = re.compile(r'^[A-Za-z0-9_.:/{}\-]{1,100}$')
_DSN_ENV = {'backend': 'AMILYA_SENTRY_BACKEND_DSN', 'android': 'AMILYA_SENTRY_ANDROID_DSN'}
_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix='amilya-sentry')
_lock = threading.Lock()
_recent: deque = deque()          # send times, all events
_per_group: dict = {}             # fingerprint -> deque of send times
MAX_PER_MINUTE = 30
MAX_PER_GROUP_PER_MINUTE = 5
sent_count = 0
dropped_count = 0


def safe(value, default='other'):
    if value is None:
        return None
    text = str(value)
    return text if _SAFE.match(text) else default


def parse_dsn(dsn: str):
    """Return (envelope endpoint, public key) or None. The DSN key only allows SENDING events."""
    try:
        parts = urlsplit(dsn)
    except ValueError:
        return None
    project = parts.path.strip('/')
    if parts.scheme != 'https' or not parts.hostname or not parts.username or not project.isdigit():
        return None
    if not re.fullmatch(r'[A-Za-z0-9]{16,64}', parts.username):
        return None
    return f'https://{parts.hostname}/api/{project}/envelope/', parts.username


def enabled(component: str = 'backend') -> bool:
    from . import isolation
    dsn = os.environ.get(_DSN_ENV.get(component, ''), '')
    return bool(dsn) and parse_dsn(dsn) is not None and isolation.is_isolated_test_server()


def environment() -> str:
    from . import isolation
    return 'isolated-test' if isolation.is_isolated_test_server() else 'unapproved'


def _allowed(fingerprint: tuple) -> bool:
    global dropped_count
    now = time.monotonic()
    with _lock:
        while _recent and now - _recent[0] > 60:
            _recent.popleft()
        group = _per_group.setdefault(fingerprint, deque())
        while group and now - group[0] > 60:
            group.popleft()
        if len(_recent) >= MAX_PER_MINUTE or len(group) >= MAX_PER_GROUP_PER_MINUTE:
            dropped_count += 1
            return False
        _recent.append(now)
        group.append(now)
        if len(_per_group) > 500:
            _per_group.clear()
        return True


def _code_frames(tb, limit=30):
    """Code locations only: module, function and line. No source lines, no variables."""
    frames = []
    for summary in traceback.extract_tb(tb)[-limit:]:
        path = summary.filename.replace('\\', '/')
        module = 'other'
        if '/server/' in path:
            module = 'server.' + path.split('/server/', 1)[1].removesuffix('.py').replace('/', '.')
        elif '/site-packages/' in path:
            module = path.split('/site-packages/', 1)[1].removesuffix('.py').replace('/', '.')
        frames.append({'module': safe(module), 'function': safe(summary.name),
                       'lineno': summary.lineno if isinstance(summary.lineno, int) else 0,
                       'in_app': module.startswith('server.')})
    return frames


def build_event(component, *, stage, code, level='error', http_status=None, route=None, model=None,
                release=None, extra_tags=None, exception=None, title=None):
    """Build the full Sentry event. Pure function, so tests can inspect exactly what would leave."""
    tags = {'component': component, 'stage': safe(stage), 'code': safe(code)}
    if http_status is not None:
        tags['http_status'] = str(int(http_status)) if isinstance(http_status, int) and 100 <= http_status <= 599 else 'other'
    if route:
        tags['route'] = safe(route)
    if model:
        tags['model'] = safe(model)
    for key, value in (extra_tags or {}).items():
        if _SAFE.match(str(key)) and value is not None:
            tags[str(key)] = safe(value)
    release = release if release and re.fullmatch(r'amilya-(backend|android)@[A-Za-z0-9_.\-]{1,40}', release) else None
    event = {
        'event_id': uuid.uuid4().hex,
        'timestamp': datetime.now(timezone.utc).isoformat(),
        'platform': 'python' if component == 'backend' else 'java',
        'level': level if level in {'fatal', 'error', 'warning', 'info'} else 'error',
        'logger': 'amilya.' + component,
        'environment': environment(),
        'tags': tags,
        'server_name': 'amilya-' + component,
        'sdk': {'name': 'amilya.relay', 'version': '1.0'},
    }
    if release:
        event['release'] = release
    if exception is not None:
        exc_type = safe(type(exception).__name__, 'Exception')
        frames = _code_frames(exception.__traceback__)
        top = next((f for f in reversed(frames) if f['in_app']), frames[-1] if frames else None)
        event['exception'] = {'values': [{'type': exc_type, 'value': 'details withheld for privacy',
                                          'stacktrace': {'frames': frames}}]}
        event['fingerprint'] = [component, 'exception', exc_type,
                                (top['module'] + ':' + top['function']) if top else 'no-frame']
    else:
        name = safe(title) if title else f'{tags["stage"]} {tags["code"]}'
        event['message'] = {'formatted': f'Amilya {component}: {name}'}
        event['fingerprint'] = [component, tags['stage'], tags['code'], tags.get('http_status', '-'),
                                tags.get('route', '-')]
    return event


def _envelope(event, dsn):
    header = {'event_id': event['event_id'], 'sent_at': event['timestamp']}
    body = json.dumps(event, separators=(',', ':'))
    return (json.dumps(header) + '\n' + json.dumps({'type': 'event', 'length': len(body.encode('utf-8'))})
            + '\n' + body + '\n').encode('utf-8')


def _post(event, dsn):
    global sent_count
    try:
        import httpx
        endpoint, key = parse_dsn(dsn)
        response = httpx.post(endpoint, content=_envelope(event, dsn), timeout=4.0, headers={
            'Content-Type': 'application/x-sentry-envelope',
            'X-Sentry-Auth': f'Sentry sentry_version=7, sentry_key={key}, sentry_client=amilya.relay/1.0'})
        if response.status_code < 300:
            sent_count += 1
    except Exception:
        pass  # Monitoring must never affect the app.


def send(component, **fields) -> bool:
    """Queue one event. Returns True only when it was queued (enabled, valid and within rate limits)."""
    try:
        if not enabled(component):
            return False
        event = build_event(component, **fields)
        if not _allowed(tuple(event['fingerprint'])):
            return False
        dsn = os.environ[_DSN_ENV[component]]
        _pool.submit(_post, event, dsn)
        return True
    except Exception:
        return False


def release() -> str:
    commit = os.environ.get('RENDER_GIT_COMMIT', '')
    return 'amilya-backend@' + (commit[:12] if re.fullmatch(r'[0-9a-f]{7,40}', commit) else 'unknown')


# ---------- the points the server reports from ----------

MONITORED_STATUSES = {409, 429, 500, 502, 503, 504}


def server_exception(exc, route):
    return send('backend', stage='server', code='unexpected_exception', level='error', route=route,
         release=release(), exception=exc)


def http_status(status, route, wcp_error=None, task=None):
    if status not in MONITORED_STATUSES:
        return False
    return send('backend', stage=task or 'request', code=wcp_error or f'http_{status}',
                level='warning' if status in {409, 429} else 'error', http_status=status, route=route,
                release=release())


def provider_failure(record):
    """record is diagnostics.cloud_event's closed, already-validated dictionary."""
    if record.get('status') != 'failed':
        return False
    return send('backend', stage=record.get('stage'), code=record.get('error_category') or record.get('error_code') or 'failed',
                level='warning', http_status=record.get('http_status'), model=record.get('selected_model'),
                release=release(), extra_tags={'finish_reason': record.get('finish_reason'),
                                               'provider': record.get('provider'), 'kind': 'ai_provider'})


SERVER_STAGE_ALERTS = {'photo_fallback': ('ai_fallback_used', 'warning'),
                       'photo_schema_recovery': ('ai_response_invalid_retried', 'warning')}


def server_stage(data):
    """data is a closed technical diagnostics.event dictionary (source 'server')."""
    stage = data.get('stage')
    if not isinstance(stage, str) or stage.endswith('_result'):
        return False  # provider outcomes are already reported by provider_failure
    if stage in SERVER_STAGE_ALERTS:
        if data.get('status') in {'pending', 'running'}:
            return False  # report once, when the fallback or recovery has finished
        code, level = SERVER_STAGE_ALERTS[stage]
        return send('backend', stage=stage, code=code, level=level, release=release(), extra_tags={'kind': 'fallback'})
    if data.get('status') != 'failed':
        return False
    if stage.startswith('source_faithfulness_'):
        count = data.get('defect_count')
        return send('backend', stage=stage, code='report_check_failed', level='warning', release=release(),
                    extra_tags={'kind': 'clinical_check',
                                'defects': str(count) if isinstance(count, int) and 0 <= count < 1000 else None})
    return send('backend', stage=stage, code=data.get('error_code') or 'failed', level='warning', release=release())


ANDROID_LEVELS = {'app_crash': 'fatal', 'app_not_responding': 'error', 'app_restart': 'warning',
                  'monitoring_test': 'error'}
# Device network failures are warnings: the app retries and the server usually reports the same failure itself.


def client_event(item):
    """item is a validated diagnostics.ClientEvent from the phone. Only failures and crashes are reported."""
    stage = item.stage
    if item.status != 'failed' and stage not in ANDROID_LEVELS:
        return False
    tags = {'flow_type': item.flow_type, 'wear_error': item.wear_error, 'storage': item.storage,
            'recording_exists': None if item.recording_exists is None else str(item.recording_exists).lower(),
            'retries': '0' if item.retry_count == 0 else '1-2' if item.retry_count <= 2 else '3+',
            'exit_reason': getattr(item, 'exit_reason', None), 'kind': 'device'}
    crash_type = getattr(item, 'crash_type', None)
    frames = getattr(item, 'crash_frames', None) or []
    app_version = getattr(item, 'app_version', None)
    fields = dict(stage=stage, code=crash_type or getattr(item, 'failure_code', None) or item.status, level=ANDROID_LEVELS.get(stage, 'warning'),
                  http_status=getattr(item, 'http_status', None),
                  release=('amilya-android@' + app_version) if app_version else None, extra_tags=tags)
    if crash_type or frames:
        event_fields = dict(fields)
        return _send_android_crash(event_fields, crash_type or 'Crash', frames)
    return send('android', **fields)


def _send_android_crash(fields, crash_type, frames):
    try:
        if not enabled('android'):
            return False
        event = build_event('android', **fields)
        parsed = []
        for frame in frames[:30]:
            where, _, line = frame.rpartition(':')
            module, _, function = where.rpartition('.')
            parsed.append({'module': safe(module), 'function': safe(function),
                           'lineno': int(line) if line.isdigit() else 0,
                           'in_app': module.startswith('com.woundcopilot')})
        parsed.reverse()  # Sentry lists the crashing frame last
        event.pop('message', None)
        event['exception'] = {'values': [{'type': safe(crash_type, 'Crash'), 'value': 'details withheld for privacy',
                                          'stacktrace': {'frames': parsed}}]}
        top = next((f for f in reversed(parsed) if f['in_app']), None)
        event['fingerprint'] = ['android', fields['stage'], safe(crash_type, 'Crash'),
                                (top['module'] + ':' + top['function']) if top else 'no-frame']
        if not _allowed(tuple(event['fingerprint'])):
            return False
        _pool.submit(_post, event, os.environ[_DSN_ENV['android']])
        return True
    except Exception:
        return False


class MonitoringSelfTest(Exception):
    """Raised on purpose to prove reporting works. Contains no data."""


def self_test():
    """Controlled test, used only on the isolated test server when AMILYA_SENTRY_SELF_TEST=true.
    Sends the same backend test exception twice (to prove grouping) and two Android-path test events."""
    def raise_test():
        raise MonitoringSelfTest()
    results = []
    for _ in range(2):
        try:
            raise_test()
        except MonitoringSelfTest as exc:
            results.append(send('backend', stage='monitoring_test', code='controlled_test_exception',
                                level='error', route='/self-test', release=release(), exception=exc))
    for _ in range(2):
        results.append(_send_android_crash(
            dict(stage='monitoring_test', code='ControlledTestCrash', level='error', release='amilya-android@server-self-test',
                 extra_tags={'kind': 'device', 'storage': 'unknown', 'retries': '0'}),
            'ControlledTestCrash', ['com.woundcopilot.android.DeveloperDiagnostics.testCrash:1']))
    return results
