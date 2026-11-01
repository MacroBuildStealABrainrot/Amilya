"""THROWAWAY reproduction (never merged). Fake data, fake provider, fake billing only.

Question: if the phone's connection drops while the server is writing a report, and the server cancels the work,
can the nurse's "Try again" (same request code) get HTTP 409 "already processing or awaiting reconciliation" forever?

These tests PASS when that problem is present: they assert the current behaviour and the exact counts
(results saved / AI calls made / charges made).
"""
import asyncio

from fastapi import HTTPException
import pytest
from sqlalchemy import select

from server import providers
from server.billing_models import UsageOperation
from server.provider_models import ProviderResult
from test_providers import completion, gateway  # noqa: F401  (fixture)

CONTEXT = {'case_facts': [], 'untrusted_sources': [{'kind': 'typed', 'text': 'Wound cleansed with NS. Dressed with Mepilex.'}],
           'rejected_claims_do_not_reinstate': []}
DRAFT = completion({'text': 'DRAFT — CLINICIAN REVIEW REQUIRED\nWound cleansed with NS. Dressed with Mepilex.'})
CHECK = completion({'passed': True, 'discrepancies': []})


def report(gateway):
    return asyncio.run(providers.quick_documentation(CONTEXT, 'nursing', 'concise', user=gateway.user, encounter_id='repro-409'))


def dropped(call):
    # The phone's connection ended and the server cancelled the work in progress.
    raise asyncio.CancelledError()


def counts(gateway):
    with gateway.sessions() as db:
        ops = list(db.scalars(select(UsageOperation)))
        results = list(db.scalars(select(ProviderResult)))
        return {'results_saved': len(results), 'ai_calls': len(gateway.http.calls),
                'charged_micros': sum(op.charged_micros or 0 for op in ops),
                'ops': sorted((op.task, op.status, op.release_reason or '') for op in ops)}


def test_cancelled_first_step_then_every_retry_is_409(gateway):
    gateway.http.responses = [dropped]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    before = counts(gateway)
    assert (before['results_saved'], before['ai_calls'], before['charged_micros']) == (0, 1, 0), before
    for attempt in range(1, 4):
        gateway.http.responses = [DRAFT, CHECK]
        with pytest.raises(HTTPException) as again:
            report(gateway)
        assert again.value.status_code == 409, f'retry {attempt}: {again.value.status_code} {again.value.detail}'
        assert 'already processing' in again.value.detail
    after = counts(gateway)
    # Nothing new was sent to the AI and nothing was charged: the nurse is simply stuck.
    assert (after['results_saved'], after['ai_calls'], after['charged_micros']) == (0, 1, 0), after
    assert ('quick_draft', 'released', 'provider_cancelled') in after['ops'], after


def test_cancelled_second_step_keeps_the_first_result_but_retry_is_409(gateway):
    gateway.http.responses = [DRAFT, dropped]
    with pytest.raises(asyncio.CancelledError):
        report(gateway)
    before = counts(gateway)
    assert before['results_saved'] == 1 and before['ai_calls'] == 2, before
    for attempt in range(1, 4):
        gateway.http.responses = [CHECK]
        with pytest.raises(HTTPException) as again:
            report(gateway)
        assert again.value.status_code == 409, f'retry {attempt}: {again.value.status_code} {again.value.detail}'
    after = counts(gateway)
    assert after['ai_calls'] == 2 and after['results_saved'] == 1, after
    assert after['charged_micros'] == before['charged_micros'], 'no second charge'
