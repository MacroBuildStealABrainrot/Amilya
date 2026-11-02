"""Cancelled report stages: real gateway/accounting, fake provider and money only."""
import asyncio
import json
import os
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import threading
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import sessionmaker
from types import SimpleNamespace
import httpx

from server import billing, providers
from server.billing_models import BillingAccount, CreditLedger, UsageOperation
from server.provider_models import ProviderResult
from server.db import Base
from server.identity import User, Workspace
from server.clinical_models import Encounter, Evidence, Patient
from server import quick_workflows as quick
from server.db import get_db
from server.identity import current_user, require_csrf
from test_providers import completion, gateway  # noqa: F401


CONTEXT = {'case_facts': [], 'untrusted_sources': [
    {'kind': 'typed', 'text': 'Right heel. Cleansed with NS. Dressed with Mepilex.'}],
    'rejected_claims_do_not_reinstate': []}
DRAFT = completion({'text': 'DRAFT — CLINICIAN REVIEW REQUIRED\nRight heel. Cleansed with NS. Dressed with Mepilex.'})
CHECK = completion({'passed': True, 'discrepancies': []})


@pytest.fixture(params=['sqlite', 'postgresql'])
def concurrent_gateway(request, gateway, monkeypatch):
    if request.param == 'sqlite':
        yield gateway
        return
    url = os.environ.get('WCP_TEST_POSTGRES_URL')
    if not url:
        if os.environ.get('GITHUB_ACTIONS') == 'true':
            pytest.fail('The dedicated CI Postgres service is required for this proof')
        pytest.skip('Dedicated CI Postgres is not available locally')
    target = make_url(url)
    # Never connect to Render, Google Cloud, a remote database, or an arbitrary
    # local user's database. This is the existing throwaway workflow service.
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert target.host in ('127.0.0.1', 'localhost') and target.port == 55432
    assert target.database == 'wcp_test' and target.username == 'wcp'
    schema = 'report_cancel_' + uuid4().hex
    admin = create_engine(url)
    engine = create_engine(url, connect_args={'options': '-c search_path=' + schema})
    sessions = sessionmaker(engine, expire_on_commit=False, autoflush=False)
    try:
        with admin.begin() as conn:
            conn.execute(text('CREATE SCHEMA ' + schema))
        Base.metadata.create_all(engine)
        with gateway.sessions() as source, sessions() as destination:
            for model in (Workspace, User, BillingAccount):
                for row in source.scalars(select(model)):
                    destination.add(model(**{column.key: getattr(row, column.key)
                        for column in model.__table__.columns}))
                destination.flush()
            destination.commit()
        monkeypatch.setattr(providers, 'SessionLocal', sessions)
        gateway.sessions = sessions
        gateway.engine = engine
        yield gateway
    finally:
        engine.dispose()
        with admin.begin() as conn:
            conn.execute(text('DROP SCHEMA IF EXISTS ' + schema + ' CASCADE'))
        admin.dispose()


async def write_report(gateway):
    return await providers.quick_documentation(CONTEXT, 'nursing', 'concise',
        user=gateway.user, encounter_id='cancelled-report')


def report(gateway):
    return asyncio.run(write_report(gateway))


def cancelled(_):
    raise asyncio.CancelledError()


def snapshot(gateway):
    with gateway.sessions() as db:
        ops = list(db.scalars(select(UsageOperation)))
        account = db.get(BillingAccount, gateway.user.workspace_id)
        ledger = list(db.scalars(select(CreditLedger)))
        return {'calls': len(gateway.http.calls), 'results': len(list(db.scalars(select(ProviderResult)))),
            'charged': sum(op.charged_micros for op in ops), 'reserved': account.reserved_micros,
            'balance': account.plan_micros + account.topup_micros, 'ledger_count': len(ledger),
            'refunds': [(row.event_key, row.delta_micros) for row in ledger if row.kind == 'workflow_refund'],
            'operations': [(op.operation_id, op.task, op.status, op.release_reason, op.charged_micros) for op in ops]}


def assert_balanced(state):
    assert state['reserved'] == 0
    assert state['balance'] + state['charged'] == 10_000_000


