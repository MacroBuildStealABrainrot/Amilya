"""Report recovery over a REAL network connection (local uvicorn server + real sockets).

Fake data, fake AI provider and fake billing only. No Render, Google Cloud or paid service is contacted.
These replace the earlier throwaway diagnostic. They prove, through the real HTTP stack, that an interrupted report
keeps the same request number, never duplicates a finished report, never charges twice, and never replaces a failed report with the raw transcript
(the recording and transcript stay saved; the phone offers Try again).

Limit: the test app has the report route and the request-number middleware, but not the full production middleware
or Render's network layer in front of it.
"""
import asyncio
import socket
import threading
import time

from fastapi import FastAPI
import httpx
import pytest
import uvicorn

from server import providers, quick_workflows as quick
from server.clinical_models import Encounter, Evidence, Patient
from server.db import get_db
from server.identity import current_user, require_csrf
from test_providers import completion, gateway  # noqa: F401  (fixture)
from test_report_cancel_recovery import assert_balanced, cancelled, snapshot

SOURCE = 'Right heel. Cleansed with NS. Dressed with Mepilex.'
DRAFT = completion({'text': 'DRAFT — CLINICIAN REVIEW REQUIRED\n' + SOURCE})
CHECK = completion({'passed': True, 'discrepancies': []})
PATH = '/api/v1/encounters/enc1/quick-report'


class RealServer:
    def __init__(self, gateway, monkeypatch):
        self.gateway = gateway
        app = FastAPI()
        app.include_router(quick.router, prefix='/api/v1')

        def database():
            with gateway.sessions() as db:
                yield db
        app.dependency_overrides[get_db] = database
        app.dependency_overrides[current_user] = lambda: gateway.user
        app.dependency_overrides[require_csrf] = lambda: None
        monkeypatch.setattr(quick, 'check_rate', lambda *args, **kwargs: None)

        @app.middleware('http')
        async def request_identity(request, call_next):
            token = providers.request_operation.set(request.headers['Idempotency-Key'])
            try:
                return await call_next(request)
            finally:
                providers.request_operation.reset(token)
        with gateway.sessions() as db:
            db.add(Patient(id='p1', workspace_id=gateway.user.workspace_id, display_name='Synthetic case'))
            db.flush()
            db.add(Encounter(id='enc1', patient_id='p1', workspace_id=gateway.user.workspace_id,
                             title='Synthetic visit', created_by=gateway.user.id))
            db.flush()
            db.add(Evidence(id='src1', workspace_id=gateway.user.workspace_id, encounter_id='enc1', kind='typed',
                            text=SOURCE, created_by=gateway.user.id, request_hash='synthetic-hash'))
            db.commit()
        probe = socket.socket()
        probe.bind(('127.0.0.1', 0))
        self.port = probe.getsockname()[1]
        probe.close()
        self.server = uvicorn.Server(uvicorn.Config(app, host='127.0.0.1', port=self.port, log_level='critical'))
        self.thread = threading.Thread(target=self.server.run, daemon=True)
        self.thread.start()
        for _ in range(100):
            if self.server.started:
                break
            time.sleep(0.1)
        assert self.server.started, 'local test server did not start'

    def post(self, key):
        return httpx.post(f'http://127.0.0.1:{self.port}{PATH}', json={}, headers={'Idempotency-Key': key}, timeout=30)

    def post_and_drop(self, key):
        """Send the request, then close the connection without reading the answer (a phone losing its signal)."""
        raw = socket.create_connection(('127.0.0.1', self.port))
        raw.sendall(('POST ' + PATH + ' HTTP/1.1\r\nHost: x\r\nIdempotency-Key: ' + key +
                     '\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}').encode())
        return raw

    def stop(self):
        self.server.should_exit = True
        self.thread.join(10)


@pytest.fixture
def real_server(gateway, monkeypatch):
    server = RealServer(gateway, monkeypatch)
    yield server
    server.stop()


def wait_until(condition, seconds=15, what='condition'):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if condition():
            return
        time.sleep(0.1)
    raise AssertionError('timed out waiting for ' + what)


