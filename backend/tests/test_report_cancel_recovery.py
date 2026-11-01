"""Cancelled report stages: real gateway/accounting, fake provider and money only."""
import asyncio
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import threading

from fastapi import HTTPException
import pytest
from sqlalchemy import select
from types import SimpleNamespace
import httpx

from server import billing, providers
from server.billing_models import BillingAccount, CreditLedger, UsageOperation
from server.provider_models import ProviderResult
from test_providers import completion, gateway  # noqa: F401


CONTEXT = {'case_facts': [], 'untrusted_sources': [
    {'kind': 'typed', 'text': 'Right heel. Cleansed with NS. Dressed with Mepilex.'}],
    'rejected_claims_do_not_reinstate': []}
DRAFT = completion({'text': 'DRAFT — CLINICIAN REVIEW REQUIRED\nRight heel. Cleansed with NS. Dressed with Mepilex.'})
CHECK = completion({'passed': True, 'discrepancies': []})


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


def test_independent_worker_threads_claim_only_one_cancelled_retry(gateway):
    gateway.http.responses = [cancelled]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    race = threading.Barrier(2)
    started = threading.Event()
    finish = threading.Event()
    async def delayed_draft(_):
        started.set()
        assert await asyncio.to_thread(finish.wait, 5), 'synthetic worker was not released'
        return DRAFT
    gateway.http.responses = [delayed_draft, CHECK]
    def worker():
        token = providers.request_operation.set('synthetic-request')
        try:
            race.wait(timeout=3)
            try:
                return report(gateway)
            except HTTPException as error:
                return error.status_code
        finally:
            providers.request_operation.reset(token)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(worker) for _ in range(2)]
        try:
            assert started.wait(3)
            completed, _ = wait(futures, timeout=3, return_when=FIRST_COMPLETED)
            assert len(completed) == 1
            assert next(iter(completed)).result() == 409
        finally:
            finish.set()
        results = [future.result(timeout=5) for future in futures]
    assert sum(isinstance(result, dict) for result in results) == 1
    state = snapshot(gateway)
    assert state['calls'] == 3 and state['results'] == 2
    assert_balanced(state)


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
