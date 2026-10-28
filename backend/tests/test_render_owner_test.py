"""No network: the temporary allowance never activates other accounts or payments."""
import importlib.util
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy import select
from server import billing, render_owner_test, providers, identity
from server.core import settings
from server.billing_models import BillingAccount
from test_identity import client, signup

spec = importlib.util.spec_from_file_location('render_owner_activation',
    Path(__file__).parents[1] / 'scripts/render_owner_activation.py')
activation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(activation)
NOW = activation.APPROVAL_END - 3600


def metadata():
    return dict(limit=1, limit_remaining=.977, usage=.023, limit_reset=None,
        is_management_key=False,
        expires_at=datetime.fromtimestamp(activation.APPROVAL_END + 120, timezone.utc).isoformat())


def test_only_approved_cap_and_expiry_qualify():
    assert activation.key_is_bounded(metadata(), NOW + 600, NOW)


@pytest.mark.parametrize('change', [dict(limit=None), dict(limit=2), dict(limit=True),
    dict(limit_remaining=0), dict(limit_remaining=2), dict(usage=float('nan')),
    dict(limit_reset='daily'), dict(is_management_key=True), dict(expires_at=None),
    dict(expires_at='2027-01-01T00:00:00Z'), dict(expires_at='2026-10-28T00:00:00Z')])
def test_unbounded_exhausted_or_expired_keys_fail_closed(change):
    assert not activation.key_is_bounded(metadata() | change, NOW + 600, NOW)


def enable(monkeypatch, user):
    monkeypatch.setenv('RENDER', 'true')
    monkeypatch.setenv('AMILYA_RENDER_TEST', 'true')
    monkeypatch.setattr(render_owner_test, 'time', SimpleNamespace(time=lambda: NOW))
    values = dict(environment='production', public_base_url='https://amilya-repair-test.onrender.com',
        secure_cookies=True, allow_signup=False, clinical_data_enabled=False, billing_enabled=False,
        provider_execution_enabled=True, test_provider_mode=False, auto_create_schema=False,
        render_owner_test_user_id=user.id, render_owner_test_until=NOW + 600,
        unlimited_credit_user_ids='')
    for key, value in values.items():
        monkeypatch.setattr(settings, key, value)


@pytest.mark.parametrize('change', [dict(render_owner_test_until=0), dict(render_owner_test_until=NOW - 1),
    dict(render_owner_test_until=activation.APPROVAL_END+1), dict(render_owner_test_user_id=''),
    dict(allow_signup=True), dict(clinical_data_enabled=True), dict(billing_enabled=True),
    dict(provider_execution_enabled=False), dict(public_base_url='https://another.onrender.com')])
def test_expiry_and_context_cannot_grant_credits(monkeypatch, change):
    actor = SimpleNamespace(id=str(uuid4()), is_active=True, is_platform_admin=False)
    enable(monkeypatch, actor)
    for key, value in change.items():
        monkeypatch.setattr(settings, key, value)
    assert not render_owner_test.allows(actor)
    assert not billing.has_unlimited_credits(actor)


def test_other_accounts_and_inactive_or_admin_accounts_cannot_use_test(monkeypatch):
    actor = SimpleNamespace(id=str(uuid4()), is_active=True, is_platform_admin=False)
    enable(monkeypatch, actor)
    assert render_owner_test.allows(actor)
    assert not render_owner_test.allows(SimpleNamespace(id=str(uuid4()), is_active=True, is_platform_admin=False))
    actor.is_active = False
    assert not render_owner_test.allows(actor)
    actor.is_active, actor.is_platform_admin = True, True
    assert not render_owner_test.allows(actor)


def test_allowance_is_not_a_paid_subscription_and_expires(client, monkeypatch):
    c, factory = client
    response = signup(c)
    assert response.status_code == 201
    with factory() as db:
        actor = db.get(identity.User, response.json()['user']['id'])
        assert not actor.is_platform_admin
        enable(monkeypatch, actor)
        monkeypatch.setattr(billing, 'SessionLocal', factory, raising=False)
        assert db.scalar(select(BillingAccount)) is None
        operation = billing.reserve(db, actor, 'synthetic-allowance', 5000)
        assert operation['admin_exempt'] is True
        account = db.get(BillingAccount, actor.workspace_id)
        assert account.status != 'active'
        assert account.plan_micros == account.topup_micros == 0
        monkeypatch.setattr(render_owner_test, 'time', SimpleNamespace(time=lambda: NOW + 601))
        assert not billing.has_unlimited_credits(actor)
        with pytest.raises(Exception) as exc:
            billing.reserve(db, actor, 'after-expiry', 5000)
        assert exc.value.status_code == 402


@pytest.mark.parametrize('mode', ['valid', 'other_user', 'two_users', 'inactive', 'admin', 'no_membership', 'no_acceptance'])
def test_activation_reads_eligibility_without_writing_or_bypassing_consent(monkeypatch, mode):
    import psycopg
    actor_id = str(uuid4())
    users = [(actor_id, 'synthetic@example.test', mode != 'inactive', mode == 'admin', 'fixture-workspace')]
    if mode == 'other_user': users[0] = (actor_id, 'other@example.test', True, False, 'fixture-workspace')
    if mode == 'two_users': users *= 2
    queries = []
    class Cursor:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def execute(self, sql, args=()):
            assert sql.startswith('SELECT ')
            queries.append(sql)
            self.sql = sql
        def fetchall(self): return users
        def fetchone(self):
            return None if ('memberships' in self.sql and mode == 'no_membership'
                or 'legal_acceptances' in self.sql and mode == 'no_acceptance') else (1,)
    class Connection:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def cursor(self): return Cursor()
    monkeypatch.setattr(psycopg, 'connect', lambda *a, **kw: Connection())
    monkeypatch.setattr(activation, 'time', SimpleNamespace(time=lambda: NOW))
    checks = []
    monkeypatch.setattr(activation, 'check_key', lambda *args: checks.append(True))
    env = dict(AMILYA_RENDER_OWNER_TEST_UNTIL=str(NOW+600),
        PUBLIC_BASE_URL='https://amilya-repair-test.onrender.com', OPENROUTER_API_KEY='synthetic-key',
        DATABASE_URL='postgresql+psycopg://synthetic:unused@dpg-fixture-a/amilya_repair_test_db',
        RENDER_OWNER_ENROLLMENT_EMAIL='synthetic@example.test')
    if mode == 'valid':
        result = activation.prepare(env)
        assert result['RENDER_OWNER_TEST_USER_ID'] == actor_id
        assert result['PROVIDER_EXECUTION_ENABLED'] == 'true'
        assert 'OPENROUTER_API_KEY' not in result
        assert checks == [True]
    else:
        with pytest.raises(ValueError): activation.prepare(env)
        assert not checks
    assert all(query.startswith('SELECT ') for query in queries)
