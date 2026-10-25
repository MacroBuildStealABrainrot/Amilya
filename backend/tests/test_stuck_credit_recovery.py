"""Stuck-credit recovery (1.8.5-test10, development flag `ai_leases_enabled`, OFF by default).

A server that dies while waiting for the AI leaves a credit hold behind. These tests simulate that death and check:
the hold is given back in full, nothing is ever charged for the lost attempt, a retry works within the attempt limit,
a late worker cannot charge or save (fencing), the batch job reports before it acts, and with the flag OFF nothing changes.
"""
import asyncio
import os
import threading

from fastapi import HTTPException
import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from server import billing, providers
from server.billing_models import BillingAccount, CreditLedger, UsageOperation
from server.db import Base
from server.identity import User, Workspace
from server.provider_models import ProviderResult
from test_providers import completion, gateway  # noqa: F401  (fixture)

CONTEXT = {'case_facts': [], 'untrusted_sources': [{'kind': 'typed', 'text': 'Wound cleansed with NS. Dressed with Mepilex.'}],
           'rejected_claims_do_not_reinstate': []}
GOOD = [completion({'text': 'Wound cleansed with NS. Dressed with Mepilex.'}), completion({'passed': True, 'discrepancies': []})]


class ProcessDied(BaseException):
    """Stands in for the server process being killed mid-call: nothing after this line runs (no settle, no release)."""


def died(call):
    raise ProcessDied()


def report(gateway):
    return asyncio.run(providers.quick_documentation(CONTEXT, 'nursing', 'concise', user=gateway.user, encounter_id='lease-report'))


def money(gateway):
    with gateway.sessions() as db:
        account = db.scalar(select(BillingAccount).where(BillingAccount.workspace_id == gateway.user.workspace_id))
        return account.plan_micros, account.topup_micros, account.reserved_micros


def rows(gateway):
    with gateway.sessions() as db:
        return list(db.scalars(select(UsageOperation).order_by(UsageOperation.created_at)))


POSTGRES_URL = os.environ.get('WCP_TEST_POSTGRES_URL')


@pytest.fixture(params=['sqlite'] + (['postgresql'] if POSTGRES_URL else []))
def database(request, gateway, monkeypatch):
    """Runs every lease test on SQLite and, when WCP_TEST_POSTGRES_URL is set, again on a real PostgreSQL server."""
    if request.param == 'postgresql':
        engine = create_engine(POSTGRES_URL)
        Base.metadata.drop_all(engine); Base.metadata.create_all(engine)
        sessions = sessionmaker(engine, expire_on_commit=False, autoflush=False)
        with gateway.sessions() as source, sessions() as target:
            for model in (Workspace, User, BillingAccount):
                for row in source.scalars(select(model)):
                    data = {c.name: getattr(row, c.key) for c in model.__table__.columns for _ in [0] if hasattr(row, c.key)}
                    target.add(model(**{k: v for k, v in data.items()}))
                target.flush()
            target.commit()
        monkeypatch.setattr(providers, 'SessionLocal', sessions)
        gateway.sessions = sessions
        gateway.pg_engine = engine
        yield gateway
        engine.dispose()
    else:
        yield gateway


@pytest.fixture
def leases(database, monkeypatch):
    gateway = database
    gateway.config.ai_leases_enabled = True
    gateway.config.ai_lease_seconds = 120
    gateway.config.ai_lease_takeover_grace_seconds = 0
    monkeypatch.setattr(billing.settings, 'ai_lease_seconds', 0)          # the dead worker's lease is already over
    monkeypatch.setattr(billing.settings, 'ai_stale_release_quiet_seconds', 0)
    return gateway


def test_with_the_flag_off_a_dead_request_still_blocks_exactly_as_before(gateway):
    start = money(gateway)
    gateway.http.responses = [died]
    with pytest.raises(ProcessDied):
        report(gateway)
    assert rows(gateway)[0].status == 'reserved' and money(gateway) != start
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert error.value.status_code == 409 and 'already processing' in error.value.detail


