from __future__ import annotations

import io
from hashlib import sha256
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace
import wave

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import pytest
from sqlalchemy import create_engine, event, func, select
from sqlalchemy.orm import sessionmaker

from server import audio_verification as av
from server import clinical, providers
from server.clinical_models import ClinicalFact, Encounter, Evidence, Patient, Wound
from server.db import Base, get_db
from server.identity import User, Workspace, current_user, require_csrf


def wav_bytes(seconds):
    output = io.BytesIO()
    with wave.open(output, 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16000)
        audio.writeframes(b'\x00\x00' * int(16000 * seconds))
    return output.getvalue()


@pytest.fixture
def api(tmp_path, monkeypatch):
    engine = create_engine(f'sqlite:///{tmp_path / "verification.sqlite"}', connect_args={'check_same_thread': False})
    @event.listens_for(engine, 'connect')
    def configure(connection, _):
        connection.execute('PRAGMA foreign_keys=ON')
    Base.metadata.create_all(engine)
    sessions = sessionmaker(engine, expire_on_commit=False, autoflush=False)
    original = wav_bytes(40)
    with sessions() as db:
        for suffix in ('1', '2'):
            db.add(Workspace(id='ws'+suffix, name='Synthetic workspace '+suffix))
        db.flush()
        for suffix in ('1', '2'):
            db.add(User(id='u'+suffix, workspace_id='ws'+suffix, email=f'verifier{suffix}@example.test', display_name='Synthetic clinician', password_hash='unused'))
        db.flush()
        db.add(Patient(id='patient', workspace_id='ws1', display_name='Synthetic patient'))
        db.flush()
        db.add(Wound(id='wound', workspace_id='ws1', patient_id='patient', label='Synthetic heel'))
        db.flush()
        db.add(Encounter(id='case', workspace_id='ws1', patient_id='patient', wound_id='wound', title='Synthetic audio case', created_by='u1'))
        db.flush()
        db.add(Evidence(id='original', workspace_id='ws1', encounter_id='case', wound_id='wound', kind='audio',
            text='Patient has fever. Wound is 2 x 1 cm.', created_by='u1', request_hash='0'*64,
            metadata_json={'media_hash': sha256(original).hexdigest(), 'segments': [
                {'text': 'Patient has fever.', 'start': 10, 'end': 12, 'speaker': 0},
                {'text': 'Wound is 2 x 1 cm.', 'start': 15, 'end': 18, 'speaker': 1}]}))
        db.commit()
    def database():
        with sessions() as db:
            try: yield db
            finally: db.rollback()
    def actor(request: Request, db=Depends(get_db)):
        return db.get(User, request.headers.get('X-Test-User', 'u1'))
    def csrf_actor(request: Request, user=Depends(actor)):
        if request.headers.get('X-CSRF-Token') != 'synthetic-csrf':
            raise HTTPException(403, 'Synthetic missing CSRF')
        return user
    app = FastAPI()
    app.include_router(av.router, prefix='/api/v1')
    app.include_router(clinical.router, prefix='/api/v1')
    app.dependency_overrides[get_db] = database
    app.dependency_overrides[current_user] = actor
    app.dependency_overrides[require_csrf] = csrf_actor
    settings = SimpleNamespace(max_audio_mb=25, provider_execution_enabled=True)
    monkeypatch.setattr(av, 'settings', settings)
    monkeypatch.setattr(av, 'check_rate', lambda *a, **k: None)
    seen = []
    def clip(data, start, end):
        assert data == original and (start, end) == (10, 12)
        return wav_bytes(2), 2.0
    async def verifier(data, **kwargs):
        seen.append((data, kwargs))
        return {'text': 'Patient denies fever.', 'segments': [{'text': 'Patient denies fever.', 'start': 0, 'end': 2, 'speaker': 0}],
                'usage': {'normalized_micros': 309, 'charged_credits': .309},
                'verifier': {'provider': 'groq', 'model': 'whisper-large-v3', 'method': 'full_whisper_v3_verification'}}
    monkeypatch.setattr(av, 'clip_audio', clip)
    monkeypatch.setattr(providers, 'verify_speech_clip', verifier)
    client = TestClient(app)
    yield SimpleNamespace(client=client, sessions=sessions, original=original, calls=seen, config=settings)
    client.close()
    engine.dispose()


