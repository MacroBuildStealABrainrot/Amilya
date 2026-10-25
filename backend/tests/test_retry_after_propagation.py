"""Safe final capacity hints survive status mapping and settled-failure replay."""
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
import json

from fastapi import HTTPException
import httpx
import pytest

from server import providers
from test_photo_quality_retry import limited
from test_providers import completion, extract, gateway, operation_rows


@pytest.mark.parametrize('hint,seconds', [('60', 60), ('60.2', 61), ('86400', 86400)])
def test_long_hint_is_propagated_without_automatic_retry_and_replay_counts_down(gateway, monkeypatch, hint, seconds):
    now = providers.time.time()
    monkeypatch.setattr(providers.time, 'time', lambda: now)
    gateway.http.responses = [limited(retry_after=hint), completion()]
    with pytest.raises(HTTPException) as first:
        extract(gateway)
    assert first.value.status_code == 503
    assert first.value.headers == {'Retry-After': str(seconds), 'X-WCP-Error':'RATE_LIMITED','X-WCP-Retryable':'true','X-WCP-Upstream-Status':'429'}
    assert len(gateway.http.calls) == 1 and not gateway.retry_sleeps
    operation = operation_rows(gateway)[0]
    assert operation.usage['retry_not_before'] == now + seconds
    assert operation.charged_micros == 0 and operation.usage['charge_waived']
    monkeypatch.setattr(providers.time, 'time', lambda: now + 10)
    with pytest.raises(HTTPException) as replay:
        extract(gateway)
    assert replay.value.headers == {'Retry-After': str(seconds - 10), 'X-WCP-Error':'RATE_LIMITED','X-WCP-Retryable':'true','X-WCP-Upstream-Status':'429'}
    assert len(gateway.http.calls) == 1 and len(operation_rows(gateway)) == 1
    monkeypatch.setattr(providers.time, 'time', lambda: now + seconds + 1)
    with pytest.raises(HTTPException) as expired:
        extract(gateway)
    assert 'Retry-After' not in expired.value.headers and len(gateway.http.calls) == 1


def test_http_date_hint_is_normalized_into_integer_seconds(gateway):
    hint = format_datetime(datetime.now(timezone.utc) + timedelta(seconds=120), usegmt=True)
    gateway.http.responses = [limited(retry_after=hint)]
    with pytest.raises(HTTPException) as error:
        extract(gateway)
    assert 118 <= int(error.value.headers['Retry-After']) <= 120
    assert len(gateway.http.calls) == 1 and not gateway.retry_sleeps


@pytest.mark.parametrize('hint', ['-1', 'NaN', 'Infinity', '1e300', '2147483648',
    'malformed PRIVATE SOURCE', '9' * 129, 'Mon, 07 Sep 2026 10:00:00'])
def test_invalid_hint_is_not_echoed_or_persisted(gateway, hint):
    gateway.http.responses = [limited(retry_after=hint)]
    with pytest.raises(HTTPException) as error:
        extract(gateway)
    assert error.value.headers == {'X-WCP-Error':'RATE_LIMITED','X-WCP-Retryable':'true','X-WCP-Upstream-Status':'429'}
    usage = operation_rows(gateway)[0].usage
    assert 'retry_not_before' not in usage
    assert 'PRIVATE SOURCE' not in json.dumps(usage) + error.value.detail


def test_last_rejection_hint_wins_after_the_single_short_retry(gateway):
    gateway.http.responses = [limited(retry_after='1'), limited(retry_after='300')]
    with pytest.raises(HTTPException) as error:
        extract(gateway)
    assert error.value.headers == {'Retry-After':'300','X-WCP-Error':'RATE_LIMITED','X-WCP-Retryable':'true','X-WCP-Upstream-Status':'429'}
    assert gateway.retry_sleeps == [1] and len(gateway.http.calls) == 2


def test_http200_error_envelope_retains_safe_hint_without_retry(gateway):
    gateway.http.responses = [httpx.Response(200,
        request=httpx.Request('POST', 'https://provider.invalid'),
        headers={'Retry-After': '600'}, json={'error': {'code': 429, 'message': 'Capacity exceeded'}})]
    with pytest.raises(HTTPException) as error:
        extract(gateway)
    assert error.value.status_code == 503 and error.value.headers == {'Retry-After':'600','X-WCP-Error':'RATE_LIMITED','X-WCP-Retryable':'true','X-WCP-Upstream-Status':'200'}
    assert len(gateway.http.calls) == 1 and not gateway.retry_sleeps


def test_quota_rejection_uses_account_guidance_instead_of_immediate_retry(gateway):
    gateway.http.responses = [limited(reason='insufficient_quota', retry_after='60')]
    with pytest.raises(HTTPException) as error:
        extract(gateway)
    assert 'account limit' in error.value.detail and 'shortly' not in error.value.detail
    assert error.value.headers == {'Retry-After':'60','X-WCP-Error':'PROVIDER_QUOTA','X-WCP-Retryable':'false','X-WCP-Upstream-Status':'429'} and len(gateway.http.calls) == 1