def test_a_dead_request_is_released_in_full_never_charged_and_the_retry_succeeds(leases):
    gateway = leases
    start = money(gateway)
    gateway.http.responses = [died]
    with pytest.raises(ProcessDied):
        report(gateway)
    held = money(gateway)
    assert held[2] > 0 and held[0] + held[1] < start[0] + start[1]
    gateway.http.responses = list(GOOD)
    result = report(gateway)
    assert 'Mepilex' in result['text']
    lost = [r for r in rows(gateway) if r.status == 'released']
    assert len(lost) == 1 and lost[0].release_reason == 'lease_expired' and lost[0].charged_micros == 0
    assert lost[0].usage['charge_waived'] is True and lost[0].usage['attempts'][-1]['error_code'] == '503'
    charged = sum(r.charged_micros for r in rows(gateway))
    end = money(gateway)
    assert end[2] == 0, 'no credits left on hold'
    assert end[0] + end[1] == start[0] + start[1] - charged, 'credits conserved: only the successful work was paid'
    with gateway.sessions() as db:
        kinds = [row.kind for row in db.scalars(select(CreditLedger).order_by(CreditLedger.created_at))]
    assert 'released' in kinds


def test_the_retry_limit_still_applies_after_interruptions(leases):
    gateway = leases
    for _ in range(providers.MAX_REPORT_STAGE_ATTEMPTS):
        gateway.http.responses = [died]
        with pytest.raises(ProcessDied):
            report(gateway)
    calls = len(gateway.http.calls)
    assert calls == providers.MAX_REPORT_STAGE_ATTEMPTS
    gateway.http.responses = list(GOOD)
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert len(gateway.http.calls) == calls, 'no fourth paid attempt'
    assert error.value.status_code in {502, 503, 504, 409}
    assert money(gateway)[2] == 0, 'every hold was given back'
    assert all(r.charged_micros == 0 for r in rows(gateway))


def test_a_non_report_request_says_try_again_and_a_new_request_works(leases):
    gateway = leases
    def extract():
        return asyncio.run(providers.extract_text('Synthetic wound assessment.', 'typed', user=gateway.user, encounter_id='lease-extract'))
    gateway.http.responses = [died]
    with pytest.raises(ProcessDied):
        extract()
    with pytest.raises(HTTPException) as error:
        extract()
    assert error.value.status_code == 503 and 'No charge was made' in error.value.detail
    assert error.value.headers['X-WCP-Retryable'] == 'true'
    token = providers.request_operation.set('a-new-request-key')
    try:
        gateway.http.responses = [completion()]
        assert extract()['facts'] == []
    finally:
        providers.request_operation.reset(token)
    assert money(gateway)[2] == 0


def test_a_late_worker_whose_hold_was_released_cannot_charge_or_save(leases):
    gateway = leases
    start = money(gateway)
    def released_while_working(call):
        with gateway.sessions() as db:
            row = db.scalar(select(UsageOperation).where(UsageOperation.status == 'reserved'))
            assert billing.release_stale_hold(db, row)
        return completion({'text': 'Wound cleansed with NS.'})
    gateway.http.responses = [released_while_working]
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert error.value.status_code == 503 and 'without charge' in error.value.detail
    with gateway.sessions() as db:
        assert db.scalar(select(ProviderResult)) is None, 'nothing saved by the fenced worker'
    assert money(gateway) == start, 'nothing charged, nothing held'


def test_heartbeat_only_extends_the_current_workers_lease(leases, monkeypatch):
    gateway = leases
    monkeypatch.setattr(billing.settings, 'ai_lease_seconds', 120)
    with gateway.sessions() as db:
        billing.reserve(db, gateway.user, 'op-heartbeat', 1000, 'e', 'quick_draft')
        assert billing.lease_started(db, gateway.user, 'op-heartbeat', 'token-a', now=1000.0)
        assert billing.lease_heartbeat(db, gateway.user.workspace_id, 'op-heartbeat', 'token-a', now=1100.0)
        assert not billing.lease_heartbeat(db, gateway.user.workspace_id, 'op-heartbeat', 'token-b', now=1150.0)
        row = db.scalar(select(UsageOperation).where(UsageOperation.operation_id == 'op-heartbeat'))
        assert row.usage['lease_expires_at'] == 1220.0
        assert not billing.lease_is_stale(row, 1219.0, 60) and not billing.lease_is_stale(row, 1279.0, 60)
        assert billing.lease_is_stale(row, 1280.0, 60)