def propose(api, *, changes=None, data=None, headers=None):
    fields = {'source_evidence_id': 'original', 'start_seconds': '10', 'end_seconds': '12',
              'uncertain_term': 'fever', 'context': 'Patient has fever.', 'consent': 'true'}
    fields.update(changes or {})
    defaults = {'Idempotency-Key': 'synthetic-verification', 'X-CSRF-Token': 'synthetic-csrf'}
    defaults.update(headers or {})
    return api.client.post('/api/v1/encounters/case/audio-verifications', data=fields,
        files={'file': ('../../untrusted.wav', api.original if data is None else data, 'audio/wav')}, headers=defaults)


def test_window_proposal_sends_only_clip_and_preserves_original(api):
    response = propose(api)
    assert response.status_code == 201, response.text
    result = response.json()
    assert result['status'] == 'pending_clinician_review' and result['case_revision'] == 2
    assert result['candidate_text'] == 'Patient denies fever.' and result['original_text'] == 'Patient has fever.'
    assert result['comparison']['changed'] is True
    assert any('Negation' in notice for notice in result['comparison']['notices'])
    assert result['segments'][0]['start'] == 10 and result['segments'][0]['end'] == 12
    assert len(api.calls) == 1 and api.calls[0][0] == wav_bytes(2)
    assert not {'context', 'uncertain_term', 'original_text'} & api.calls[0][1].keys()
    with api.sessions() as db:
        original = db.get(Evidence, 'original')
        assert original.text == 'Patient has fever. Wound is 2 x 1 cm.'
        assert db.scalar(select(func.count()).select_from(ClinicalFact)) == 0
        proposal = db.get(Evidence, result['verification_id'])
        assert proposal.metadata_json['source_media_hash'] == sha256(api.original).hexdigest()
        assert proposal.metadata_json['raw_media_retained'] is False


def test_proposal_replay_is_idempotent_and_different_payload_conflicts(api):
    first = propose(api).json()
    repeated = propose(api)
    assert repeated.status_code == 201 and repeated.json()['verification_id'] == first['verification_id']
    assert len(api.calls) == 1
    changed = propose(api, changes={'uncertain_term': 'negation'})
    assert changed.status_code == 409 and len(api.calls) == 1


def test_explicit_acceptance_creates_additional_unconfirmed_evidence(api):
    proposal = propose(api).json()
    route = f'/api/v1/audio-verifications/{proposal["verification_id"]}/accept'
    payload = {'expected_case_revision': proposal['case_revision'], 'accepted_text': 'Patient denies fever.\n'}
    result = api.client.post(route, json=payload, headers={'X-CSRF-Token': 'synthetic-csrf'})
    assert result.status_code == 200, result.text
    accepted = result.json()
    assert accepted['case_revision'] == 3 and accepted['status'] == 'accepted'
    assert accepted['evidence']['text'] == payload['accepted_text']
    assert accepted['evidence']['metadata']['candidate_edited'] is True
    assert accepted['facts'] and all(f['confirmation_state'] == 'unconfirmed' for f in accepted['facts'])
    assert accepted['facts'][0]['source_locator']['verification_id'] == proposal['verification_id']
    assert api.client.post(route, json=payload, headers={'X-CSRF-Token': 'synthetic-csrf'}).json()['evidence']['id'] == accepted['evidence']['id']
    assert api.client.post(route, json={**payload, 'accepted_text': 'Patient has fever.'}, headers={'X-CSRF-Token': 'synthetic-csrf'}).status_code == 409
    refreshed = api.client.get('/api/v1/audio-verifications/' + proposal['verification_id']).json()
    assert refreshed['status'] == 'accepted' and refreshed['accepted_evidence_id'] == accepted['evidence']['id']
    with api.sessions() as db:
        assert db.get(Evidence, 'original').text == 'Patient has fever. Wound is 2 x 1 cm.'
    assert len(api.calls) == 1


