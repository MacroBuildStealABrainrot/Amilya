"""Sentry reporting: privacy, on/off rules, monitored statuses, grouping and rate limits."""
import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from server import diagnostics, sentry_relay
from test_isolation_boundary import make_isolated

BACKEND_DSN = 'https://0123456789abcdef0123456789abcdef@o1.ingest.us.sentry.io/111'
ANDROID_DSN = 'https://fedcba9876543210fedcba9876543210@o1.ingest.us.sentry.io/222'
PRIVATE = ['Jane Doe', 'patient', 'cellulitis', 'MRN', 'password', 'Bearer', 'transcript text']


@pytest.fixture
def queued(monkeypatch):
    make_isolated(monkeypatch)
    monkeypatch.setenv('AMILYA_SENTRY_BACKEND_DSN', BACKEND_DSN)
    monkeypatch.setenv('AMILYA_SENTRY_ANDROID_DSN', ANDROID_DSN)
    monkeypatch.setenv('RENDER_GIT_COMMIT', 'abcdef1234567890')
    sentry_relay._recent.clear(); sentry_relay._per_group.clear()
    sent = []
    monkeypatch.setattr(sentry_relay._pool, 'submit', lambda fn, event, dsn: sent.append((event, dsn)))
    return sent


def no_private_text(event):
    text = json.dumps(event)
    for word in PRIVATE:
        assert word not in text, word
    for key in ('user', 'request', 'extra', 'contexts', 'breadcrumbs'):
        assert key not in event


def test_off_without_dsn_and_off_on_any_server_except_the_isolated_test_server(monkeypatch):
    monkeypatch.setenv('AMILYA_SENTRY_BACKEND_DSN', BACKEND_DSN)
    monkeypatch.delenv('AMILYA_RENDER_TEST', raising=False)
    assert not sentry_relay.enabled('backend')
    assert sentry_relay.send('backend', stage='server', code='x') is False
    make_isolated(monkeypatch)
    monkeypatch.delenv('AMILYA_SENTRY_BACKEND_DSN')
    assert not sentry_relay.enabled('backend')


def test_bad_dsn_is_rejected():
    assert sentry_relay.parse_dsn('http://key@host/1') is None
    assert sentry_relay.parse_dsn('https://host/1') is None
    assert sentry_relay.parse_dsn(BACKEND_DSN) == ('https://o1.ingest.us.sentry.io/api/111/envelope/',
                                                    '0123456789abcdef0123456789abcdef')


def test_exception_sends_class_and_code_locations_only(queued):
    def handler(note):
        raise ValueError('Jane Doe has cellulitis, MRN 12345, password hunter2')
    try:
        handler('patient transcript text')
    except ValueError as exc:
        assert sentry_relay.server_exception(exc, '/api/v1/quick/{encounter_id}/audio-parts')
    event, dsn = queued[0]
    assert dsn == BACKEND_DSN
    value = event['exception']['values'][0]
    assert value['type'] == 'ValueError' and value['value'] == 'details withheld for privacy'
    for frame in value['stacktrace']['frames']:
        assert set(frame) == {'module', 'function', 'lineno', 'in_app'}
    assert event['tags']['route'] == '/api/v1/quick/{encounter_id}/audio-parts'
    assert event['release'] == 'amilya-backend@abcdef123456'
    assert event['environment'] == 'isolated-test'
    no_private_text(event)


def test_unsafe_values_become_other(queued):
    sentry_relay.send('backend', stage='Jane Doe notes', code='cellulitis?', route='/x?name=Jane',
                      extra_tags={'note': 'patient said hi'})
    event = queued[0][0]
    assert event['tags']['stage'] == 'other' and event['tags']['code'] == 'other'
    assert event['tags']['route'] == 'other' and event['tags']['note'] == 'other'
    no_private_text(event)


@pytest.mark.parametrize('status,sent', [(409, True), (429, True), (500, True), (502, True), (503, True), (504, True),
                                         (200, False), (404, False), (422, False)])
def test_only_the_requested_http_statuses_are_reported(queued, status, sent):
    assert sentry_relay.http_status(status, '/api/v1/quick/{id}/report', 'INVALID_RESPONSE', 'quick_draft') is sent
    if sent:
        event = queued[0][0]
        assert event['level'] == ('warning' if status in (409, 429) else 'error')
        assert event['tags']['http_status'] == str(status)


