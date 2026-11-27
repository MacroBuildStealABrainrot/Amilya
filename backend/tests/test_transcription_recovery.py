"""Local gateway and database with synthetic speech and a fully intercepted provider."""
import asyncio

from fastapi import HTTPException
import pytest
from sqlalchemy import select

from server import providers
from server.billing_models import UsageOperation
from test_providers import gateway  # noqa: F401


@pytest.fixture
def speech(gateway):
    gateway.config.cloudflare_api_token = 'synthetic'
    gateway.config.cloudflare_account_id = 'synthetic'
    return lambda: providers.speech_attempt('cloudflare', b'synthetic voice', 'audio/wav', 'test.wav', 1,
                                           user=gateway.user, encounter_id='synthetic-voice')


def test_cancelled_before_dispatch_resumes_once_and_replays(gateway, speech, monkeypatch):
    async def cancelled_before_transport(self):
        raise asyncio.CancelledError()

    with monkeypatch.context() as patch:
        patch.setattr(type(gateway.http), '__aenter__', cancelled_before_transport)
        with pytest.raises(asyncio.CancelledError):
            asyncio.run(speech())
    assert not gateway.http.calls
    with gateway.sessions() as db:
        prior = db.scalar(select(UsageOperation))
        assert (prior.status, prior.release_reason, prior.charged_micros) == ('released', 'speech_not_dispatched', 0)

    async def recover():
        started = asyncio.Event()
        finish = asyncio.Event()

        async def response(_):
            started.set()
            await finish.wait()
            return {'success': True, 'result': {'text': 'Synthetic conversation.'}}

        gateway.http.responses = [response]
        first = asyncio.create_task(speech())
        await asyncio.wait_for(started.wait(), 2)
        try:
            with pytest.raises(HTTPException) as waiting:
                await speech()
            assert waiting.value.status_code == 409
            assert waiting.value.headers['X-Amilya-Transcription-State'] == 'pending'
        finally:
            finish.set()
        assert (await first)['text'] == 'Synthetic conversation.'
        assert (await speech())['text'] == 'Synthetic conversation.'

    asyncio.run(recover())
    assert len(gateway.http.calls) == 1


def test_dispatched_cancellation_keeps_unknown_vendor_outcome_and_never_restarts(gateway, speech):
    def stop_after_dispatch(_):
        raise asyncio.CancelledError()

    gateway.http.responses = [stop_after_dispatch]
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(speech())
    with gateway.sessions() as db:
        prior = db.scalar(select(UsageOperation))
        assert (prior.status, prior.release_reason, prior.charged_micros) == ('released', 'provider_cancelled', 0)
    for _ in range(2):
        with pytest.raises(HTTPException) as stopped:
            asyncio.run(speech())
        assert stopped.value.status_code == 409
        assert stopped.value.headers['X-Amilya-Transcription-State'] == 'needs_review'
    assert len(gateway.http.calls) == 1


def test_repeated_undispatched_cancellations_stop_at_existing_budget(gateway, speech, monkeypatch):
    async def stop_before_send(self):
        raise asyncio.CancelledError()

    monkeypatch.setattr(type(gateway.http), '__aenter__', stop_before_send)
    for _ in range(1 + providers.MAX_MEDIA_RETRIES):
        with pytest.raises(asyncio.CancelledError):
            asyncio.run(speech())
    with pytest.raises(HTTPException) as stopped:
        asyncio.run(speech())
    assert stopped.value.status_code == 409
    assert stopped.value.headers['X-Amilya-Transcription-State'] == 'needs_review'
    assert not gateway.http.calls