@pytest.mark.parametrize('change', [
    {'start_seconds': '-1'}, {'start_seconds': '12', 'end_seconds': '12'},
    {'end_seconds': '41'}, {'end_seconds': 'nan'}, {'start_seconds': 'inf'},
    {'uncertain_term': ''}, {'uncertain_term': 'x'*201}, {'context': 'x'*2001}, {'verifier': 'arbitrary-provider'},
])
def test_invalid_window_or_fields_are_rejected_before_provider(api, change):
    response = propose(api, changes=change)
    assert response.status_code == 422, response.text
    assert not api.calls


def test_consent_csrf_idempotency_and_source_hash_are_required(api):
    assert propose(api, changes={'consent': 'false'}).status_code == 403
    assert propose(api, headers={'X-CSRF-Token': ''}).status_code == 403
    assert propose(api, headers={'Idempotency-Key': ''}).status_code == 422
    assert propose(api, data=wav_bytes(3)).status_code == 422
    assert not api.calls


def test_cross_tenant_and_cross_encounter_source_are_rejected(api):
    assert propose(api, headers={'X-Test-User': 'u2'}).status_code == 404
    with api.sessions() as db:
        db.add(Encounter(id='other', workspace_id='ws1', patient_id='patient', wound_id='wound', title='Other', created_by='u1'))
        db.flush()
        db.add(Evidence(id='other-source', workspace_id='ws1', encounter_id='other', kind='audio', text='Synthetic',
            created_by='u1', request_hash='0'*64, metadata_json={'media_hash': sha256(api.original).hexdigest()}))
        db.commit()
    assert propose(api, changes={'source_evidence_id': 'other-source'}).status_code == 422
    assert not api.calls


def test_execution_pause_and_rate_gate_block_processing(api, monkeypatch):
    api.config.provider_execution_enabled = False
    assert propose(api).status_code == 503 and not api.calls
    api.config.provider_execution_enabled = True
    def exhausted(*args, **kwargs): raise HTTPException(429, 'Synthetic rate gate')
    monkeypatch.setattr(av, 'check_rate', exhausted)
    assert propose(api).status_code == 429 and not api.calls


def test_upload_limit_is_checked_before_provider(api):
    response = propose(api, data=b'RIFF' + b'\x00' * (25 * 1024 * 1024))
    assert response.status_code == 413 and not api.calls


def test_stale_provider_result_cannot_attach_to_changed_case(api, monkeypatch):
    async def changed(*args, **kwargs):
        with api.sessions() as db:
            case = db.get(Encounter, 'case')
            case.revision += 1
            db.commit()
        return {'text': 'Patient denies fever.', 'segments': [], 'usage': {}, 'verifier': {'method': 'mocked'}}
    monkeypatch.setattr(providers, 'verify_speech_clip', changed)
    assert propose(api).status_code == 409
    with api.sessions() as db:
        assert db.scalar(select(func.count()).select_from(Evidence)) == 1


def test_stale_acceptance_cannot_create_facts(api):
    proposal = propose(api).json()
    result = api.client.post(f'/api/v1/audio-verifications/{proposal["verification_id"]}/accept',
        json={'expected_case_revision': 1, 'accepted_text': 'Patient denies fever.'}, headers={'X-CSRF-Token': 'synthetic-csrf'})
    assert result.status_code == 409
    with api.sessions() as db:
        assert db.scalar(select(func.count()).select_from(ClinicalFact)) == 0


def test_proposal_and_acceptance_do_not_leak_across_tenants(api):
    proposal = propose(api).json()
    path = '/api/v1/audio-verifications/' + proposal['verification_id']
    assert api.client.get(path, headers={'X-Test-User': 'u2'}).status_code == 404
    assert api.client.post(path+'/accept', json={'expected_case_revision': 2, 'accepted_text': 'Synthetic'},
        headers={'X-Test-User': 'u2', 'X-CSRF-Token': 'synthetic-csrf'}).status_code == 404


