"""Private, fixed-input provider check; never exposes an HTTP endpoint or patient data.

The launcher is still provider-disabled. Only this short-lived child process can
send its built-in shapes and fictional text. A durable claim prevents repeats on
Render restarts. No legal acceptance, login session, or paid account is created.
"""
from __future__ import annotations

import asyncio
import io
import os
from pathlib import Path
import secrets
import sys
import tempfile
import time

from render_test_start import runtime_environment, ConfigurationError

VERSION = 'photo-report-v1'
MARKER = '063dbda7-f51c-5d78-8102-342bd1ac74d6'
EXTRACTION_VERSION = 'extract-sol-v2'
HTTP_VERSION = 'native-http-v3'
MARKERS = {VERSION: MARKER, EXTRACTION_VERSION: 'bd2440cf-fb76-5294-aedf-dd7c031b3802',
           HTTP_VERSION: 'd98f0af3-886d-50bc-9adb-e02ab812ea02'}
TEXT = ('Fictional training visit, no real patient: the clinician removed the old '
        'dressing, cleansed the wound with normal saline, and applied a foam dressing. '
        'The fictional patient reported no pain during the dressing change.')
STAGES = ('photo', 'extract', 'report')


def claim_once(env):
    """Only a nonclinical marker is persisted in the isolated Render database."""
    import psycopg
    url = 'postgresql://' + env['DATABASE_URL'].split('://', 1)[1]
    with psycopg.connect(url, connect_timeout=10, options='-c statement_timeout=10000') as conn:
        with conn.cursor() as cursor:
            cursor.execute('INSERT INTO workspaces (id,name,audit_sequence,audit_head,created_at) '
                           'VALUES (%s,%s,0,%s,CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING RETURNING id',
                           (MARKERS[env['AMILYA_RENDER_SYNTHETIC_PROBE']],
                            'Synthetic provider probe ' + env['AMILYA_RENDER_SYNTHETIC_PROBE'] + ' claimed', '0' * 64))
            return cursor.fetchone() is not None


def synthetic_image():
    from PIL import Image, ImageDraw
    image = Image.new('RGB', (384, 384), 'white')
    draw = ImageDraw.Draw(image)
    draw.rectangle((32, 32, 160, 160), fill='blue')
    draw.ellipse((208, 208, 352, 352), fill='green')
    stream = io.BytesIO()
    image.save(stream, format='PNG')
    return stream.getvalue()


def error_code(exc):
    """Fixed codes only: never exception messages, provider bodies or values."""
    from fastapi import HTTPException
    if isinstance(exc, HTTPException):
        app_code = (exc.headers or {}).get('X-WCP-Error')
        if app_code in {'PROVIDER_QUOTA', 'PROVIDER_AUTH', 'MODEL_UNAVAILABLE',
                        'REQUEST_INCOMPATIBLE', 'PROVIDER_TIMEOUT', 'INVALID_RESPONSE',
                        'NETWORK_UNAVAILABLE', 'RATE_LIMITED', 'RESPONSE_PROCESSING_FAILED',
                        'OUTPUT_LIMIT', 'PROVIDER_UNAVAILABLE'}:
            return app_code
        code = (exc.headers or {}).get('X-WCP-Provider-Error')
        allowed = {'unknown', 'invalid_image', 'unsupported_image_format', 'timeout',
                   'content_policy_violation', 'refusal', 'context_length_exceeded',
                   'rate_limit_exceeded', 'invalid_request', 'server_error'}
        return code.upper() if code in allowed else 'HTTP_' + str(exc.status_code)
    return 'CHECK_FAILED'