@pytest.mark.parametrize('cancel_at', ['draft', 'check'])
def test_cancelled_stage_resumes_same_request_and_replays_finished_report(gateway, cancel_at):
    gateway.http.responses = [cancelled] if cancel_at == 'draft' else [DRAFT, cancelled]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    gateway.http.responses = [DRAFT, CHECK] if cancel_at == 'draft' else [CHECK]
    result = report(gateway)
    assert 'Right heel' in result['text'] and result['qa']['passed'] is True
    state = snapshot(gateway)
    assert (state['calls'], state['results']) == (3, 2)
    assert state['charged'] > 0 and state['refunds'] == []
    assert_balanced(state)
    for _ in range(3):
        assert report(gateway)['text'] == result['text']
    assert snapshot(gateway) == state, 'completed replay cannot regenerate, charge or refund again'


@pytest.mark.parametrize('cancel_at', ['draft', 'check'])
def test_cancellations_remain_bounded_across_new_event_loops(gateway, cancel_at):
    # A new event loop on each call models no dependence on process-local retry counters.
    for attempt in range(3):
        gateway.http.responses = ([DRAFT] if cancel_at == 'check' and attempt == 0 else []) + [cancelled]
        with pytest.raises(asyncio.CancelledError):
            report(gateway)
    before = snapshot(gateway)
    gateway.http.responses = [DRAFT, CHECK]
    for _ in range(3):
        with pytest.raises(HTTPException) as error:
            report(gateway)
        assert error.value.status_code == 503
        assert error.value.headers.get(providers.REPORT_STATE_HEADER) == providers.REPORT_ENDED_NO_CHARGE
    after = snapshot(gateway)
    assert after['calls'] == before['calls'] == (3 if cancel_at == 'draft' else 4)
    assert after['charged'] == 0
    assert len(after['refunds']) == (0 if cancel_at == 'draft' else 1)
    assert_balanced(after)
    with pytest.raises(HTTPException):
        report(gateway)
    assert snapshot(gateway) == after, 'terminal replay cannot refund twice or start attempt four'


def test_retry_while_check_is_still_running_does_not_refund_or_restart(gateway):
    async def scenario():
        checking = asyncio.Event()
        finish = asyncio.Event()
        async def delayed_check(_):
            checking.set()
            await finish.wait()
            return CHECK
        gateway.http.responses = [DRAFT, delayed_check]
        first = asyncio.create_task(write_report(gateway))
        await asyncio.wait_for(checking.wait(), 2)
        before = snapshot(gateway)
        assert before['charged'] > 0 and before['reserved'] > 0
        try:
            with pytest.raises(HTTPException) as error:
                await write_report(gateway)
            assert error.value.status_code == 409
            assert providers.REPORT_STATE_HEADER not in (error.value.headers or {})
            assert snapshot(gateway) == before, 'a busy retry is not a failed report and must not refund its draft'
        finally:
            finish.set()
            await first
    asyncio.run(scenario())
    assert_balanced(snapshot(gateway))


def test_retryable_503_after_cancelled_check_keeps_the_charged_draft(gateway):
    gateway.http.responses = [DRAFT, cancelled]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    before = snapshot(gateway)
    assert before['charged'] > 0 and before['results'] == 1
    gateway.http.responses = [lambda _: httpx.ConnectError('Synthetic temporary connection failure')]
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert error.value.status_code == 503
    assert providers.REPORT_STATE_HEADER not in (error.value.headers or {})
    retryable = snapshot(gateway)
    assert retryable['charged'] == before['charged'], 'a retryable 503 must keep the completed draft charged once'
    assert retryable['refunds'] == []
    assert retryable['results'] == 1 and retryable['calls'] == 3
    assert_balanced(retryable)
    gateway.http.responses = [CHECK]
    assert report(gateway)['qa']['passed'] is True
    completed = snapshot(gateway)
    assert completed['calls'] == 4 and completed['results'] == 2
    assert completed['refunds'] == []
    assert_balanced(completed)
    report(gateway)
    assert snapshot(gateway) == completed


