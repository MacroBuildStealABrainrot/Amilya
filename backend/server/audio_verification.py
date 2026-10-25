"""Short, source-bound speech verification with an explicit clinician decision.

The configured speech service receives only a decoded <=30-second clip. The
original transcript is not supplied to the speech service or silently replaced.
Proposals and accepted excerpts are separate immutable Evidence records.
"""
from __future__ import annotations

import asyncio
import io
import math
from pathlib import Path
from hashlib import sha256
import re
import shutil
import subprocess
import tempfile
import wave
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from pydantic import Field, StringConstraints
from sqlalchemy import select
from sqlalchemy.orm import Session

from server import clinical, providers
from server.clinical_models import Encounter, Evidence
from server.core import settings
from server.db import get_db, uid
from server.identity import User, check_rate, current_user, require_csrf
from server.schemas import StrictModel

router = APIRouter(tags=['audio verification'])
MAX_ORIGINAL_BYTES = 25 * 1024 * 1024
MAX_CLIP_BYTES = 1_100_000
MAX_WINDOW_SECONDS = 30


class AcceptVerification(StrictModel):
    expected_case_revision: int = Field(ge=1)
    accepted_text: Annotated[str, StringConstraints(min_length=1, max_length=8000)]


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def validate_window(start: float, end: float):
    if not finite_number(start) or not finite_number(end) or start < 0 or not 0 < end - start <= MAX_WINDOW_SECONDS:
        raise HTTPException(422, 'Choose a finite audio window longer than zero and no more than 30 seconds')


def clip_audio(data: bytes, start: float, end: float) -> tuple[bytes, float]:
    """Bounded local decode; neither a user filename nor a URL reaches the decoder."""
    validate_window(start, end)
    if not data or len(data) > MAX_ORIGINAL_BYTES:
        raise HTTPException(413, 'The original audio must be nonempty and no larger than 25 MB')
    original_duration = providers.audio_duration(data)
    if end > original_duration + .025:
        raise HTTPException(422, 'The verification window extends beyond the original recording')
    executable = shutil.which('ffmpeg')
    if not executable:
        raise HTTPException(503, 'The short-audio verification runtime is not configured')
    try:
        with tempfile.TemporaryDirectory(prefix='wcp-audio-window-') as directory:
            original = Path(directory) / 'original.media'
            clipped = Path(directory) / 'clip.wav'
            original.write_bytes(data)
            command = [executable, '-nostdin', '-hide_banner', '-loglevel', 'error',
                       '-protocol_whitelist', 'file,pipe', '-threads', '1', '-filter_threads', '1',
                       '-ss', f'{start:.6f}', '-i', str(original), '-t', f'{end - start:.6f}',
                       '-map', '0:a:0', '-vn', '-sn', '-dn', '-ac', '1', '-ar', '16000',
                       '-c:a', 'pcm_s16le', '-f', 'wav', str(clipped)]
            subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                           timeout=20, check=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            with clipped.open('rb') as stream:
                clip = stream.read(MAX_CLIP_BYTES + 1)
        if not clip or len(clip) > MAX_CLIP_BYTES:
            raise ValueError('Invalid clip size')
        with wave.open(io.BytesIO(clip), 'rb') as audio:
            if audio.getnchannels() != 1 or audio.getframerate() != 16000 or audio.getsampwidth() != 2 or audio.getcomptype() != 'NONE':
                raise ValueError('Unexpected decoded audio format')
            duration = audio.getnframes() / audio.getframerate()
        if not 0 < duration <= MAX_WINDOW_SECONDS or duration > end - start + .025:
            raise ValueError('Decoded clip exceeds selected window')
        return clip, duration
    except (OSError, ValueError, EOFError, wave.Error, subprocess.SubprocessError) as error:
        raise HTTPException(422, 'The selected audio window could not be safely decoded') from error


def original_excerpt(source: Evidence, start: float, end: float):
    selected = []
    for segment in source.metadata_json.get('segments', [])[:1000]:
        if not isinstance(segment, dict):
            continue
        begin, finish = segment.get('start'), segment.get('end')
        if finite_number(begin) and finite_number(finish) and begin < end and finish > start and isinstance(segment.get('text'), str):
            selected.append(segment['text'])
    if selected:
        return ' '.join(selected)[:8000], 'overlapping_original_segments'
    return (source.text or '')[:8000], 'full_transcript_context_without_aligned_segments'


def comparison(original: str, candidate: str, term: str, scope: str):
    normalized = lambda value: re.sub(r'\s+', ' ', value).strip().casefold()
    notices = ['This is a second transcription by the configured speech service, not an independent medical expert assessment.',
               'Compare the audio and both texts. Clinician acceptance does not automatically confirm extracted clinical facts.']
    if scope != 'overlapping_original_segments':
        notices.append('Original segment timing is unavailable; the original text is context and may cover more than this clip.')
    if re.findall(r'\d+(?:\.\d+)?', original) != re.findall(r'\d+(?:\.\d+)?', candidate):
        notices.append('Number tokens differ. Verify measurements, units and doses directly against the audio.')
    negatives = lambda value: re.findall(r'\b(?:no|not|denies|without|negative)\b', value.casefold())
    if negatives(original) != negatives(candidate):
        notices.append('Negation tokens differ. Verify whether each finding was present, absent or uncertain.')
    return {'changed': normalized(original) != normalized(candidate),
            'original_contains_term': normalized(term) in normalized(original),
            'candidate_contains_term': normalized(term) in normalized(candidate), 'notices': notices}


