"""Regression: a report that failed at the AI provider must be retryable with the same request key.

Before the fix, a timed-out (504) or failed (502) report stage was settled as a waived failure and every
later attempt with the same Idempotency-Key replayed that failure without calling the AI again, so a
nurse tapping "Try again" (or the phone resending after a dropped connection) could never get an AI report.
"""
import asyncio

from fastapi import HTTPException
import httpx
import pytest
from sqlalchemy import select

from server import providers
from server.billing_models import UsageOperation
from test_providers import completion, gateway  # noqa: F401  (fixture)

CONTEXT = {'case_facts': [], 'untrusted_sources': [{'kind': 'typed', 'text': 'Wound cleansed with NS. Dressed with Mepilex.'}],
           'rejected_claims_do_not_reinstate': []}
GOOD = [completion({'text': 'DRAFT — CLINICIAN REVIEW REQUIRED\nWound cleansed with NS. Dressed with Mepilex.'}),
        completion({'passed': True, 'discrepancies': []})]


def report(gateway):
    return asyncio.run(providers.quick_documentation(CONTEXT, 'nursing', 'concise', user=gateway.user, encounter_id='retry-report'))


@pytest.mark.parametrize('failure', [
    lambda call: httpx.ReadTimeout('synthetic timeout'),
    lambda call: (502, {'error': {'message': 'synthetic upstream failure'}}),
    lambda call: (503, {'error': {'message': 'synthetic upstream overload'}}),
])
def test_failed_report_stage_is_retried_with_the_same_request_key(gateway, failure):
    gateway.http.responses = [failure]
    with pytest.raises(HTTPException) as first:
        report(gateway)
    assert first.value.status_code in {502, 503, 504}
    calls_after_failure = len(gateway.http.calls)
    gateway.http.responses = list(GOOD)
    result = report(gateway)
    assert 'Mepilex' in result['text']
    assert len(gateway.http.calls) > calls_after_failure, 'retry must reach the AI again'
    with gateway.sessions() as db:
        rows = list(db.scalars(select(UsageOperation)))
    failed = [r for r in rows if r.usage.get('attempts', [{}])[-1].get('status') == 'failed']
    assert failed and all(r.charged_micros == 0 for r in failed), 'a failed attempt is never charged'


def test_retry_attempts_are_bounded(gateway):
    # Agreed limit: 3 attempts in total per report step, including the first one.
    assert providers.MAX_REPORT_STAGE_ATTEMPTS == 3
    for _ in range(providers.MAX_REPORT_STAGE_ATTEMPTS):
        gateway.http.responses = [lambda call: httpx.ReadTimeout('synthetic timeout')]
        with pytest.raises(HTTPException):
            report(gateway)
    before = len(gateway.http.calls)
    assert before == 3, 'exactly 3 paid attempts in total for one report step'
    gateway.http.responses = list(GOOD)
    with pytest.raises(HTTPException) as error:
        report(gateway)
    assert len(gateway.http.calls) == before, 'after the retry limit no new paid request is made'
    assert error.value.status_code in {502, 503, 504}


def test_successful_report_is_replayed_not_regenerated(gateway):
    gateway.http.responses = list(GOOD)
    first = report(gateway)
    calls = len(gateway.http.calls)
    second = report(gateway)
    assert second['text'] == first['text'] and len(gateway.http.calls) == calls
