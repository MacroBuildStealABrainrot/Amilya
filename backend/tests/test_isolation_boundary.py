"""Safety boundary for the isolated test app (Android build type ``isolatedTest``).

The app marks every request with ``X-Amilya-Build: isolated-test``. Only the isolated Render test server may accept it.
Every other server refuses it before reading the body, so recordings, photos or text from that app are never stored.
"""
import pytest

from server import isolation
from server.core import settings
from test_identity import client  # noqa: F401  (fixture)

HEADER = {'X-Amilya-Build': 'isolated-test'}


def make_isolated(monkeypatch):
    monkeypatch.setenv('RENDER', 'true')
    monkeypatch.setenv('AMILYA_RENDER_TEST', 'true')
    for name, value in dict(environment='production', public_base_url=isolation.TEST_ORIGIN, secure_cookies=True,
                            clinical_data_enabled=False, billing_enabled=False, allow_signup=False,
                            database_url='postgresql+psycopg://u:p@dpg-abc/amilya_repair_test_db').items():
        monkeypatch.setattr(settings, name, value)


def test_a_normal_server_is_not_the_isolated_test_server(monkeypatch):
    monkeypatch.delenv('RENDER', raising=False)
    monkeypatch.delenv('AMILYA_RENDER_TEST', raising=False)
    assert not isolation.is_isolated_test_server()
    assert isolation.refuses(HEADER | {'x-amilya-build': 'isolated-test'})


def test_the_isolated_test_server_accepts_the_isolated_app(monkeypatch):
    make_isolated(monkeypatch)
    assert isolation.is_isolated_test_server()
    assert not isolation.refuses({'x-amilya-build': 'isolated-test'})


@pytest.mark.parametrize('change', [
    dict(clinical_data_enabled=True), dict(billing_enabled=True), dict(allow_signup=True),
    dict(public_base_url='https://woundcopilot-1039151887397.us-central1.run.app'),
    dict(database_url='postgresql+psycopg://u:p@10.0.0.5/woundcopilot'), dict(secure_cookies=False),
    dict(environment='development')])
def test_any_break_in_the_test_boundary_makes_the_server_refuse(monkeypatch, change):
    make_isolated(monkeypatch)
    for name, value in change.items():
        monkeypatch.setattr(settings, name, value)
    assert not isolation.is_isolated_test_server()
    assert isolation.refuses({'x-amilya-build': 'isolated-test'})


def test_other_apps_are_not_affected(monkeypatch):
    monkeypatch.delenv('RENDER', raising=False)
    assert not isolation.refuses({})
    assert not isolation.refuses({'x-amilya-build': 'pilot'})


def test_a_normal_server_refuses_an_upload_from_the_isolated_app_before_storing_anything(client, monkeypatch):
    c, factory = client
    monkeypatch.delenv('RENDER', raising=False)
    monkeypatch.delenv('AMILYA_RENDER_TEST', raising=False)
    for path, kwargs in [('/api/v1/encounters/x/upload', dict(files={'file': ('a.m4a', b'synthetic', 'audio/mp4')}, data={'kind': 'audio', 'consent': 'true'})),
                         ('/api/v1/auth/login', dict(json={'email': 'a@example.com', 'password': 'synthetic-password'})),
                         ('/api/v1/encounters/x/quick-report', dict(json={'template': 'nursing'}))]:
        response = c.post(path, headers=HEADER, **kwargs)
        assert response.status_code == 421, path
        assert 'Nothing was stored' in response.json()['detail']
    # Reading the public description is also refused, and it says it is not the isolated test server.
    assert c.get('/api/v1/config', headers=HEADER).status_code == 421
    assert c.get('/api/v1/config').json()['isolated_test_server'] is False


def test_the_isolated_test_server_declares_itself(client, monkeypatch):
    c, factory = client
    make_isolated(monkeypatch)
    config = c.get('/api/v1/config', headers=HEADER)
    assert config.status_code == 200
    assert config.json()['isolated_test_server'] is True
    assert config.json()['clinical_data_enabled'] is False
    assert config.json()['synthetic_test']['patient_data_allowed'] is False