@pytest.mark.parametrize('status', [402, 503, 504])
def test_provider_failures_preserve_original_and_create_no_proposal(api, monkeypatch, status):
    async def unavailable(*args, **kwargs): raise HTTPException(status, 'Synthetic provider gate')
    monkeypatch.setattr(providers, 'verify_speech_clip', unavailable)
    assert propose(api).status_code == status
    with api.sessions() as db:
        assert db.scalar(select(func.count()).select_from(Evidence)) == 1


def test_clip_decoder_uses_fixed_paths_bounded_flags_and_actual_duration(monkeypatch):
    monkeypatch.setattr(providers, 'audio_duration', lambda _: 60)
    monkeypatch.setattr(av.shutil, 'which', lambda _: 'synthetic-ffmpeg')
    calls = []
    def decode(command, **kwargs):
        calls.append((command, kwargs))
        assert command[command.index('-protocol_whitelist') + 1] == 'file,pipe'
        assert command[command.index('-ss') + 1] == '10.000000'
        assert command[command.index('-t') + 1] == '2.000000'
        assert '-nostdin' in command and kwargs['timeout'] == 20 and 'shell' not in kwargs
        assert Path(command[command.index('-i') + 1]).name == 'original.media'
        Path(command[-1]).write_bytes(wav_bytes(2))
    monkeypatch.setattr(av.subprocess, 'run', decode)
    clip, seconds = av.clip_audio(b'synthetic-original', 10, 12)
    assert clip == wav_bytes(2) and seconds == 2 and len(calls) == 1


def test_clip_decoder_checks_source_duration_and_output_bound(monkeypatch):
    monkeypatch.setattr(providers, 'audio_duration', lambda _: 5)
    with pytest.raises(HTTPException) as caught: av.clip_audio(b'synthetic', 10, 12)
    assert caught.value.status_code == 422
    monkeypatch.setattr(providers, 'audio_duration', lambda _: 60)
    monkeypatch.setattr(av.shutil, 'which', lambda _: 'synthetic-ffmpeg')
    def too_long(command, **kwargs): Path(command[-1]).write_bytes(wav_bytes(3))
    monkeypatch.setattr(av.subprocess, 'run', too_long)
    with pytest.raises(HTTPException) as caught: av.clip_audio(b'synthetic', 10, 12)
    assert caught.value.status_code == 422


def test_clip_decoder_timeout_is_bounded(monkeypatch):
    monkeypatch.setattr(providers, 'audio_duration', lambda _: 60)
    monkeypatch.setattr(av.shutil, 'which', lambda _: 'synthetic-ffmpeg')
    def timeout(*args, **kwargs): raise subprocess.TimeoutExpired('synthetic', 20)
    monkeypatch.setattr(av.subprocess, 'run', timeout)
    with pytest.raises(HTTPException) as caught: av.clip_audio(b'synthetic', 10, 12)
    assert caught.value.status_code == 422


def test_missing_segment_alignment_is_reported_as_context_not_exact_window():
    source = SimpleNamespace(text='Synthetic full transcript', metadata_json={})
    original, scope = av.original_excerpt(source, 1, 2)
    result = av.comparison(original, 'Synthetic clip', 'clip', scope)
    assert scope == 'full_transcript_context_without_aligned_segments'
    assert any('timing is unavailable' in notice for notice in result['notices'])


@pytest.mark.skipif(not shutil.which('ffmpeg') or not shutil.which('ffprobe'), reason='Local decoder binaries are unavailable')
def test_real_local_decoder_clips_synthetic_wave_without_provider_calls():
    clip, duration = av.clip_audio(wav_bytes(3), .25, 1.25)
    assert duration == 1
    with wave.open(io.BytesIO(clip), 'rb') as result:
        assert result.getnframes() == 16000 and result.getnchannels() == 1