def absolute_segments(segments, start, duration):
    output = []
    for segment in segments[:1000] if isinstance(segments, list) else []:
        if not isinstance(segment, dict) or not isinstance(segment.get('text'), str):
            continue
        begin, finish = segment.get('start'), segment.get('end')
        if not finite_number(begin) or not finite_number(finish) or not 0 <= begin <= finish <= duration + .025:
            continue
        item = {'text': segment['text'][:8000], 'start': round(start + begin, 6), 'end': round(start + finish, 6),
                'clip_start': begin, 'clip_end': finish}
        speaker = segment.get('speaker')
        if isinstance(speaker, (str, int)) and not isinstance(speaker, bool):
            item['speaker'] = str(speaker)[:80]
        confidence = segment.get('confidence')
        if finite_number(confidence) and 0 <= confidence <= 1:
            item['confidence'] = confidence
        output.append(item)
    return output


def acceptance_key(proposal_id):
    return 'audio-verification-accept:' + proposal_id


def accepted_evidence(db, proposal):
    return db.scalar(select(Evidence).where(Evidence.workspace_id == proposal.workspace_id,
        Evidence.encounter_id == proposal.encounter_id, Evidence.request_key == acceptance_key(proposal.id)))


def proposal_out(db, encounter, proposal):
    metadata = proposal.metadata_json
    accepted = accepted_evidence(db, proposal)
    return {'verification_id': proposal.id, 'source_evidence_id': metadata['source_evidence_id'],
            'original_text': metadata['original_text'], 'original_text_scope': metadata['original_text_scope'],
            'candidate_text': proposal.text, 'uncertain_term': metadata['uncertain_term'], 'context': metadata['context'],
            'window': metadata['window'], 'comparison': metadata['comparison'], 'segments': metadata['segments'],
            'status': 'accepted' if accepted else 'pending_clinician_review',
            'accepted_evidence_id': accepted.id if accepted else None, 'case_revision': encounter.revision,
            'usage': metadata['usage'], 'verifier': metadata['verifier']}


