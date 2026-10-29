"""Fixed non-patient comparison; never changes the public app's reasoning setting."""
from contextlib import contextmanager
import math
import os
import secrets
import time
from uuid import uuid4

MODEL = 'openai/gpt-6.1-sol'
PROFILES = ('high', 'medium')
CORE_FIELDS = {'wound_tissue', 'wound_edges', 'periwound'}


@contextmanager
def reasoning_profile(effort):
    from server import providers
    if effort not in PROFILES:
        raise ValueError('Unknown comparison profile')
    original = providers.configured_route
    def configured(task, content):
        route = original(task, content)
        if task == 'image':
            if route['model'] != MODEL:
                raise ValueError('Unexpected comparison model')
            return {**route, 'effort_level': effort}
        return route
    providers.configured_route = configured
    try:
        yield
    finally:
        providers.configured_route = original


def quality_gate(image, status, body):
    """Contract/coverage check, not a judgment of clinical equivalence."""
    from server import providers
    if status != 201 or not isinstance(body, dict):
        return False, 0
    facts = body.get('facts')
    quality = body.get('photo_quality', {})
    if not isinstance(facts, list):
        return False, 0
    if image == 'shapes':
        return not facts and quality.get('retake_required') is True, 0
    if image != 'wound' or quality.get('status') != 'sufficient' or quality.get('retake_required') is not False:
        return False, 0
    names = set()
    try:
        for fact in facts:
            if (fact['name'] not in providers.PHOTO_NAMES
                    or fact.get('confirmation_state') == 'confirmed'):
                return False, 0
            value = providers.validated_photo_value(fact['name'], fact['value'])
            if not isinstance(value.get('text'), str) or not value['text'].strip():
                return False, 0
            names.add(fact['name'])
    except (ValueError, KeyError, TypeError):
        return False, 0
    return CORE_FIELDS <= names, len(names)


def safe_metrics(rows, latency):
    """Only provider-reported cost and bounded numeric counters may leave the child."""
    def integer(value):
        return type(value) is int and 0 <= value <= 999999999
    costs = [row.actual_cost_micros for row in rows]
    cost = sum(costs) if costs and all(integer(x) for x in costs) else None
    if cost is not None and not integer(cost):
        cost = None
    counters = {}
    for key in ('input_tokens', 'output_tokens', 'cached_tokens'):
        values = [(row.usage or {}).get(key) for row in rows]
        total = sum(values) if values and all(integer(x) for x in values) else None
        counters[key] = total if total is not None and integer(total) else 'UNKNOWN'
    milliseconds = round(latency * 1000) if math.isfinite(latency) and latency >= 0 else 0
    return {'cost_micros': cost if cost is not None else 'UNKNOWN',
            'latency_ms': min(milliseconds, 9999999), **counters}


def primary_only(rows):
    """A backup model cannot be credited as a comparison of the same model."""
    return bool(rows) and all(row.model == MODEL and row.provider == 'openrouter' for row in rows)


def metric_line(profile, image, passed, fields, metrics):
    if profile not in PROFILES or image not in ('shapes', 'wound'):
        raise ValueError('Unknown fixed comparison input')
    parts = [f'AMILYA_PHOTO_COMPARE profile={profile} image={image}',
             'status=' + ('PASS' if passed else 'FAIL'),
             'fields=' + str(fields if type(fields) is int and 0 <= fields <= 4 else 0)]
    for key in ('cost_micros', 'latency_ms', 'input_tokens', 'output_tokens', 'cached_tokens'):
        value = metrics.get(key)
        parts.append(key + '=' + (str(value) if type(value) is int and 0 <= value <= 999999999 else 'UNKNOWN'))
    return ' '.join(parts)


def compare():
    from fastapi.testclient import TestClient
    from sqlalchemy import select
    from server.main import app
    from server.core import settings
    from server import billing
    from server.db import SessionLocal
    from server.identity import User
    from server.billing_models import UsageOperation
    from render_owner_activation import APPROVAL_END
    from render_synthetic_probe import synthetic_image, synthetic_wound
    deadline = int(os.environ.get('AMILYA_RENDER_OWNER_TEST_UNTIL', str(APPROVAL_END)))
    if not 0 < deadline <= APPROVAL_END:
        raise ValueError('Invalid activated test deadline')
    password = secrets.token_urlsafe(24)
    outcomes = []
    with TestClient(app, base_url='http://localhost') as client:
        signup = client.post('/api/v1/auth/signup', headers={'X-WCP-Client':'android'}, json={
            'email':'fixed-photo-comparison@example.test', 'password':password,
            'display_name':'Synthetic comparison fixture', 'workspace_name':'Disposable fixture',
            'accept_terms':True, 'terms_version':settings.legal_version, 'privacy_version':settings.legal_version})
        if signup.status_code != 201:
            raise ValueError('Fixture registration failed')
        token = signup.json()['csrf_token']
        if client.post('/api/v1/auth/logout', headers={'X-CSRF-Token':token}).status_code != 200:
            raise ValueError('Fixture logout failed')
        login = client.post('/api/v1/auth/login', headers={'X-WCP-Client':'android'},
                            json={'email':'fixed-photo-comparison@example.test','password':password})
        if login.status_code != 200 or 'wcp_refresh=' not in login.headers.get('set-cookie',''):
            raise ValueError('Fixture login failed')
        headers = {'X-CSRF-Token':login.json()['csrf_token'],'X-WCP-Client':'android'}
        with SessionLocal() as db:
            actor = db.scalar(select(User).where(User.email=='fixed-photo-comparison@example.test'))
            if actor.is_platform_admin:
                raise ValueError('Unexpected fixture role')
            account = billing._lock_account(db, actor.workspace_id)
            account.status, account.plan = 'active', 'essential'
            account.period_start, account.period_end = int(time.time())-60, int(time.time())+3600
            account.plan_micros = 1_000_000  # disposable SQLite fixture only
            db.commit()
        for profile in PROFILES:
            with reasoning_profile(profile):
                for image in ('shapes', 'wound'):
                    # Do not start a request near the existing hard approval boundary.
                    if time.time() + 90 >= deadline:
                        raise ValueError('Test window too short')
                    started = client.post('/api/v1/quick/start', headers=headers,
                        json={'mode':'photo','request_id':str(uuid4())})
                    if started.status_code != 201:
                        raise ValueError('Fixture encounter failed')
                    encounter = started.json()['encounter']['id']
                    data, mime = (synthetic_image(),'image/png') if image=='shapes' else (synthetic_wound(),'image/jpeg')
                    clock = time.perf_counter()
                    response = client.post('/api/v1/encounters/'+encounter+'/upload', headers=headers,
                        data={'kind':'photo'}, files={'file':('fixed-synthetic-image',data,mime)})
                    latency = time.perf_counter()-clock
                    try:
                        body = response.json()
                    except ValueError:
                        body = None
                    passed, fields = quality_gate(image, response.status_code, body)
                    with SessionLocal() as db:
                        rows = db.scalars(select(UsageOperation).where(UsageOperation.encounter_id==encounter,
                            UsageOperation.task=='image')).all()
                        metrics = safe_metrics(rows, latency)
                        passed = passed and primary_only(rows)
                    print(metric_line(profile,image,passed,fields,metrics),flush=True)
                    outcomes.append(passed)
    return len(outcomes)==4 and all(outcomes)