def test_the_batch_job_reports_first_never_charges_and_leaves_live_work_alone(leases, monkeypatch):
    gateway = leases
    monkeypatch.setattr(billing.settings, 'ai_lease_seconds', 120)
    with gateway.sessions() as db:
        billing.reserve(db, gateway.user, 'op-dead', 2000, 'e', 'quick_draft')
        billing.lease_started(db, gateway.user, 'op-dead', 't1', now=0.0)
        billing.reserve(db, gateway.user, 'op-alive', 3000, 'e', 'quick_draft')
        import time
        billing.lease_started(db, gateway.user, 'op-alive', 't2', now=time.time())
    before = money(gateway)
    with gateway.sessions() as db:
        dry = billing.release_stale_holds(db, dry_run=True)
    assert dry['stale'] == 1 and dry['released'] == 0 and dry['held_micros'] == 2000
    assert money(gateway) == before
    with gateway.sessions() as db:
        done = billing.release_stale_holds(db, dry_run=False)
    assert done['released'] == 1
    after = money(gateway)
    assert after[2] == before[2] - 2000 and after[0] + after[1] == before[0] + before[1] + 2000
    with gateway.sessions() as db:
        statuses = {r.operation_id: r.status for r in db.scalars(select(UsageOperation))}
        assert statuses == {'op-dead': 'released', 'op-alive': 'reserved'}
        assert billing.release_stale_holds(db, dry_run=False)['released'] == 0, 'running it again changes nothing'
        report = billing.absorbed_cost_report(db, 0)
    assert report['lease_expired'] == 1 and report['charged_micros'] == 0


@pytest.mark.skipif(not POSTGRES_URL, reason='needs a real PostgreSQL server (WCP_TEST_POSTGRES_URL)')
def test_postgres_many_workers_racing_to_release_one_dead_hold_release_it_exactly_once(leases, monkeypatch):
    gateway = leases
    if gateway.sessions.kw['bind'].dialect.name != 'postgresql':
        pytest.skip('true parallel workers need PostgreSQL')
    with gateway.sessions() as db:
        billing.reserve(db, gateway.user, 'op-race', 5000, 'e', 'quick_draft')
        billing.lease_started(db, gateway.user, 'op-race', 'dead', now=0.0)
    before = money(gateway)
    results, barrier = [], threading.Barrier(12)
    def worker():
        with gateway.sessions() as db:
            row = db.scalar(select(UsageOperation).where(UsageOperation.operation_id == 'op-race'))
            barrier.wait()
            results.append(billing.release_stale_hold(db, row))
    threads = [threading.Thread(target=worker) for _ in range(12)]
    [t.start() for t in threads]; [t.join(30) for t in threads]
    assert results.count(True) == 1 and len(results) == 12
    after = money(gateway)
    assert after[2] == before[2] - 5000 and after[0] + after[1] == before[0] + before[1] + 5000


def test_settle_racing_release_ends_exactly_one_way(leases):
    gateway = leases
    outcomes = []
    for n in range(20):
        op = f'op-settle-race-{n}'
        with gateway.sessions() as db:
            billing.reserve(db, gateway.user, op, 4000, 'e', 'quick_draft')
        before = money(gateway)
        barrier = threading.Barrier(2); result = {}
        def settle():
            with gateway.sessions() as db:
                barrier.wait()
                try:
                    billing.settle(db, gateway.user, op, 1000, 'p', 'm', {}); result['settle'] = True
                except HTTPException:
                    result['settle'] = False
        def release():
            with gateway.sessions() as db:
                row = db.scalar(select(UsageOperation).where(UsageOperation.operation_id == op))
                barrier.wait()
                result['release'] = billing.release_stale_hold(db, row)
        threads = [threading.Thread(target=settle), threading.Thread(target=release)]
        [t.start() for t in threads]; [t.join(30) for t in threads]
        assert result['settle'] != result['release'], result
        with gateway.sessions() as db:
            row = db.scalar(select(UsageOperation).where(UsageOperation.operation_id == op))
        after = money(gateway)
        if result['settle']:
            assert row.status == 'settled' and row.charged_micros == 1000
            assert after[0] + after[1] == before[0] + before[1] + 3000
        else:
            assert row.status == 'released' and row.charged_micros == 0
            assert after[0] + after[1] == before[0] + before[1] + 4000
        assert after[2] == before[2] - 4000
        outcomes.append('settle' if result['settle'] else 'release')
    assert len(outcomes) == 20
