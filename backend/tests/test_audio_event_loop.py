"""Item 7: blocking audio probes (ffprobe/ffmpeg) must never run on the event loop."""
import ast
import asyncio
import time
from io import BytesIO
from pathlib import Path
from uuid import uuid4
import wave

import pytest
from fastapi import HTTPException

from server import audio_parts, providers
from test_clinical import clinical_api
from test_quick_workflows import quick_api, start

BLOCKING = {'audio_duration', 'clip_audio', 'read_pdf_text'}
SUBPROCESS_CALLS = {'run', 'Popen', 'check_output', 'check_call', 'call'}
SERVER = Path(__file__).resolve().parents[1] / 'server'


def on_event_loop():
    try:
        asyncio.get_running_loop()
        return True
    except RuntimeError:
        return False


def silent_wav():
    stream = BytesIO()
    with wave.open(stream, 'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000); wav.writeframes(b'\0\0' * 1600)
    return stream.getvalue()


def no_speech_providers(monkeypatch):
    for name in ('cloudflare_api_token', 'cloudflare_account_id', 'groq_api_key', 'deepgram_api_key'):
        monkeypatch.setattr(providers.settings, name, '')
    monkeypatch.setattr(providers.settings, 'deepgram_enabled', False)


def direct_blocking_calls(source):
    """Direct calls to blocking helpers inside `async def` bodies. Passing the helper to
    asyncio.to_thread (as an argument, not a call) is the allowed pattern."""
    found = []
    for function in ast.walk(ast.parse(source)):
        if not isinstance(function, ast.AsyncFunctionDef):
            continue
        for node in ast.walk(function):
            if isinstance(node, ast.Call):
                target = node.func
                name = target.id if isinstance(target, ast.Name) else target.attr if isinstance(target, ast.Attribute) else ''
                if name in BLOCKING:
                    found.append((function.name, node.lineno))
                # Running a program (ffmpeg, ffprobe, a PDF reader...) directly inside async code freezes every other request.
                elif isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name) and target.value.id == 'subprocess' and name in SUBPROCESS_CALLS:
                    found.append((function.name, node.lineno))
    return found


def test_no_async_function_calls_a_blocking_audio_helper_directly():
    offenders = {}
    for path in sorted(SERVER.glob('*.py')):
        hits = direct_blocking_calls(path.read_text(encoding='utf-8'))
        if hits:
            offenders[path.name] = hits
    assert offenders == {}


def test_audio_part_probe_runs_off_the_event_loop(quick_api, monkeypatch):
    api = quick_api
    api.client.app.include_router(audio_parts.router, prefix='/api/v1')
    encounter = start(api)['encounter']['id']
    job = str(uuid4())
    seen = []

    def probe(_):
        seen.append(on_event_loop())
        return 120.0

    async def transcribe(*args, **kwargs):
        return {'text': 'No fever.', 'segments': [], 'warnings': []}

    monkeypatch.setattr(providers, 'audio_duration', probe)
    monkeypatch.setattr(providers, 'transcribe', transcribe)
    response = api.client.post(f'/api/v1/encounters/{encounter}/audio-parts',
        files={'file': ('synthetic.wav', silent_wav(), 'audio/wav')},
        data={'job_id': job, 'index': 0, 'count': 1, 'consent': 'true'})
    assert response.status_code == 200, response.text
    assert seen == [False]


def test_transcribe_probe_runs_off_the_event_loop(monkeypatch):
    no_speech_providers(monkeypatch)
    seen = []

    def probe(_):
        seen.append(on_event_loop())
        return 10.0

    monkeypatch.setattr(providers, 'audio_duration', probe)
    with pytest.raises(HTTPException) as error:
        asyncio.run(providers.transcribe(silent_wav(), 'audio/wav', 'synthetic.wav', user=None, encounter_id='e'))
    assert error.value.status_code == 503
    assert seen == [False]


def test_slow_probe_does_not_freeze_other_requests(monkeypatch):
    def slow_probe(_):
        time.sleep(0.3)
        return 31.0  # rejected right after the probe, so no provider is ever called

    monkeypatch.setattr(providers, 'audio_duration', slow_probe)
    ticks = 0

    async def heartbeat():
        nonlocal ticks
        while True:
            await asyncio.sleep(0.01)
            ticks += 1

    async def scenario():
        beat = asyncio.create_task(heartbeat())
        with pytest.raises(HTTPException) as error:
            await providers.verify_speech_clip(silent_wav(), user=None, encounter_id='e')
        beat.cancel()
        return error.value.status_code

    assert asyncio.run(scenario()) == 422
    assert ticks >= 10  # the loop kept running while the probe slept
