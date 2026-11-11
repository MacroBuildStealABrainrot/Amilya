"""Synthetic only. All external HTTP is intercepted; no credentials or paid calls."""
import asyncio
from pathlib import Path
import base64
import hashlib

import pytest
from fastapi import HTTPException
from sqlalchemy import select
from server import providers
from server.billing_models import UsageOperation
from test_providers import gateway

FIXTURE = Path(__file__).parents[1] / 'scripts/fixtures/render-synthetic-visit.m4a.b64'


def audio():
    return base64.b64decode(FIXTURE.read_bytes())


def transcript(**changes):
    return {'text':'Synthetic software test only. The dressing was changed. Pain score is two out of ten.',
        'segments':[{'start':0.0,'end':5.0,'text':'The dressing was changed. Pain score is two out of ten.'}],
        'usage':{'seconds':5.312,'cost':.00003984}, **changes}


def enable(gateway):
    gateway.config.openrouter_speech_enabled = True


def call(gateway):
    return asyncio.run(providers.transcribe(audio(), 'audio/mp4', 'synthetic.m4a',
        user=gateway.user, encounter_id='synthetic-audio'))


def test_fixture_is_fixed_artificial_voice_and_decodes_locally(gateway):
    assert hashlib.sha256(audio()).hexdigest() == '4d8d5e1eadac65c3660cb71c07c2f224c578994dbf67f5f348b446acef21246a'
    assert providers.audio_duration(audio()) == pytest.approx(5.312, abs=.01)


def test_same_key_transcription_preserves_privacy_and_records_actual_cost(gateway):
    enable(gateway)
    gateway.http.responses = [transcript()]
    result = call(gateway)
    assert 'two out of ten' in result['text']
    request = gateway.http.calls[0]
    assert request['url'].endswith('/audio/transcriptions')
    payload = request['json']
    assert payload['model'] == providers.OPENROUTER_SPEECH_MODEL
    assert payload['provider'] == {'zdr':True, 'data_collection':'deny'}
    assert payload['response_format'] == 'verbose_json'
    assert base64.b64decode(payload['input_audio']['data']) == audio()
    assert payload['input_audio']['format'] == 'm4a'
    assert 'prompt' not in payload and 'filename' not in payload
    with gateway.sessions() as db:
        operation = db.scalar(select(UsageOperation))
        assert operation.actual_cost_micros == 40
        assert operation.normalized_micros == 164
        assert not operation.usage.get('charge_waived')
    assert call(gateway)['text'] == result['text']
    assert len(gateway.http.calls) == 1


def test_default_disabled_and_zdr_cannot_be_relaxed(gateway):
    with pytest.raises(HTTPException) as exc: call(gateway)
    assert exc.value.status_code == 503 and not gateway.http.calls
    enable(gateway)
    gateway.config.openrouter_require_zdr = False
    with pytest.raises(HTTPException) as exc: call(gateway)
    assert exc.value.status_code == 503 and not gateway.http.calls


@pytest.mark.parametrize('changes', [dict(text=None), dict(segments='bad'),
    dict(segments=[{'start':float('nan'),'end':2,'text':'Synthetic'}]),
    dict(segments=[{'start':0,'end':90,'text':'Synthetic'}]),
    dict(model='invented-model-private-words')])
def test_invalid_transcripts_fail_without_charge(gateway, changes):
    enable(gateway)
    gateway.http.responses = [transcript(**changes)]
    with pytest.raises(HTTPException) as exc: call(gateway)
    assert exc.value.status_code == 502
    with gateway.sessions() as db:
        operation = db.scalar(select(UsageOperation))
        assert operation.charged_micros == 0
        assert operation.usage.get('charge_waived') is True


def test_http200_error_envelope_is_not_a_transcript_or_blindly_retried(gateway):
    enable(gateway)
    gateway.http.responses = [{'error':{'code':503,'message':'PRIVATE'}}]
    with pytest.raises(HTTPException) as exc: call(gateway)
    assert exc.value.status_code == 502
    assert 'PRIVATE' not in str(exc.value)
    assert len(gateway.http.calls) == 1


def test_missing_vendor_cost_is_unknown_not_zero(gateway):
    enable(gateway)
    gateway.http.responses = [transcript(usage={})]
    call(gateway)
    with gateway.sessions() as db:
        assert db.scalar(select(UsageOperation)).actual_cost_micros is None


def test_repeat_clip_is_never_claimed_as_an_independent_verifier(gateway):
    enable(gateway)
    gateway.http.responses = [transcript()]
    result = asyncio.run(providers.verify_speech_clip(audio(), user=gateway.user,
        encounter_id='synthetic-clip'))
    assert result['verifier']['method'] == 'repeat_openrouter_transcription'
    assert any('not an independent' in w for w in result['warnings'])
