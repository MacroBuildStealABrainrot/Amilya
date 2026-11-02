"""THROWAWAY (never merged). Fake data/provider/billing only.
Real uvicorn server + real TCP socket that is closed mid-report, then a same-request-number retry.
Fails by design at the end so the observed facts appear in the CI annotation."""
import asyncio, json, socket, threading, time

from fastapi import FastAPI
from fastapi.testclient import TestClient  # noqa: F401
import httpx
import pytest
from sqlalchemy import select
import uvicorn

from server import providers, quick_workflows as quick
from server.billing_models import UsageOperation
from server.clinical_models import Encounter, Evidence, Patient
from server.db import get_db
from server.identity import current_user, require_csrf
from server.provider_models import ProviderResult
from test_providers import completion, gateway  # noqa: F401

DRAFT = completion({'text': 'DRAFT — CLINICIAN REVIEW REQUIRED\nRight heel. Cleansed with NS. Dressed with Mepilex.'})
CHECK = completion({'passed': True, 'discrepancies': []})


def ops(gateway):
    with gateway.sessions() as db:
        return sorted((o.task, o.status, o.release_reason or '', o.charged_micros or 0) for o in db.scalars(select(UsageOperation))), \
               len(list(db.scalars(select(ProviderResult))))


def test_real_socket_drop_then_retry(gateway, monkeypatch):
    app = FastAPI(); app.include_router(quick.router, prefix='/api/v1')
    def database():
        with gateway.sessions() as db: yield db
    app.dependency_overrides[get_db] = database
    app.dependency_overrides[current_user] = lambda: gateway.user
    app.dependency_overrides[require_csrf] = lambda: None
    monkeypatch.setattr(quick, 'check_rate', lambda *a, **k: None)
    @app.middleware('http')
    async def request_identity(request, call_next):
        token = providers.request_operation.set(request.headers['Idempotency-Key'])
        try: return await call_next(request)
        finally: providers.request_operation.reset(token)
    with gateway.sessions() as db:
        db.add(Patient(id='p1', workspace_id=gateway.user.workspace_id, display_name='Synthetic')); db.flush()
        db.add(Encounter(id='enc1', patient_id='p1', workspace_id=gateway.user.workspace_id, title='Synthetic visit', created_by=gateway.user.id)); db.flush()
        db.add(Evidence(id='src1', workspace_id=gateway.user.workspace_id, encounter_id='enc1', kind='typed',
            text='Right heel. Cleansed with NS. Dressed with Mepilex.', created_by=gateway.user.id, request_hash='h')); db.commit()

    started, release = threading.Event(), threading.Event()
    async def slow(_):
        started.set()
        for _i in range(300):          # up to ~30 s, waits only if not cancelled
            if release.is_set(): break
            await asyncio.sleep(0.1)
        return DRAFT
    gateway.http.responses = [slow]

    sock_probe = socket.socket(); sock_probe.bind(('127.0.0.1', 0)); port = sock_probe.getsockname()[1]; sock_probe.close()
    server = uvicorn.Server(uvicorn.Config(app, host='127.0.0.1', port=port, log_level='warning'))
    thread = threading.Thread(target=server.run, daemon=True); thread.start()
    for _ in range(100):
        if server.started: break
        time.sleep(0.1)
    facts = {}
    try:
        release.set(); gateway.http.responses = [DRAFT, CHECK]
        c = httpx.post(f'http://127.0.0.1:{port}/api/v1/encounters/enc1/quick-report', json={}, headers={'Idempotency-Key': 'ctl'}, timeout=30)
        facts['ctl'] = c.status_code; facts['ctlbody'] = c.text[:80]
        ctl_ops = ops(gateway)
        release.clear(); started.clear(); gateway.http.responses = [slow]
        body = b'{}'
        raw = socket.create_connection(('127.0.0.1', port))
        raw.sendall(b'POST /api/v1/encounters/enc1/quick-report HTTP/1.1\r\nHost: x\r\nIdempotency-Key: real-drop-1\r\n'
                    b'Content-Type: application/json\r\nContent-Length: 2\r\n\r\n' + body)
        facts['provider_call_started'] = started.wait(15)
        raw.close()                                      # the "connection dropped"
        t0 = time.time(); seen = None
        while time.time() - t0 < 12:
            state = ops(gateway)[0]
            if any(s == 'released' for _t, s, _r, _c in state): seen = state; break
            time.sleep(0.25)
        facts['server_cancelled_after_drop'] = seen is not None
        facts['ops_after_drop'] = ops(gateway)
        if seen is None:
            release.set(); time.sleep(1.0)               # worker was NOT cancelled: let it finish late
            facts['ops_after_late_finish'] = ops(gateway)
        gateway.http.responses = [DRAFT, CHECK] if seen is not None else [CHECK, DRAFT, CHECK]
        results = []
        for _ in range(3):
            r = httpx.post(f'http://127.0.0.1:{port}/api/v1/encounters/enc1/quick-report', json={},
                           headers={'Idempotency-Key': 'real-drop-1'}, timeout=30)
            results.append(r.status_code)
            if r.status_code == 200: break
        facts['retry_statuses'] = results
        facts['final'] = ops(gateway)
        facts['ai_calls'] = len(gateway.http.calls)
    finally:
        release.set(); server.should_exit = True; thread.join(10)
    short = {'ctl': facts.get('ctl'), 'cb': facts.get('ctlbody'), 'st': facts.get('provider_call_started'), 'cx': facts.get('server_cancelled_after_drop'),
         'rt': facts.get('retry_statuses'), 'calls': facts.get('ai_calls')}
    assert False, 'OBS1 ' + json.dumps(short)

