"""Automatic photo usability and bounded capacity recovery, no live providers."""
import asyncio
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
import json

from fastapi import HTTPException
import httpx
import pytest
from sqlalchemy import select

from server import providers
from server.billing_models import BillingAccount, UsageOperation
from server.clinical_models import Evidence
from test_clinical import clinical_api, create_case
from test_providers import completion, extract, gateway, operation_rows


def limited(*, reason='rate_limit_exceeded', message='Temporarily rate limited', retry_after=None, extra=None):
    return httpx.Response(429, request=httpx.Request('POST', 'https://provider.invalid/api/v1/chat/completions'),
        headers={} if retry_after is None else {'Retry-After': retry_after},
        json={'error': {'message': message, 'metadata': {'error_type': reason}}, **(extra or {})})


@pytest.mark.parametrize('reason', list(providers.PHOTO_RETAKE_MESSAGES))
def test_insufficient_photo_discards_every_finding_and_uses_fixed_retaking_message(reason):
    result = providers.validate_result({'quality': {'status': 'insufficient', 'reason': reason},
        'facts': [{'name': 'periwound', 'value': {'text': 'Invented clinical finding'}, 'quote': ''}],
        'warnings': []}, 'image')
    assert result['facts'] == []
    assert result['quality'] == {'status': 'insufficient', 'reason': reason, 'retake_required': True,
        'message': providers.PHOTO_RETAKE_MESSAGES[reason]}
    assert 'Invented' not in json.dumps(result)


@pytest.mark.parametrize('quality', [None, {}, {'status': True, 'reason': 'none'},
    {'status': 'sufficient', 'reason': 'blur'}, {'status': 'insufficient', 'reason': 'none'},
    {'status': 'sufficient', 'reason': 'none', 'diagnosis': 'infected'},
    {'status': 'insufficient', 'reason': 'infected'}, {'status': [], 'reason': 'none'}])
def test_automatic_quality_decision_is_required_and_strict(quality):
    with pytest.raises(ValueError):
        providers.validate_result({'quality': quality, 'facts': [], 'warnings': []}, 'image')


def test_sufficient_photo_keeps_only_visible_assessable_observations():
    result = providers.validate_result({'quality': {'status': 'sufficient', 'reason': 'none'}, 'warnings': [],
        'facts': [{'name': 'wound_edges', 'value': {'text': 'Visible irregular margin'}, 'quote': ''},
                  {'name': 'pain', 'value': {'present': True}, 'quote': ''}]}, 'image')
    assert result['quality']['retake_required'] is False
    assert [fact['name'] for fact in result['facts']] == ['wound_edges']


def test_sufficient_without_assessable_findings_becomes_retake():
    result = providers.validate_result({'quality': {'status': 'sufficient', 'reason': 'none'}, 'warnings': [],
        'facts': [{'name': 'pain', 'value': {'present': True}, 'quote': ''}]}, 'image')
    assert result['facts'] == []
    assert result['quality']['retake_required'] and result['quality']['reason'] == 'uncertain'


def test_photo_quality_persists_and_replays_without_manual_visibility_or_new_facts(clinical_api, monkeypatch):
    attempts = []
    quality = providers.photo_quality({'status': 'insufficient', 'reason': 'blur'})
    async def analyze(*args, **kwargs):
        attempts.append(1)
        return {'quality': quality, 'facts': [{'name': 'periwound', 'value': {'text': 'Should be discarded'}}], 'warnings': []}
    monkeypatch.setattr(providers, 'analyze_media', analyze)
    case = create_case(clinical_api, wound=False)
    path = f"/api/v1/encounters/{case['id']}/upload"
    files = {'file': ('test.png', b'\x89PNG\r\n\x1a\nsynthetic', 'image/png')}
    kwargs = {'files': files, 'data': {'kind': 'photo'}, 'headers': {'Idempotency-Key': 'quality-idempotent'}}
    first = clinical_api.client.post(path, **kwargs)
    assert first.status_code == 201, first.text
    assert first.json()['facts'] == [] and first.json()['photo_quality'] == quality
    assert first.json()['evidence']['metadata']['photo_quality'] == quality
    assert 'photo_review' not in first.json()['evidence']['metadata']
    again = clinical_api.client.post(path, **kwargs)
    assert again.status_code == 201 and first.json() == again.json() and len(attempts) == 1
    saved = clinical_api.client.get(f"/api/v1/encounters/{case['id']}").json()
    assert saved['evidence'][0]['metadata']['photo_quality'] == quality and not saved['facts']


