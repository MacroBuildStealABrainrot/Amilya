"""Speech-model comparison on the ISOLATED TEST SERVER only, with a made-up noisy home-visit recording.

Runs once per label in the background at start-up when AMILYA_SPEECH_EVAL=<label> is set. It:
1. makes a fictional recording with text-to-speech: a clinician and a client talking, a TV in the background,
   a phone call, long silences and a constant room hum (every word invented, no real person);
2. sends the same recording to each speech model being compared, and to Whisper again after silence is skipped;
3. logs one line per model starting with AMILYA_SPEECH_EVAL: how many clinical details were heard correctly,
   how many made-up filler phrases appeared, and the (made-up) transcript, so the owner can choose.
Nothing here is used by the app itself, and it never runs anywhere except the isolated test server.
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
import subprocess
import tempfile

logger = logging.getLogger('woundcopilot.speech_eval')

TTS_CANDIDATES = ['openai/gpt-4o-mini-tts', 'openai/gpt-4o-mini-tts-2025-12-15', 'google/gemini-3.1-flash-tts-preview', 'mistralai/voxtral-mini-tts-2603']
MODELS = ['openai/whisper-large-v3', 'openai/gpt-4o-mini-transcribe', 'openai/gpt-4o-transcribe']

# (speaker, words, seconds of silence after). Speakers: nurse, client, family, tv, phone. All invented.
SCRIPT = [
    ('tv', 'Captain, the engines are failing. We need to land this ship right now.', 2),
    ('tv', 'Not on my watch, sergeant. Hold the line.', 6),
    ('nurse', 'Hello, how are you feeling today?', 1),
    ('client', 'Not great. Very tired.', 2),
    ('nurse', 'Any pain today?', 1), ('client', 'No.', 1),
    ('nurse', 'Any fever or chills?', 1), ('client', 'No, none.', 5),
    ('phone', 'Hi, yes, the nurse is here right now. I will call you back later. Bye.', 6),
    ('nurse', 'Any cough or shortness of breath?', 1), ('client', 'No.', 1),
    ('nurse', 'Did you eat anything today?', 1), ('client', 'A little toast this morning.', 3),
    ('tv', 'Ladies and gentlemen, welcome back to the big game.', 2),
    ('nurse', 'Your blood pressure is one eighteen over seventy two.', 2),
    ('nurse', 'How are you sleeping?', 1), ('client', 'I wake up about every hour to pee.', 1),
    ('nurse', 'Is the urine dark?', 1), ('client', 'No, it is light.', 7),
    ('nurse', 'So your chemotherapy starts on Monday?', 1), ('client', 'Yes, Monday.', 1),
    ('family', 'I will keep helping her with drinks.', 1),
    ('nurse', 'Good. Keep up the fluids, about one litre a day. Your blood sugar today is six point four.', 8),
]
VOICES = {'nurse': ('alloy', 'Calm, clear nurse.'), 'client': ('shimmer', 'Older, tired, quiet patient.'),
          'family': ('echo', 'Caring adult daughter.'), 'tv': ('onyx', 'Dramatic TV actor, slightly distant.'),
          'phone': ('ash', 'Speaking on the phone across the room.')}
VOLUME = {'nurse': 1.0, 'client': 0.75, 'family': 0.8, 'tv': 0.45, 'phone': 0.6}

CLINICAL = {'tired': r'tired', 'no pain': r'pain\?\s*no|no pain|pain[^.?]*\?[^a-z]*no', 'fever/chills': r'fever.{0,20}chills',
            'cough/SOB': r'shortness of breath', 'toast': r'toast', 'BP 118/72': r'(118|one eighteen)[^0-9]{1,12}(72|seventy[- ]two)',
            'wakes hourly to pee': r'every hour', 'urine light': r'light', 'chemo Monday': r'chemo\w*[^.]{0,40}monday|monday',
            'daughter helps': r'helping her|drinks', 'fluids 1 litre': r'(one|1)\s*(litre|liter|l\b)', 'sugar 6.4': r'(6\.4|six point four)'}
FILLER = r"thank you\.|thanks for watching|please subscribe|♪|\bsubtitles\b|\[music\]|\byou\s*$"


def _ffmpeg(*args):
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', *args], check=True, timeout=180)


async def _post(path, body):
    import httpx
    from .core import settings
    async with httpx.AsyncClient(timeout=180, follow_redirects=False) as client:
        response = await client.post(settings.openrouter_base_url.rstrip('/') + path,
                                     headers={'Authorization': 'Bearer ' + settings.openrouter_api_key}, json=body)
    if response.status_code >= 400:
        raise RuntimeError(f'{path} HTTP {response.status_code}: {response.text[:150]}')
    return response


async def available_models():
    import httpx
    from .core import settings
    async with httpx.AsyncClient(timeout=60) as client:
        response = await client.get(settings.openrouter_base_url.rstrip('/') + '/models',
                                    headers={'Authorization': 'Bearer ' + settings.openrouter_api_key})
    ids = [m.get('id', '') for m in (response.json().get('data') or [])] if response.status_code < 400 else []
    return [i for i in ids if re.search(r'tts|speech|transcri|whisper|chirp|voxtral', i, re.I)]


async def speak(model, words, speaker):
    voice, style = VOICES[speaker]
    body = {'model': model, 'input': words, 'response_format': 'mp3'}  # invented words only, so no privacy routing is needed
    if model.startswith('openai/'):
        body.update({'voice': voice, 'instructions': style})
    return await _post('/audio/speech', body)


async def make_recording(folder, tts_model):
    parts = []
    for index, (speaker, words, pause) in enumerate(SCRIPT):
        response = await speak(tts_model, words, speaker)
        raw = os.path.join(folder, f'{index}.mp3'); wav = os.path.join(folder, f'{index}.wav')
        with open(raw, 'wb') as handle:
            handle.write(response.content)
        _ffmpeg('-i', raw, '-af', f'volume={VOLUME[speaker]},apad=pad_dur={pause}', '-ar', '16000', '-ac', '1', wav)
        parts.append(wav)
    listing = os.path.join(folder, 'list.txt')
    with open(listing, 'w') as handle:
        handle.write(''.join(f"file '{p}'\n" for p in parts))
    speech = os.path.join(folder, 'speech.wav'); final = os.path.join(folder, 'visit.mp3')
    _ffmpeg('-f', 'concat', '-safe', '0', '-i', listing, '-ar', '16000', '-ac', '1', speech)
    # A constant quiet room hum under everything, like a fridge or fan in a home.
    _ffmpeg('-i', speech, '-f', 'lavfi', '-i', 'anoisesrc=color=brown:amplitude=0.02:sample_rate=16000',
            '-filter_complex', '[0][1]amix=inputs=2:duration=first:normalize=0', '-ac', '1', '-b:a', '64k', final)
    trimmed = os.path.join(folder, 'visit-no-silence.mp3')
    _ffmpeg('-i', final, '-af', 'silenceremove=start_periods=1:stop_periods=-1:stop_duration=0.8:stop_threshold=-38dB', '-b:a', '64k', trimmed)
    return final, trimmed


async def transcribe(path, model):
    with open(path, 'rb') as handle:
        data = base64.b64encode(handle.read()).decode()
    body = {'model': model, 'input_audio': {'data': data, 'format': 'mp3'}, 'temperature': 0,
            'provider': {'zdr': True, 'data_collection': 'deny'}}
    private = True
    try:
        response = await _post('/audio/transcriptions', body)
    except RuntimeError:
        # Recorded for the owner: a model that cannot run under zero-data-retention is a privacy point against it.
        private = False
        response = await _post('/audio/transcriptions', {k: v for k, v in body.items() if k != 'provider'})
    payload = response.json()
    return str(payload.get('text') or ''), {'usage': payload.get('usage'), 'zero_data_retention': private}


def score(text):
    from .transcript_cleaning import clean_transcript
    cleaned = clean_transcript(text) or ''
    heard = [name for name, pattern in CLINICAL.items() if re.search(pattern, text, re.I)]
    return {'clinical_heard': f'{len(heard)}/{len(CLINICAL)}', 'missed': [n for n in CLINICAL if n not in heard],
            'made_up_filler': len(re.findall(FILLER, text, re.I | re.M)),
            'filler_after_free_cleaning': len(re.findall(FILLER, cleaned, re.I | re.M)), 'words': len(text.split())}


async def run(label):
    audio_models = await available_models()
    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'audio_models_listed': audio_models[:40]}))
    tts_model, last_error = None, ''
    for candidate in [m for m in audio_models if re.search(r'tts|speech', m, re.I)] + TTS_CANDIDATES:
        try:
            await speak(candidate, 'Test.', 'nurse')
            tts_model = candidate
            break
        except Exception as error:
            last_error = str(error)[:160]
    if tts_model is None:
        logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'error': 'no text-to-speech model worked', 'detail': last_error}))
        return
    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'voices_made_with': tts_model}))
    with tempfile.TemporaryDirectory() as folder:
        recording, trimmed = await make_recording(folder, tts_model)
        variants = [(model, recording) for model in MODELS] + [('openai/whisper-large-v3 + silence skipped', trimmed)]
        for name, path in variants:
            try:
                text, usage = await transcribe(path, name.split(' ')[0])
                logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'model': name, **score(text),
                                                                     **usage, 'transcript': text[:1500]}))
            except Exception as error:
                logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'model': name, 'error': type(error).__name__,
                                                                     'detail': str(error)[:200]}))
            await asyncio.sleep(3)
    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'finished': True}))


def _already_ran(label):
    from .db import SessionLocal
    from .clinical_models import Patient
    from .identity import User
    from sqlalchemy import select
    with SessionLocal() as db:
        user = db.scalar(select(User).where(User.is_active.is_(True)).order_by(User.created_at))
        if user is None:
            return True
        marker_id = f'speecheval-{user.workspace_id}'[:36]
        marker = db.get(Patient, marker_id)
        if marker is not None and marker.external_ref == f'speech-done:{label}':
            return True
        if marker is None:
            marker = Patient(id=marker_id, workspace_id=user.workspace_id, display_name='Made-up speech evaluation marker', external_ref=None)
            db.add(marker)
        marker.external_ref = f'speech-done:{label}'  # marked first: a restart mid-run never pays twice
        db.commit()
        return False


def start_if_requested():
    from . import isolation
    label = os.environ.get('AMILYA_SPEECH_EVAL', '').strip()
    if not label or not re.fullmatch(r'[A-Za-z0-9_.-]{1,40}', label) or not isolation.is_isolated_test_server():
        return None

    async def later():
        await asyncio.sleep(20)
        try:
            if await asyncio.to_thread(_already_ran, label):
                logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'skipped': 'already run for this label'}))
                return
            await run(label)
        except Exception as error:
            logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'error': type(error).__name__, 'detail': str(error)[:200]}))
    return asyncio.create_task(later())