@router.post('/encounters/{encounter_id}/audio-verifications', status_code=201, dependencies=[Depends(require_csrf)])
async def verify_audio_window(encounter_id: str, request: Request, file: UploadFile = File(...),
        source_evidence_id: str = Form(...), start_seconds: float = Form(...), end_seconds: float = Form(...),
        uncertain_term: str = Form(...), context: str = Form(''), consent: bool = Form(False), verifier: str = Form('stronger'),
        user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = clinical.owned(db, Encounter, encounter_id, user)
    source = clinical.owned(db, Evidence, source_evidence_id, user)
    if source.encounter_id != encounter.id or source.kind != 'audio':
        raise HTTPException(422, 'Select original audio evidence from this encounter')
    if not consent:
        raise HTTPException(403, 'Consent is required before processing the audio again')
    if verifier not in {'stronger', 'medical'}:
        raise HTTPException(422, 'Choose stronger speech verification or explicit medical transcription')
    validate_window(start_seconds, end_seconds)
    uncertain_term, context = uncertain_term.strip(), context.strip()
    if not 1 <= len(uncertain_term) <= 200 or len(context) > 2000:
        raise HTTPException(422, 'Describe the uncertain term in 1–200 characters and use no more than 2,000 characters of context')
    key = clinical.request_key(request)
    if not key:
        raise HTTPException(422, 'An Idempotency-Key is required for short-audio verification')
    data = await file.read(min(MAX_ORIGINAL_BYTES, settings.max_audio_mb * 1024 * 1024) + 1)
    if not data or len(data) > min(MAX_ORIGINAL_BYTES, settings.max_audio_mb * 1024 * 1024):
        raise HTTPException(413, 'The original audio must be nonempty and no larger than 25 MB')
    mime = (file.content_type or '').lower().split(';', 1)[0].strip()
    clinical.checked_media(data, mime, 'audio')
    original_hash = sha256(data).hexdigest()
    if original_hash != source.metadata_json.get('media_hash'):
        raise HTTPException(422, 'This file does not match the original audio evidence')
    request_hash = clinical.digest([source.id, original_hash, start_seconds, end_seconds, uncertain_term, context, consent, verifier])
    key = 'audio-verification:' + clinical.content_hash(key)
    previous = clinical.previous_request(db, Evidence, user, encounter.id, key, request_hash)
    if previous:
        if previous.kind != 'audio_verification':
            raise HTTPException(409, 'This request identity belongs to another evidence action')
        return proposal_out(db, encounter, previous)
    check_rate('audio-verification:' + user.id, limit=10)
    if not settings.provider_execution_enabled:
        raise HTTPException(503, 'Live speech verification is paused. Review the original audio manually.')
    expected = encounter.revision
    # ffprobe/ffmpeg are blocking subprocesses: keep them off the event loop.
    clip, duration = await asyncio.to_thread(clip_audio, data, start_seconds, end_seconds)
    # The uncertainty and original transcript are deliberately not sent to speech.
    result = await providers.verify_speech_clip(clip, user=user, encounter_id=encounter.id, verifier=verifier)
    candidate = result.get('text')
    if not isinstance(candidate, str) or not candidate.strip() or len(candidate) > 8000:
        raise HTTPException(502, 'The speech service returned no usable short-window transcript')
    original, scope = original_excerpt(source, start_seconds, end_seconds)
    segments = absolute_segments(result.get('segments'), start_seconds, duration)
    clinical.lock_case(db, encounter, expected)
    proposal = Evidence(id=uid(), workspace_id=user.workspace_id, encounter_id=encounter.id, wound_id=source.wound_id,
        kind='audio_verification', text=candidate, created_by=user.id, request_key=key, request_hash=request_hash,
        metadata_json={'source_evidence_id': source.id, 'source_media_hash': original_hash, 'clip_hash': sha256(clip).hexdigest(),
            'window': {'start_seconds': start_seconds, 'end_seconds': end_seconds, 'duration_seconds': duration},
            'original_text': original, 'original_text_scope': scope, 'uncertain_term': uncertain_term, 'context': context,
            'comparison': comparison(original, candidate, uncertain_term, scope), 'segments': segments,
            'usage': result.get('usage', {}), 'verifier': result['verifier'], 'raw_media_retained': False, 'consent': True,
            'method': 'bounded_speech_verification_with_local_comparison'},
        warnings=['Unaccepted verification candidate. The original transcript and clinical facts remain unchanged.'])
    db.add(proposal)
    clinical.timeline(db, user, encounter.id, 'audio.verification.propose', proposal.id,
        {'source_evidence_id': source.id, 'start_seconds': start_seconds, 'end_seconds': end_seconds, 'clip_seconds': duration})
    clinical.finish(db)
    return proposal_out(db, encounter, proposal)


@router.get('/audio-verifications/{verification_id}')
def get_verification(verification_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    proposal = clinical.owned(db, Evidence, verification_id, user)
    if proposal.kind != 'audio_verification':
        raise HTTPException(404, 'Audio verification not found')
    return proposal_out(db, clinical.owned(db, Encounter, proposal.encounter_id, user), proposal)


@router.post('/audio-verifications/{verification_id}/accept', dependencies=[Depends(require_csrf)])
def accept_verification(verification_id: str, payload: AcceptVerification, user: User = Depends(current_user), db: Session = Depends(get_db)):
    proposal = clinical.owned(db, Evidence, verification_id, user)
    if proposal.kind != 'audio_verification':
        raise HTTPException(404, 'Audio verification not found')
    encounter = clinical.owned(db, Encounter, proposal.encounter_id, user)
    if not payload.accepted_text.strip():
        raise HTTPException(422, 'Enter the excerpt you reviewed against the audio')
    request_hash = clinical.digest([proposal.id, payload.accepted_text])
    previous = accepted_evidence(db, proposal)
    if previous:
        if previous.request_hash != request_hash:
            raise HTTPException(409, 'This verification was already accepted with different text')
        return {'verification_id': proposal.id, 'status': 'accepted', **clinical.source_response(db, encounter, previous)}
    check_rate('audio-verification-accept:' + user.id, limit=30)
    clinical.lock_case(db, encounter, payload.expected_case_revision)
    candidates, warnings = clinical.extract_explicit(payload.accepted_text, 'transcript')
    metadata = proposal.metadata_json
    for candidate in candidates:
        candidate['locator'] = {'verification_id': proposal.id, 'source_evidence_id': metadata['source_evidence_id'],
                                'window': metadata['window']}
    evidence = Evidence(id=uid(), workspace_id=user.workspace_id, encounter_id=encounter.id, wound_id=proposal.wound_id,
        kind='transcript', text=payload.accepted_text, created_by=user.id, request_key=acceptance_key(proposal.id), request_hash=request_hash,
        metadata_json={'verification_id': proposal.id, 'source_evidence_id': metadata['source_evidence_id'],
            'window': metadata['window'], 'source_media_hash': metadata['source_media_hash'],
            'clinician_verified_excerpt': True, 'candidate_edited': payload.accepted_text != proposal.text,
            'original_transcript_replaced': False, 'raw_media_retained': False},
        warnings=warnings + ['This reviewed excerpt is additional evidence. Review its clinical findings separately; the original transcript is preserved.'])
    db.add(evidence)
    db.flush()
    clinical.add_facts(db, evidence, candidates, user, confirmed=False)
    clinical.timeline(db, user, encounter.id, 'audio.verification.accept', evidence.id,
                       {'verification_id': proposal.id, 'source_evidence_id': metadata['source_evidence_id'],
                        'candidate_edited': payload.accepted_text != proposal.text})
    clinical.finish(db)
    return {'verification_id': proposal.id, 'status': 'accepted', **clinical.source_response(db, encounter, evidence)}