async def check_workflows(actor, stages=STAGES):
    from server import providers
    results = {}
    for stage in stages:
        try:
            if stage == 'photo':
                result = await providers.analyze_media(synthetic_image(), 'image/png', 'photo',
                    user=actor, encounter_id='synthetic-photo')
                if result.get('facts') != [] or not result.get('quality', {}).get('retake_required'):
                    raise ValueError()
            elif stage == 'extract':
                result = await providers.extract_text(TEXT, 'typed', user=actor, encounter_id='synthetic-extract')
                if not result.get('facts'):
                    raise ValueError()
            else:
                context = {'case_facts': [], 'untrusted_sources': [
                    {'id': 'synthetic-source', 'kind': 'typed', 'text': TEXT}],
                    'rejected_claims_do_not_reinstate': []}
                result = await providers.quick_documentation(context, 'nursing', 'concise',
                    user=actor, encounter_id='synthetic-report')
                if not result.get('text', '').strip() or result.get('qa', {}).get('passed') is not True:
                    raise ValueError()
            results[stage] = 'PASS'
            print('AMILYA_SYNTHETIC_PROBE stage=' + stage + ' status=PASS', flush=True)
        except Exception as exc:
            results[stage] = 'FAIL'
            print('AMILYA_SYNTHETIC_PROBE stage=' + stage + ' status=FAIL code=' + error_code(exc), flush=True)
    return all(value == 'PASS' for value in results.values())


def check_http_workflows():
    """Exercise native login, multipart photo and checked report in disposable DB.

    No dependency/auth overrides; the normal routes, cookies, CSRF and real
    provider gateway are used. Only the fixture account receives a fixture balance.
    This is server HTTP integration, not Samsung hardware validation.
    """
    from uuid import uuid4
    from fastapi.testclient import TestClient
    from server.main import app
    from server.core import settings
    from server import billing
    from server.db import SessionLocal
    from server.identity import User
    from sqlalchemy import select
    password = secrets.token_urlsafe(24)
    results = []
    with TestClient(app, base_url='http://localhost') as client:
        signup = client.post('/api/v1/auth/signup', headers={'X-WCP-Client': 'android'}, json={
            'email': 'render-http-probe@example.test', 'password': password,
            'display_name': 'Synthetic HTTP fixture', 'workspace_name': 'Disposable fixture',
            'accept_terms': True, 'terms_version': settings.legal_version, 'privacy_version': settings.legal_version})
        if signup.status_code != 201:
            raise ValueError()
        token = signup.json()['csrf_token']
        if client.post('/api/v1/auth/logout', headers={'X-CSRF-Token': token}).status_code != 200:
            raise ValueError()
        login = client.post('/api/v1/auth/login', headers={'X-WCP-Client': 'android'},
            json={'email': 'render-http-probe@example.test', 'password': password})
        if login.status_code != 200 or 'wcp_refresh=' not in login.headers.get('set-cookie', ''):
            raise ValueError()
        headers = {'X-CSRF-Token': login.json()['csrf_token'], 'X-WCP-Client': 'android'}
        if client.get('/api/v1/auth/session').status_code != 200:
            raise ValueError()
        with SessionLocal() as db:
            actor = db.scalar(select(User).where(User.email == 'render-http-probe@example.test'))
            if actor.is_platform_admin:
                raise ValueError()
            account = billing._lock_account(db, actor.workspace_id)
            account.status, account.plan = 'active', 'essential'
            account.period_start, account.period_end = int(time.time()) - 60, int(time.time()) + 3600
            account.plan_micros = 1_000_000  # temporary fixture, never the Render account DB
            db.commit()
        for stage in STAGES:
            try:
                if stage == 'photo':
                    started = client.post('/api/v1/quick/start', headers=headers,
                        json={'mode': 'photo', 'request_id': str(uuid4())})
                    if started.status_code != 201: raise ValueError()
                    photo_id = started.json()['encounter']['id']
                    result = client.post('/api/v1/encounters/' + photo_id + '/upload', headers=headers,
                        data={'kind': 'photo'}, files={'file': ('synthetic.png', synthetic_image(), 'image/png')})
                    body = result.json()
                    if result.status_code != 201 or body.get('facts') != [] or not body.get('photo_quality', {}).get('retake_required'):
                        raise ValueError()
                elif stage == 'extract':
                    started = client.post('/api/v1/quick/start', headers=headers,
                        json={'mode': 'document', 'request_id': str(uuid4())})
                    if started.status_code != 201: raise ValueError()
                    report_id = started.json()['encounter']['id']
                    result = client.post('/api/v1/encounters/' + report_id + '/evidence', headers=headers,
                        json={'kind': 'typed', 'text': TEXT})
                    if result.status_code != 201 or not result.json().get('facts') or result.json().get('warnings'):
                        raise ValueError()
                else:
                    result = client.post('/api/v1/encounters/' + report_id + '/quick-report', headers=headers,
                        json={'template': 'nursing', 'preferred_length': 'concise', 'use_ai': True})
                    body = result.json()
                    if result.status_code != 200 or not body.get('text', '').strip() or body.get('qa', {}).get('passed') is not True or body.get('recovery'):
                        raise ValueError()
                results.append(True)
                print('AMILYA_SYNTHETIC_PROBE stage=' + stage + ' status=PASS', flush=True)
            except Exception:
                results.append(False)
                print('AMILYA_SYNTHETIC_PROBE stage=' + stage + ' status=FAIL code=HTTP_CHECK_FAILED', flush=True)
    return all(results)