def test_transient429_retries_once_under_one_reservation_and_caches_success(gateway):
    gateway.http.responses = [limited(retry_after='2'), completion()]
    result = extract(gateway)
    assert gateway.retry_sleeps == [2]
    assert len(gateway.http.calls) == 2 and gateway.http.calls[0]['json'] == gateway.http.calls[1]['json']
    assert result['usage']['attempt_count'] == 2
    assert result['usage']['attempts'][0]['error_code'] == '429'
    assert result['usage']['attempts'][1]['status'] == 'completed'
    assert result['usage']['actual_cost_micros'] is None
    replay = extract(gateway)
    assert result == replay and len(gateway.http.calls) == 2 and gateway.retry_sleeps == [2]
    rows = operation_rows(gateway)
    assert len(rows) == 1 and rows[0].status == 'settled' and rows[0].charged_micros > 0
    with gateway.sessions() as db:
        account = db.get(BillingAccount, gateway.user.workspace_id)
        assert account.reserved_micros == 0


def test_exhausted429_budget_settles_once_and_replay_does_not_retry_again(gateway):
    gateway.http.responses = [limited(), limited(), completion()]
    for _ in range(2):
        with pytest.raises(HTTPException) as error:
            extract(gateway)
        assert error.value.status_code == 503
    assert len(gateway.http.calls) == 2 and len(gateway.retry_sleeps) == 1
    row = operation_rows(gateway)[0]
    assert row.charged_micros == 0 and row.usage['charge_waived']
    assert row.usage['attempt_count'] == 2 and row.usage['upstream_status'] == 429
    assert row.usage['failure_reason'] == 'provider_rate_limit'
    assert len(row.usage['attempts']) == 2


@pytest.mark.parametrize('reason,message', [('insufficient_quota', 'Temporary error'),
    ('rate_limit_exceeded', 'You have exhausted your daily quota'), ('rate_limit_exceeded', 'Insufficient credits'),
    ('billing_hard_limit_reached', 'Account limit'), ('rate_limit_exceeded', 'Monthly budget exceeded')])
def test_quota_and_credit429_are_never_automatically_retried(gateway, reason, message):
    gateway.http.responses = [limited(reason=reason, message=message, retry_after='1'), completion()]
    with pytest.raises(HTTPException) as error:
        extract(gateway)
    assert error.value.status_code == 503 and len(gateway.http.calls) == 1 and gateway.retry_sleeps == []
    row = operation_rows(gateway)[0]
    assert row.usage['failure_reason'] == 'provider_credits' and row.charged_micros == 0


@pytest.mark.parametrize('status', [400, 401, 402, 403, 404, 500, 503])
def test_non429_failures_are_not_automatically_retried(gateway, status):
    gateway.http.responses = [(status, {'error': {'message': 'Synthetic request error'}}), completion()]
    with pytest.raises(HTTPException):
        extract(gateway)
    assert len(gateway.http.calls) == 1 and not gateway.retry_sleeps


@pytest.mark.parametrize('extra', [{'usage': {'completion_tokens': 1}}, {'choices': []}, {'id': 'generated-id'}])
def test_possible_generation429_is_not_retried(gateway, extra):
    gateway.http.responses = [limited(extra=extra), completion()]
    with pytest.raises(HTTPException):
        extract(gateway)
    assert len(gateway.http.calls) == 1 and not gateway.retry_sleeps


def test_http200_error429_is_not_retried_after_generation_may_have_started(gateway):
    gateway.http.responses = [{'error': {'code': 429, 'message': 'Rate limit exceeded'}}, completion()]
    with pytest.raises(HTTPException) as error:
        extract(gateway)
    assert error.value.status_code == 503 and len(gateway.http.calls) == 1 and not gateway.retry_sleeps
    assert operation_rows(gateway)[0].usage['failure_reason'] == 'provider_rate_limit'


@pytest.mark.parametrize('hint', ['60', '-1', 'NaN', 'Infinity', 'bad'])
def test_retry_hint_is_never_shortened_or_ignored(gateway, hint):
    gateway.http.responses = [limited(retry_after=hint), completion()]
    with pytest.raises(HTTPException):
        extract(gateway)
    assert len(gateway.http.calls) == 1 and not gateway.retry_sleeps


def test_short_http_date_retry_hint_is_supported():
    hint = format_datetime(datetime.now(timezone.utc) + timedelta(seconds=3), usegmt=True)
    delay = providers.rate_limit_retry_delay(limited(retry_after=hint))
    assert 0 < delay <= 3


def test_unknown_large_or_malformed_error_is_not_automatically_retried():
    for body in ('not-json', 'x' * 20000):
        response = httpx.Response(429, request=httpx.Request('POST', 'https://provider.invalid'), text=body)
        assert providers.rate_limit_retry_delay(response) is None