def test_explicit_retryable_503_between_lookup_and_reserve_keeps_checkpoint(gateway, monkeypatch):
    gateway.http.responses = [DRAFT, cancelled]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    before = snapshot(gateway)
    reserve = billing.reserve
    raced = []
    def competing_worker_cancelled(db, user, operation_id, estimate, encounter_id=None, task='ai'):
        answer = reserve(db, user, operation_id, estimate, encounter_id, task)
        if task == 'quick_qa' and answer['created'] and not raced:
            # Another worker claimed and cancelled this child after our lookup.
            # Use real accounting transitions, not a fabricated billing response.
            raced.append(operation_id)
            billing.release(db, user, operation_id, 'provider_cancelled')
            return reserve(db, user, operation_id, estimate, encounter_id, task)
        return answer
    monkeypatch.setattr(billing, 'reserve', competing_worker_cancelled)
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert error.value.status_code == 503 and error.value.headers['X-WCP-Retryable'] == 'true'
    assert providers.REPORT_STATE_HEADER not in error.value.headers
    after = snapshot(gateway)
    assert after['charged'] == before['charged'] and after['refunds'] == []
    assert after['calls'] == 2 and after['results'] == 1
    assert_balanced(after)
    gateway.http.responses = [CHECK]
    assert report(gateway)['qa']['passed'] is True
    assert snapshot(gateway)['calls'] == 3
    assert snapshot(gateway)['refunds'] == []


def test_http_retryable_503_preserves_pending_report_and_checkpoint(gateway, monkeypatch):
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
        db.add(Patient(id='synthetic-patient', workspace_id=gateway.user.workspace_id,
            display_name='Synthetic case'))
        db.flush()
        encounter = Encounter(id='cancelled-report', patient_id='synthetic-patient',
            workspace_id=gateway.user.workspace_id, title='Synthetic visit', created_by=gateway.user.id)
        db.add(encounter)
        db.flush()
        db.add(Evidence(id='synthetic-source', workspace_id=gateway.user.workspace_id,
            encounter_id=encounter.id, kind='typed', text=CONTEXT['untrusted_sources'][0]['text'],
            created_by=gateway.user.id, request_hash='synthetic-hash'))
        db.commit()
        context = quick.source_context(db, encounter)
    gateway.http.responses = [DRAFT, cancelled]
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(providers.quick_documentation(context, 'nursing', 'concise',
            user=gateway.user, encounter_id='cancelled-report'))
    before = snapshot(gateway)
    client = TestClient(app)
    headers = {'Idempotency-Key': 'synthetic-request'}
    path = '/api/v1/encounters/cancelled-report/quick-report'
    gateway.http.responses = [lambda _: httpx.ConnectError('Synthetic temporary connection failure')]
    failed = client.post(path, json={}, headers=headers)
    assert failed.status_code == 503, 'a temporary failure must not complete the request with a backup draft'
    assert providers.REPORT_STATE_HEADER not in failed.headers
    assert 'recovery' not in failed.json()
    retryable = snapshot(gateway)
    assert retryable['charged'] == before['charged'] > 0 and retryable['refunds'] == []
    assert retryable['results'] == 1 and retryable['calls'] == 3
    gateway.http.responses = [CHECK]
    completed = client.post(path, json={}, headers=headers)
    assert completed.status_code == 200 and completed.json()['qa']['passed'] is True
    assert 'Right heel' in completed.json()['text'] and 'recovery' not in completed.json()
    state = snapshot(gateway)
    assert state['calls'] == 4 and state['results'] == 2 and state['refunds'] == []
    assert_balanced(state)
    assert client.post(path, json={}, headers=headers).json()['text'] == completed.json()['text']
    assert snapshot(gateway) == state


