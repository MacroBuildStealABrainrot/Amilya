"""Speech-model comparison on the ISOLATED TEST SERVER only, with made-up noisy clinical recordings.

Runs once per label in the background at start-up when AMILYA_SPEECH_EVAL=<label> is set. It:
1. makes fictional recordings with text-to-speech (every word invented, no real person): a nursing home visit with a TV,
   a phone call, long silences and room hum; and a physician clinic visit with a ringing phone and louder noise;
2. sends each recording, as recorded and after Amilya's audio clean-up, to Whisper and to Mistral Voxtral, always with
   strict zero-data-retention routing;
3. logs one line per run starting with AMILYA_SPEECH_EVAL: clinical details heard, made-up filler, whether Whisper sent
   the confidence values the made-up-line filter needs, and the made-up transcript.
Nothing here is used by the app, it never runs outside the isolated test server, and it never touches patient data.
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

TTS_MODEL = 'mistralai/voxtral-mini-tts-2603'
WHISPER = 'openai/whisper-large-v3'
VOXTRAL_CANDIDATES = ['mistralai/voxtral-small-24b-2507']
VOICES = {'nurse': 'en_paul_neutral', 'doctor': 'en_paul_neutral', 'client': 'gb_jane_neutral', 'family': 'voice_478',
          'tv': 'en_paul_neutral', 'phone': 'gb_jane_neutral'}
VOLUME = {'nurse': 1.0, 'doctor': 1.0, 'client': 0.7, 'family': 0.8, 'tv': 0.5, 'phone': 0.6}

RECORDINGS = {
    'home_visit_tv': {
        'noise': 0.02, 'ring': False,
        'script': [
            ('tv', 'Captain, the engines are failing. We need to land this ship right now.', 2),
            ('tv', 'Not on my watch, sergeant. Hold the line.', 6),
            ('nurse', 'Hello, how are you feeling today?', 1), ('client', 'Not great. Very tired.', 2),
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
            ('nurse', 'Good. Keep up the fluids, about one litre a day. Your blood sugar today is six point four.', 8)],
        'clinical': {'tired': r'tired', 'no pain': r'pain[^.?]{0,15}\?\s*no|no pain', 'fever/chills': r'fever.{0,20}chills',
                     'cough/SOB': r'shortness of breath', 'toast': r'toast', 'BP 118/72': r'(118|one eighteen)[^0-9]{1,12}(72|seventy[- ]two)',
                     'wakes hourly to pee': r'every hour', 'urine light': r'\blight\b', 'chemo Monday': r'chemo\w*[^.]{0,40}monday',
                     'daughter helps': r'drinks', 'fluids 1 litre': r'(one|1)\s*(litre|liter|l\b)', 'sugar 6.4': r'(6\.4|six point four)'}},
    'clinic_physician_noise': {
        'noise': 0.05, 'ring': True,
        'script': [
            ('doctor', 'So you have had chest pain since yesterday?', 1), ('client', 'Yes, on the left side, when I walk upstairs.', 2),
            ('doctor', 'Does it spread to your arm or jaw?', 1), ('client', 'No.', 1),
            ('doctor', 'Any shortness of breath or sweating?', 1), ('client', 'A little short of breath, no sweating.', 4),
            ('doctor', 'Your blood pressure is one fifty over ninety, heart rate ninety two.', 2),
            ('doctor', 'You take metformin five hundred milligrams twice a day?', 1), ('client', 'Yes, and atorvastatin at night.', 5),
            ('doctor', 'I will order an ECG and troponin today, and refer you to cardiology.', 1),
            ('doctor', 'If the pain comes at rest, call nine one one.', 6)],
        'clinical': {'chest pain': r'chest pain', 'left side': r'\bleft\b', 'exertional': r'(walk|stairs|upstairs)',
                     'no radiation': r'(arm|jaw)[^.?]*\??\s*no|no[^.]{0,20}(arm|jaw)', 'some SOB': r'short of breath',
                     'no sweating': r'no sweat', 'BP 150/90': r'(150|one fifty)[^0-9]{1,12}(90|ninety)', 'HR 92': r'(92|ninety[- ]two)',
                     'metformin 500 BID': r'metformin[^.]{0,30}(500|five hundred)', 'atorvastatin': r'atorvastatin',
                     'ECG/troponin': r'(ecg|ekg)[^.]{0,30}troponin', 'cardiology': r'cardiolog', '911 advice': r'(911|nine one one)'}},
}
FILLER = r"thank you\.|thanks for watching|please subscribe|♪|\bsubtitles\b|\[music\]"


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


VOICE_CANDIDATES = ['en_paul_neutral', 'gb_jane_neutral', 'voice_478', 'en_paul_confident', 'gb_oliver_neutral', 'en_emma_neutral',
                    'paul', 'jane', 'default', 'alloy']
WORKING_VOICES: list[str] = []


async def find_voices():
    for voice in VOICE_CANDIDATES:
        for fmt in ('mp3', 'wav'):
            try:
                await _post('/audio/speech', {'model': TTS_MODEL, 'input': 'Test.', 'voice': voice, 'response_format': fmt})
                WORKING_VOICES.append(voice)
                break
            except RuntimeError as error:
                last = str(error)[:120]
        if len(WORKING_VOICES) >= 3:
            break
    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'voices_that_work': WORKING_VOICES}))
    return WORKING_VOICES


async def speak(words, speaker):
    # Invented words only. Mistral's speech maker is used because the account allows only private (ZDR) providers.
    order = ['nurse', 'client', 'family', 'doctor', 'tv', 'phone']
    voice = WORKING_VOICES[order.index(speaker) % len(WORKING_VOICES)]
    return await _post('/audio/speech', {'model': TTS_MODEL, 'input': words, 'voice': voice, 'response_format': 'mp3'})


async def make_recording(folder, name, spec):
    parts = []
    for index, (speaker, words, pause) in enumerate(spec['script']):
        response = await speak(words, speaker)
        raw = os.path.join(folder, f'{name}-{index}.mp3'); wav = os.path.join(folder, f'{name}-{index}.wav')
        with open(raw, 'wb') as handle:
            handle.write(response.content)
        _ffmpeg('-i', raw, '-af', f'volume={VOLUME[speaker]},apad=pad_dur={pause}', '-ar', '16000', '-ac', '1', wav)
        parts.append(wav)
    listing = os.path.join(folder, f'{name}-list.txt')
    with open(listing, 'w') as handle:
        handle.write(''.join(f"file '{p}'\n" for p in parts))
    speech = os.path.join(folder, f'{name}-speech.wav'); final = os.path.join(folder, f'{name}.m4a')
    _ffmpeg('-f', 'concat', '-safe', '0', '-i', listing, '-ar', '16000', '-ac', '1', speech)
    graph = f"anoisesrc=color=brown:amplitude={spec['noise']}:sample_rate=16000[n];[0:a][n]amix=inputs=2:duration=first:normalize=0[m]"
    out = '[m]'
    if spec['ring']:
        # A phone ringing for one second in every six, in the background.
        graph += ";sine=frequency=880:sample_rate=16000,volume='if(lt(mod(t,6),1),0.08,0)':eval=frame[r];[m][r]amix=inputs=2:duration=first:normalize=0[o]"
        out = '[o]'
    _ffmpeg('-i', speech, '-filter_complex', graph, '-map', out, '-ac', '1', '-c:a', 'aac', '-b:a', '64k', final)
    with open(final, 'rb') as handle:
        return handle.read()


async def whisper(data, mime):
    fmt = {'audio/mp4': 'm4a', 'audio/flac': 'flac'}[mime]
    response = await _post('/audio/transcriptions', {'model': WHISPER, 'input_audio': {'data': base64.b64encode(data).decode(), 'format': fmt},
                                                     'temperature': 0, 'response_format': 'verbose_json', 'timestamp_granularities': ['segment'],
                                                     'provider': {'zdr': True, 'data_collection': 'deny'}})
    payload = response.json()
    segments = payload.get('segments') or []
    keys = sorted(segments[0].keys()) if segments and isinstance(segments[0], dict) else []
    return str(payload.get('text') or ''), segments, keys


async def voxtral(data, mime, model):
    if mime != 'audio/flac':
        # Voxtral could not open the phone's m4a format on 2026-11-07: a plain FLAC copy, no filters, same sound.
        with tempfile.TemporaryDirectory() as folder:
            source, target = os.path.join(folder, 'in.m4a'), os.path.join(folder, 'out.flac')
            with open(source, 'wb') as handle:
                handle.write(data)
            _ffmpeg('-i', source, '-ac', '1', '-ar', '16000', '-c:a', 'flac', target)
            with open(target, 'rb') as handle:
                data, mime = handle.read(), 'audio/flac'
    fmt = 'flac'
    audio = {'data': base64.b64encode(data).decode(), 'format': fmt}
    try:
        response = await _post('/audio/transcriptions', {'model': model, 'input_audio': audio, 'temperature': 0,
                                                         'provider': {'zdr': True, 'data_collection': 'deny'}})
        return str(response.json().get('text') or '')
    except RuntimeError:
        # A Voxtral chat model hears audio through a normal request; asked for a word-for-word transcript only.
        body = {'model': model, 'temperature': 0, 'provider': {'zdr': True, 'data_collection': 'deny'},
            'messages': [{'role': 'user', 'content': [
                {'type': 'text', 'text': 'Transcribe this recording word for word. Output only the words spoken, nothing else.'},
                {'type': 'input_audio', 'input_audio': audio}]}]}
        for attempt in range(6):
            try:
                response = await _post('/chat/completions', body)
                break
            except RuntimeError as error:
                if '429' not in str(error) or attempt == 5:
                    raise
                await asyncio.sleep(45 * (attempt + 1))   # Voxtral is often busy: wait longer each time
        return str(((response.json().get('choices') or [{}])[0].get('message') or {}).get('content') or '')


async def deepgram(data, mime):
    """Deepgram Nova-3 Medical with speaker separation, exactly as the app would call it (model-improvement opt-out on)."""
    import httpx
    from .core import settings
    async with httpx.AsyncClient(timeout=180) as client:
        response = await client.post('https://api.deepgram.com/v1/listen', content=data, headers={'Authorization': 'Token ' + settings.deepgram_api_key,
            'Content-Type': mime}, params={'model': 'nova-3-medical', 'smart_format': 'true', 'utterances': 'true', 'mip_opt_out': 'true', 'diarize': 'true'})
    if response.status_code >= 400:
        raise RuntimeError(f'deepgram HTTP {response.status_code}: {response.text[:150]}')
    raw = response.json()
    text = raw['results']['channels'][0]['alternatives'][0]['transcript']
    turns = [(u.get('speaker'), u.get('transcript', '')) for u in raw['results'].get('utterances', [])]
    return text, turns


def speaker_report(turns, spec):
    """How well speakers were kept apart: for each scripted voice, which speaker numbers its lines landed on."""
    landed = {}
    for speaker, words in turns:
        for who, line, _ in spec['script']:
            first = ' '.join(line.lower().split()[:3])
            if first and first in words.lower():
                landed.setdefault(who, set()).add(speaker)
    return {who: sorted(s for s in found if s is not None) for who, found in landed.items()}


def score(text, clinical, segments=None):
    from .speech_prep import drop_guessed
    from .transcript_cleaning import clean_transcript
    working, _, dropped = drop_guessed(text, segments or [])
    cleaned = clean_transcript(working) or ''
    heard = [name for name, pattern in clinical.items() if re.search(pattern, cleaned, re.I)]
    return {'clinical_heard': f'{len(heard)}/{len(clinical)}', 'missed': [n for n in clinical if n not in heard],
            'made_up_filler_raw': len(re.findall(FILLER, text, re.I)), 'made_up_filler_after_amilya': len(re.findall(FILLER, cleaned, re.I)),
            'guessed_lines_dropped': dropped, 'words': len(cleaned.split())}


async def voxtral_models():
    import httpx
    from .core import settings
    async with httpx.AsyncClient(timeout=60) as client:
        response = await client.get(settings.openrouter_base_url.rstrip('/') + '/models')
    ids = [m.get('id', '') for m in (response.json().get('data') or [])] if response.status_code < 400 else []
    return [i for i in ids if 'voxtral' in i.lower()]


async def run(label):
    from .speech_prep import prepare
    voxtral_model = None
    found = await voxtral_models()
    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'voxtral_models_listed': found}))
    global VOXTRAL_CANDIDATES
    VOXTRAL_CANDIDATES = [m for m in found if 'tts' not in m.lower()] or VOXTRAL_CANDIDATES
    if not await find_voices():
        logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({'label': label, 'error': 'no voice accepted by the speech maker'}))
        return
    for name, spec in RECORDINGS.items():
        with tempfile.TemporaryDirectory() as folder:
            original = await make_recording(folder, name, spec)
        for audio_name, data, mime in (('as recorded', original, 'audio/mp4'),):
            line = {'label': label, 'recording': name, 'audio': audio_name}
            try:
                text, segments, keys = await whisper(data, mime)
                logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({**line, 'model': WHISPER, **score(text, spec['clinical'], segments),
                                                                     'segment_values_sent': keys, 'transcript': text[:1200]}))
            except Exception as error:
                logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({**line, 'model': WHISPER, 'error': str(error)[:200]}))
            from .core import settings
            if settings.deepgram_api_key:
                try:
                    text, turns = await deepgram(data, mime)
                    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({**line, 'model': 'deepgram nova-3-medical + speakers', **score(text, spec['clinical']),
                        'speakers_found': len({t[0] for t in turns}), 'speaker_of_each_voice': speaker_report(turns, spec),
                        'transcript': ' | '.join(f'S{t[0]}: {t[1]}' for t in turns)[:1500]}))
                except Exception as error:
                    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({**line, 'model': 'deepgram', 'error': str(error)[:200]}))
            if os.environ.get('AMILYA_SPEECH_EVAL_SKIP_VOXTRAL') == '1':
                continue
            for model in ([voxtral_model] if voxtral_model else VOXTRAL_CANDIDATES):
                try:
                    text = await voxtral(data, mime, model)
                    voxtral_model = model
                    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({**line, 'model': model, **score(text, spec['clinical']), 'transcript': text[:1200]}))
                    break
                except Exception as error:
                    logger.warning('AMILYA_SPEECH_EVAL %s', json.dumps({**line, 'model': model, 'error': str(error)[:200]}))
            await asyncio.sleep(60)   # keep well under Voxtral's busy limit
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
