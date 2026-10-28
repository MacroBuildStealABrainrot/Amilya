"""Synthetic only: registration remains explicit, restricted and non-privileged."""
import time

import pytest
from sqlalchemy import select

from server import identity, main, render_enrollment
from server.core import settings
from server.billing_models import BillingAccount
from test_identity import client, signup


def enable(monkeypatch, factory):
    monkeypatch.setenv('RENDER', 'true')
    monkeypatch.setenv('AMILYA_RENDER_TEST', 'true')
    values = dict(environment='production', public_base_url=render_enrollment.TEST_ORIGIN,
        database_url='postgresql+psycopg://synthetic:unused@dpg-synthetic-a/amilya_repair_test_db',
        secure_cookies=True, allow_signup=False, clinical_data_enabled=False,
        billing_enabled=False, provider_execution_enabled=False, test_provider_mode=False,
        auto_create_schema=False, render_owner_enrollment_email='synthetic@example.test',
        render_owner_enrollment_until=int(time.time()) + 1800)
    for key, value in values.items():
        monkeypatch.setattr(settings, key, value)
    monkeypatch.setattr(main, 'SessionLocal', factory)


@pytest.mark.parametrize('changes', [
    {'environment': 'development'}, {'public_base_url': 'https://other.onrender.com'},
    {'database_url': 'postgresql+psycopg://synthetic:unused@10.30.0.10/woundcopilot'},
    {'database_url': 'sqlite:///work/local.db'}, {'allow_signup': True},
    {'secure_cookies': False}, {'clinical_data_enabled': True}, {'billing_enabled': True},
    {'provider_execution_enabled': True}, {'render_owner_enrollment_email': ''},
    {'render_owner_enrollment_until': 0},
    {'render_owner_enrollment_until': int(time.time()) + 2 * 86400},
])
def test_wrong_context_fails_closed(client, monkeypatch, changes):
    c, factory = client
    enable(monkeypatch, factory)
    for key, value in changes.items():
        monkeypatch.setattr(settings, key, value)
    assert not render_enrollment.configured()


def test_only_selected_username_can_enroll_and_consent_is_still_required(client, monkeypatch):
    c, factory = client
    enable(monkeypatch, factory)
    c.base_url = 'https://testserver'
    config = c.get('/api/v1/config').json()
    assert config['allow_signup'] is True and config['signup_scope'] == 'owner_test'
    assert not config['capabilities']['ai']['enabled']
    assert signup(c, email='someone-else@example.test').status_code == 403
    assert signup(c, accept_terms=False).status_code == 422
    assert signup(c, terms_version='old').status_code == 422
    with factory() as db:
        assert db.scalar(select(identity.User)) is None
        assert db.scalar(select(identity.LegalAcceptance)) is None
    result = signup(c)
    assert result.status_code == 201
    assert result.json()['user']['is_platform_admin'] is False
    assert c.get('/api/v1/admin/overview').status_code == 403
    config = c.get('/api/v1/config').json()
    assert config['allow_signup'] is False and config['signup_scope'] == 'closed'
    assert signup(c).status_code == 403
    with factory() as db:
        assert len(list(db.scalars(select(identity.User)))) == 1
        assert db.scalar(select(BillingAccount)) is None


def test_registered_owner_can_use_native_login_without_public_signup(client, monkeypatch):
    c, factory = client
    enable(monkeypatch, factory)
    c.base_url = 'https://testserver'
    result = signup(c)
    assert result.status_code == 201
    token = result.json()['csrf_token']
    assert c.post('/api/v1/auth/logout', headers={'X-CSRF-Token': token}).status_code == 200
    result = c.post('/api/v1/auth/login', json={'email': 'synthetic@example.test',
        'password': 'Synthetic-test-password'}, headers={'X-WCP-Client': 'android'})
    assert result.status_code == 200
    assert 'wcp_refresh=' in result.headers['set-cookie']
    assert c.get('/api/v1/auth/session').status_code == 200
    assert c.get('/api/v1/config').json()['allow_signup'] is False


def test_expiry_and_render_context_are_enforced(client, monkeypatch):
    _, factory = client
    enable(monkeypatch, factory)
    with factory() as db:
        assert render_enrollment.is_open(db)
        monkeypatch.setattr(settings, 'render_owner_enrollment_until', int(time.time()) - 1)
        assert not render_enrollment.is_open(db)
        monkeypatch.setattr(settings, 'render_owner_enrollment_until', int(time.time()) + 1800)
        monkeypatch.setenv('RENDER', 'false')
        assert not render_enrollment.is_open(db)