def test_ended_signal_waits_for_app_credit_refund_and_does_not_erase_provider_cost(gateway, monkeypatch):
    for attempt in range(3):
        gateway.http.responses = ([DRAFT] if attempt == 0 else []) + [cancelled]
        with pytest.raises(asyncio.CancelledError):
            report(gateway)
    before = snapshot(gateway)
    assert before['charged'] > 0 and before['refunds'] == []
    original_refund = billing.refund_failed_document
    def unavailable(*args, **kwargs):
        raise HTTPException(503, 'Synthetic accounting temporarily unavailable')
    monkeypatch.setattr(billing, 'refund_failed_document', unavailable)
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert error.value.status_code == 503
    assert providers.REPORT_STATE_HEADER not in (error.value.headers or {})
    assert snapshot(gateway) == before
    monkeypatch.setattr(billing, 'refund_failed_document', original_refund)
    with pytest.raises(HTTPException) as ended:
        report(gateway)
    assert ended.value.headers[providers.REPORT_STATE_HEADER] == providers.REPORT_ENDED_NO_CHARGE
    after = snapshot(gateway)
    assert after['charged'] == 0 and len(after['refunds']) == 1
    assert after['calls'] == before['calls']
    with gateway.sessions() as db:
        completed_draft = db.scalar(select(UsageOperation).where(UsageOperation.task == 'quick_draft'))
        assert completed_draft.actual_cost_micros == 1000, 'app-credit refund must preserve measured provider cost'
    assert_balanced(after)
    with pytest.raises(HTTPException):
        report(gateway)
    assert snapshot(gateway) == after


def test_two_retries_after_cancellation_share_one_new_attempt(gateway):
    gateway.http.responses = [cancelled]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    async def scenario():
        started = asyncio.Event()
        finish = asyncio.Event()
        async def delayed_draft(_):
            started.set()
            await finish.wait()
            return DRAFT
        gateway.http.responses = [delayed_draft, CHECK]
        first = asyncio.create_task(write_report(gateway))
        try:
            await asyncio.wait_for(started.wait(), 2)
            with pytest.raises(HTTPException) as error:
                await write_report(gateway)
            assert error.value.status_code == 409
            assert providers.REPORT_STATE_HEADER not in (error.value.headers or {})
        finally:
            finish.set()
            await first
    asyncio.run(scenario())
    state = snapshot(gateway)
    assert state['calls'] == 3 and state['results'] == 2
    assert_balanced(state)


def test_independent_worker_threads_claim_only_one_cancelled_retry(concurrent_gateway, capsys):
    gateway = concurrent_gateway
    gateway.http.responses = [cancelled]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    root_id = snapshot(gateway)['operations'][0][0]
    race = threading.Barrier(2)
    started = threading.Event()
    finish = threading.Event()
    async def delayed_draft(_):
        started.set()
        assert await asyncio.to_thread(finish.wait, 10), 'synthetic worker was not released'
        return DRAFT
    gateway.http.responses = [delayed_draft, CHECK]
    def worker():
        token = providers.request_operation.set('synthetic-request')
        try:
            race.wait(timeout=5)
            try:
                return report(gateway)
            except HTTPException as error:
                return error.status_code
        finally:
            providers.request_operation.reset(token)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker) for _ in range(2)]
        try:
            assert started.wait(5)
            completed, _ = wait(futures, timeout=5, return_when=FIRST_COMPLETED)
            assert len(completed) == 1
            assert next(iter(completed)).result() == 409
        finally:
            finish.set()
        results = [future.result(timeout=10) for future in futures]
    assert sum(isinstance(result, dict) for result in results) == 1
    state = snapshot(gateway)
    assert state['calls'] == 3 and state['results'] == 2
    assert_balanced(state)
    assert results.count(409) == 1 and state['refunds'] == []
    children = [op for op in state['operations'] if op[1] == 'quick_draft' and op[0] != root_id]
    assert len(children) == 1 and children[0][2] == 'settled'
    with gateway.sessions() as db:
        claims = list(db.scalars(select(CreditLedger).where(
            CreditLedger.kind == 'reserved', CreditLedger.event_key == 'reserve:' + children[0][0])))
    assert len(claims) == 1
    counts = {'database': gateway.engine.dialect.name, 'completed_report_responses': 1,
        'ai_calls': state['calls'], 'successful_stage_charges': sum(op[4] > 0 for op in state['operations']),
        'app_charged_micros': state['charged'], 'refunds': len(state['refunds']),
        'retry_child_reservations': len(claims), 'losers_409': results.count(409), 'held_micros': state['reserved']}
    # Closed fake counts only: no source text, database URL, credentials or IDs.
    with capsys.disabled():
        print('AMILYA_CANCEL_CONCURRENCY ' + json.dumps(counts, sort_keys=True))