def test_same_problem_groups_together_and_floods_are_limited(queued):
    for _ in range(8):
        sentry_relay.http_status(502, '/api/v1/quick/{id}/report', 'INVALID_RESPONSE', 'quick_draft')
    assert len(queued) == sentry_relay.MAX_PER_GROUP_PER_MINUTE
    assert len({tuple(e['fingerprint']) for e, _ in queued}) == 1


def test_provider_failure_and_fallback_and_report_check(queued):
    sentry_relay.provider_failure({'status': 'failed', 'stage': 'image', 'error_category': 'OUTPUT_LIMIT',
                                   'http_status': 200, 'selected_model': 'anthropic/claude-haiku-5.5',
                                   'finish_reason': 'length', 'provider': 'openrouter'})
    sentry_relay.provider_failure({'status': 'success', 'stage': 'image'})
    sentry_relay.server_stage({'source': 'server', 'stage': 'photo_fallback', 'status': 'pending'})
    sentry_relay.server_stage({'source': 'server', 'stage': 'photo_fallback', 'status': 'success'})
    sentry_relay.server_stage({'source': 'server', 'stage': 'source_faithfulness_draft', 'status': 'failed', 'defect_count': 2})
    sentry_relay.server_stage({'source': 'server', 'stage': 'quick_draft_result', 'status': 'failed'})
    codes = [e['tags']['code'] for e, _ in queued]
    assert codes == ['OUTPUT_LIMIT', 'ai_fallback_used', 'report_check_failed']


def test_phone_crash_goes_to_the_android_project_with_code_locations_only(queued):
    item = diagnostics.ClientEvent(id='00000000-0000-4000-8000-000000000001', job_id='00000000-0000-4000-8000-000000000002',
        stage='app_crash', started_at='2026-11-05T01:00:00Z', finished_at='2026-11-05T01:00:00Z', duration_ms=0,
        status='failed', crash_type='java.lang.IllegalStateException', app_version='1.9.0-test38',
        crash_frames=['com.woundcopilot.android.AppState.quickReport:812', 'android.os.Handler.dispatchMessage:102'])
    assert sentry_relay.client_event(item)
    event, dsn = queued[0]
    assert dsn == ANDROID_DSN and event['level'] == 'fatal'
    assert event['exception']['values'][0]['type'] == 'java.lang.IllegalStateException'
    assert event['release'] == 'amilya-android@1.9.0-test38'
    no_private_text(event)


def test_phone_success_events_are_not_reported(queued):
    item = diagnostics.ClientEvent(id='00000000-0000-4000-8000-000000000003', job_id='00000000-0000-4000-8000-000000000004',
        stage='upload', started_at='2026-11-05T01:00:00Z', finished_at='2026-11-05T01:00:00Z', duration_ms=1, status='success')
    assert not sentry_relay.client_event(item)
    assert queued == []


@pytest.mark.parametrize('field,value', [('crash_type', 'Jane Doe crashed'), ('crash_frames', ['note: patient said hi:1']),
                                         ('app_version', '1.0 Jane'), ('exit_reason', 'whatever')])
def test_phone_cannot_send_free_text(field, value):
    with pytest.raises(Exception):
        diagnostics.ClientEvent(id='00000000-0000-4000-8000-000000000005', job_id='00000000-0000-4000-8000-000000000006',
            stage='app_crash', started_at='2026-11-05T01:00:00Z', finished_at='2026-11-05T01:00:00Z', duration_ms=0,
            status='failed', **{field: value})


def test_monitoring_failure_never_breaks_a_request(queued, monkeypatch):
    def broken(*a, **k):
        raise RuntimeError('sentry down')
    monkeypatch.setattr(sentry_relay, 'build_event', broken)
    assert sentry_relay.http_status(502, '/x', None, None) is False


def test_self_test_sends_grouped_backend_and_android_events(queued):
    assert all(sentry_relay.self_test())
    backend = [e for e, d in queued if d == BACKEND_DSN]
    android = [e for e, d in queued if d == ANDROID_DSN]
    assert len(backend) == 2 and len(android) == 2
    assert backend[0]['fingerprint'] == backend[1]['fingerprint']
    assert android[0]['fingerprint'] == android[1]['fingerprint']
    for event in backend + android:
        no_private_text(event)


def test_envelope_is_valid_three_lines():
    event = sentry_relay.build_event('backend', stage='s', code='c')
    lines = sentry_relay._envelope(event, BACKEND_DSN).decode().split('\n')
    assert json.loads(lines[0])['event_id'] == event['event_id']
    assert json.loads(lines[1])['type'] == 'event'
    assert json.loads(lines[2])['event_id'] == event['event_id']