def main():
    try:
        env = runtime_environment(os.environ)
        version = env.get('AMILYA_RENDER_SYNTHETIC_PROBE')
        if version not in MARKERS:
            raise ConfigurationError('PROBE_OPT_IN_REQUIRED')
        if not env.get('OPENROUTER_API_KEY') or env.get('OPENROUTER_BASE_URL', 'https://openrouter.ai/api/v1') != 'https://openrouter.ai/api/v1':
            raise ConfigurationError('PROBE_PROVIDER_REQUIRED')
        if version == HTTP_VERSION:
            from render_owner_activation import APPROVAL_END, check_key
            now = time.time()
            if now >= APPROVAL_END:
                raise ConfigurationError('TEST_WINDOW_CLOSED')
            check_key(env, APPROVAL_END, now)
        if not claim_once(env):
            print('AMILYA_SYNTHETIC_PROBE status=ALREADY_CLAIMED', flush=True)
            return 0
        with tempfile.TemporaryDirectory(prefix='amilya-synthetic-') as directory:
            # The real provider gateway and validators run with a temporary local
            # fixture ledger. The web process and durable DB retain their guards.
            os.environ.update(DATABASE_URL='sqlite:///' + directory + '/probe.sqlite',
                ENVIRONMENT='development', PROVIDER_EXECUTION_ENABLED='true',
                APP_SECRET=secrets.token_hex(32), UNLIMITED_CREDIT_USER_IDS='',
                ALLOW_SIGNUP='true' if version == HTTP_VERSION else 'false',
                PUBLIC_BASE_URL='http://localhost' if version == HTTP_VERSION else env['PUBLIC_BASE_URL'],
                SECURE_COOKIES='false' if version == HTTP_VERSION else 'true',
                OPENROUTER_REQUIRE_ZDR='true', PROVIDER_TIMEOUT_SECONDS='45',
                MODEL_EXTRACTION='openai/gpt-6.1-sol' if version in (EXTRACTION_VERSION, HTTP_VERSION) else 'openai/gpt-6-luna',
                MODEL_VISION='openai/gpt-6.1-sol',
                MODEL_DOCUMENTATION='openai/gpt-6.1-sol', MODEL_FRONTIER='openai/gpt-6-astra')
            sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
            from server import main as app_module  # register the unchanged model metadata; no server is started
            from server import billing
            from server.db import Base, engine, SessionLocal
            from server.identity import User, Workspace
            Base.metadata.create_all(engine)
            if version == HTTP_VERSION:
                try:
                    return 0 if check_http_workflows() else 1
                finally:
                    engine.dispose()
            with SessionLocal() as db:
                workspace = Workspace(name='Disposable synthetic probe')
                db.add(workspace)
                db.flush()
                actor = User(email='render-probe@example.test', display_name='Synthetic probe',
                    password_hash='disabled-no-login', is_active=False, is_platform_admin=False,
                    workspace_id=workspace.id)
                db.add(actor)
                db.flush()
                account = billing._lock_account(db, workspace.id)
                account.status, account.plan = 'active', 'essential'
                account.period_start, account.period_end = int(time.time()) - 60, int(time.time()) + 3600
                account.plan_micros = 1_000_000  # fixture balance only; no payment or subscription
                db.commit()
            try:
                passed = asyncio.run(check_workflows(actor, ('extract',) if version == EXTRACTION_VERSION else STAGES))
            finally:
                engine.dispose()
            return 0 if passed else 1
    except Exception:
        print('AMILYA_SYNTHETIC_PROBE status=BLOCKED', flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