def test_a_cancelled_worker_finishing_late_cannot_charge_or_replace_the_retry(gateway):
    async def scenario():
        started = asyncio.Event()
        finish_late = asyncio.Event()
        async def old_worker(_):
            started.set()
            await finish_late.wait()
            return DRAFT
        gateway.http.responses = [old_worker, DRAFT, CHECK]
        old = asyncio.create_task(write_report(gateway))
        await asyncio.wait_for(started.wait(), 2)
        with gateway.sessions() as db:
            first = db.scalar(select(UsageOperation))
            original_id = first.operation_id
            billing.release(db, gateway.user, original_id, 'provider_cancelled')
        try:
            result = await write_report(gateway)
            assert result['qa']['passed'] is True
            before_late_result = snapshot(gateway)
        finally:
            finish_late.set()
        with pytest.raises(HTTPException) as late:
            await old
        assert late.value.status_code == 409
        assert snapshot(gateway) == before_late_result
        with gateway.sessions() as db:
            released = db.scalar(select(UsageOperation).where(UsageOperation.operation_id == original_id))
            assert released.status == 'released' and released.charged_micros == 0
            assert db.scalar(select(ProviderResult).where(ProviderResult.operation_id == original_id)) is None
    asyncio.run(scenario())
    assert_balanced(snapshot(gateway))


def test_timeout_and_cancellation_share_the_same_three_attempt_limit(gateway):
    gateway.http.responses = [lambda _: httpx.ReadTimeout('Synthetic timeout')]
    with pytest.raises(HTTPException):
        report(gateway)
    for _ in range(2):
        gateway.http.responses = [cancelled]
        with pytest.raises(asyncio.CancelledError):
            report(gateway)
    gateway.http.responses = [DRAFT, CHECK]
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert error.value.headers[providers.REPORT_STATE_HEADER] == providers.REPORT_ENDED_NO_CHARGE
    state = snapshot(gateway)
    assert state['calls'] == 3 and state['charged'] == 0
    assert_balanced(state)


@pytest.mark.parametrize('task', sorted(providers.REPORT_STAGE_TASKS))
def test_each_report_stage_can_resume_its_own_cancelled_hold(gateway, task):
    def stage():
        return asyncio.run(providers.structured(task, 'Synthetic source only', 'openai/gpt-6-luna',
            user=gateway.user, encounter_id='stage-coverage'))
    gateway.http.responses = [cancelled]
    with pytest.raises(asyncio.CancelledError):
        stage()
    gateway.http.responses = [CHECK if task == 'quick_qa' else DRAFT]
    result = stage()
    assert result
    state = snapshot(gateway)
    assert state['calls'] == 2 and state['results'] == 1
    assert_balanced(state)


@pytest.mark.parametrize('change', [
    {'status': 'reserved'}, {'status': 'settled'}, {'release_reason': 'unknown'},
    {'release_reason': 'lease_expired'}, {'charged_micros': 1}, {'task': 'image'},
])
def test_running_unknown_or_charged_records_never_count_as_cancelled_report_steps(change):
    record = SimpleNamespace(task='quick_draft', status='released',
        release_reason='provider_cancelled', charged_micros=0)
    for name, value in change.items():
        setattr(record, name, value)
    assert not providers.cancelled_report_stage(record, 'quick_draft')


def test_non_report_cancellation_is_not_changed_by_this_fix(gateway):
    def extract():
        return asyncio.run(providers.extract_text('Synthetic text', 'typed',
            user=gateway.user, db=None, encounter_id='separate-extraction'))
    gateway.http.responses = [cancelled]
    with pytest.raises(asyncio.CancelledError):
        extract()
    gateway.http.responses = [completion()]
    with pytest.raises(HTTPException) as error:
        extract()
    assert error.value.status_code == 409
    assert len(gateway.http.calls) == 1