def try_post(server, key):
    """A request the server cancels may end as an error response or a dropped connection; both are acceptable."""
    try:
        return server.post(key).status_code
    except httpx.HTTPError:
        return 'connection-closed'


def test_dropped_connection_late_worker_finishes_and_retry_replays_without_duplicates(real_server, gateway):
    started, finish = threading.Event(), threading.Event()

    async def slow_draft(_):
        started.set()
        assert await asyncio.to_thread(finish.wait, 20), 'synthetic worker was not released'
        return DRAFT
    gateway.http.responses = [slow_draft, CHECK]
    raw = real_server.post_and_drop('phone-request-1')
    assert started.wait(15), 'the report never started'
    raw.close()  # the phone's connection ended while the AI was still writing
    time.sleep(0.5)
    # Trying again while the first worker may still be running must not start a second report or charge.
    overlapping = real_server.post('phone-request-1')
    assert overlapping.status_code == 409 and providers.REPORT_STATE_HEADER not in overlapping.headers
    running = snapshot(gateway)
    assert running['calls'] == 1 and running['refunds'] == [], 'an overlapping retry must not call the AI or refund'
    finish.set()  # the original worker finishes late
    wait_until(lambda: snapshot(gateway)['results'] == 2 and snapshot(gateway)['reserved'] == 0,
               what='the late worker to store both finished steps')
    done = snapshot(gateway)
    assert done['calls'] == 2 and done['refunds'] == []
    first = real_server.post('phone-request-1')
    assert first.status_code == 200 and first.json()['qa']['passed'] is True and 'Right heel' in first.json()['text']
    again = real_server.post('phone-request-1')
    assert again.status_code == 200 and again.json()['text'] == first.json()['text']
    final = snapshot(gateway)
    assert final == done, 'replaying a finished report must make no new AI call, charge or refund'
    assert_balanced(final)


def test_server_cancellation_over_real_connection_then_same_request_completes(real_server, gateway):
    gateway.http.responses = [cancelled]
    first = try_post(real_server, 'phone-request-2')
    assert first != 200, 'a cancelled report must not look like a finished one'
    after_cancel = snapshot(gateway)
    assert after_cancel['results'] == 0 and after_cancel['refunds'] == []
    gateway.http.responses = [DRAFT, CHECK]
    retry = real_server.post('phone-request-2')
    assert retry.status_code == 200 and retry.json()['qa']['passed'] is True and 'recovery' not in retry.json()
    done = snapshot(gateway)
    assert done['calls'] == 3 and done['results'] == 2 and done['refunds'] == []
    assert_balanced(done)
    replay = real_server.post('phone-request-2')
    assert replay.status_code == 200 and replay.json()['text'] == retry.json()['text']
    assert snapshot(gateway) == done


def test_cancelled_after_first_step_reuses_the_saved_draft_over_real_connection(real_server, gateway):
    gateway.http.responses = [DRAFT, cancelled]
    assert try_post(real_server, 'phone-request-3') != 200
    mid = snapshot(gateway)
    assert mid['results'] == 1 and mid['calls'] == 2
    gateway.http.responses = [CHECK]
    retry = real_server.post('phone-request-3')
    assert retry.status_code == 200 and retry.json()['qa']['passed'] is True
    done = snapshot(gateway)
    assert done['calls'] == 3, 'the saved draft must be reused, not written again'
    assert done['results'] == 2 and done['refunds'] == []
    assert_balanced(done)


def test_three_cancellations_end_without_a_raw_transcript_report_and_no_fourth_ai_call(real_server, gateway):
    for number in range(3):
        gateway.http.responses = [cancelled]
        assert try_post(real_server, 'phone-request-4') != 200, f'attempt {number + 1} should not finish'
    exhausted = snapshot(gateway)
    assert exhausted['calls'] == 3 and exhausted['results'] == 0
    gateway.http.responses = []
    final = real_server.post('phone-request-4')
    assert final.status_code != 200, 'a confirmed final failure must not hand back the raw transcript as a report'
    assert 'Right heel' not in final.text and 'source_only' not in final.text
    state = snapshot(gateway)
    assert state['calls'] == 3, 'no fourth AI request after the attempt limit'
    assert state['reserved'] == 0 and state['charged'] == 0
