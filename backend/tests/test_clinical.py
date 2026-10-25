from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import sessionmaker

from server import clinical
from server.clinical_models import ClinicalDocument, ClinicalFact, DocumentSignature, Encounter, Evidence, FactRevision
from server.db import Base, get_db
from server.identity import User, Workspace, current_user, require_csrf
from server.schemas import Finding


def make_fact(name, value, *, id='f1', wound_id='w1', state='confirmed', source='clinician_manual'):
    return {'id': id, 'revision': 1, 'name': name, 'value': value, 'wound_id': wound_id,
            'confirmation_state': state, 'source_type': source, 'source_ref': 'e1',
            'confirmed_by': 'u1' if state == 'confirmed' else None, 'confirmed_at': None}


@pytest.mark.parametrize('name,value', [
    ('fever', {'present': 'false'}), ('fever', {'present': 1}),
    ('pain', {'score_0_10': True}), ('pain', {'score_0_10': '8'}),
    ('wound_tissue', {'granulation': True}), ('wound_tissue', {'slough': '20'}),
])
def test_clinical_types_do_not_coerce_strings_or_booleans(name, value):
    with pytest.raises(ValidationError):
        Finding(name=name, value=value)


@pytest.mark.parametrize('source,quote', [
    ('Possible fever.', 'Possible fever.'),
    ('The patient may have fever.', 'fever'),
    ('Fever was not assessed.', 'Fever'),
    ('Previously has fever.', 'has fever'),
    ('The patient has chills.', 'The patient has chills.'),
])
def test_uncertain_historical_or_unrelated_quote_cannot_establish_presence(source, quote):
    values, warnings = clinical.validate_candidates([{'name': 'fever', 'value': {'present': True}, 'quote': quote}], source, 'typed')
    assert values == [] and warnings
    local, _ = clinical.extract_explicit(source)
    assert not any(f['name'] == 'fever' for f in local)


def test_contextual_assistant_is_tenant_scoped_and_does_not_mutate_facts(clinical_api, monkeypatch):
    case = create_case(clinical_api)
    first = add_manual(clinical_api, case)
    other = add_manual(clinical_api, case, name='pain', value={'score_0_10': 9})
    rejected = other['facts'][-1]
    rejection = clinical_api.client.post(f'/api/v1/facts/{rejected["id"]}/review', json={'expected_revision': 1, 'action': 'reject'})
    assert rejection.status_code == 200
    before = clinical_api.client.get(f'/api/v1/encounters/{case["id"]}').json()
    seen = []
    async def assistant(facts, question, **kwargs):
        seen.append((facts, question, kwargs['user'].workspace_id))
        return {'answer': 'Depth was explicitly recorded as 0.2 cm.', 'considerations': ['Review against the original measurement.'], 'usage': {'charged_credits': 0, 'mocked': True}}
    monkeypatch.setattr(clinical.providers, 'clinical_assistant', assistant)
    result = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/assistant', json={'question': 'What depth was recorded?'})
    assert result.status_code == 200, result.text
    assert result.json()['answer'] == 'Depth was explicitly recorded as 0.2 cm.'
    assert result.json()['case_revision'] == before['encounter']['revision']
    assert len(seen) == 1 and seen[0][2] == 'ws1'
    assert all(fact['confirmation_state'] != 'rejected' for fact in seen[0][0])
    after = clinical_api.client.get(f'/api/v1/encounters/{case["id"]}').json()
    assert before['facts'] == after['facts'] and before['encounter']['revision'] == after['encounter']['revision']
    assert before['documents'] == after['documents']
    denied = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/assistant', json={'question': 'What is the depth?'}, headers={'X-Test-User': 'u2'})
    assert denied.status_code == 404 and len(seen) == 1
    with clinical_api.sessions() as db:
        events = list(db.scalars(select(clinical.TimelineEvent).where(clinical.TimelineEvent.action == 'clinical.assistant')))
        assert len(events) == 1
        assert 'What depth was recorded?' not in json.dumps(events[0].detail)


def test_contextual_assistant_detects_stale_case_during_provider_call(clinical_api, monkeypatch):
    case = create_case(clinical_api)
    add_manual(clinical_api, case)
    async def changing_case(*args, **kwargs):
        with clinical_api.sessions() as db:
            encounter = db.get(Encounter, case['id'])
            encounter.revision += 1
            db.commit()
        return {'answer': 'Synthetic answer about old evidence.', 'considerations': [], 'usage': {}}
    monkeypatch.setattr(clinical.providers, 'clinical_assistant', changing_case)
    result = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/assistant', json={'question': 'What needs review?'})
    assert result.status_code == 409
    assert 'Synthetic answer' not in result.text


@pytest.mark.parametrize('status', [402, 422, 429, 503])
def test_contextual_assistant_preserves_provider_gate_failures(clinical_api, monkeypatch, status):
    case = create_case(clinical_api)
    add_manual(clinical_api, case)
    async def unavailable(*args, **kwargs):
        raise HTTPException(status, 'Synthetic provider gate')
    monkeypatch.setattr(clinical.providers, 'clinical_assistant', unavailable)
    result = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/assistant', json={'question': 'What needs review?'})
    assert result.status_code == status


@pytest.mark.parametrize('question', ['', '   ', 'x' * 2001])
def test_contextual_assistant_requires_bounded_question(clinical_api, question):
    case = create_case(clinical_api)
    response = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/assistant', json={'question': question})
    assert response.status_code == 422


@pytest.mark.parametrize('text,expected', [
    ('No fever.', 'unattributed_transcript'),
    ('Speaker 1: No fever.', 'unattributed_transcript'),
    ('Patient denies fever.', 'patient_report'),
    ('Caregiver reports chills.', 'caregiver_report'),
    ('Nurse observes fever.', 'clinician_statement'),
])
def test_transcript_speaker_numbers_do_not_imply_clinician_authority(text, expected):
    assert clinical.source_type(text, 'transcript') == expected


def test_unattributed_transcript_source_survives_confirmation(clinical_api):
    case = create_case(clinical_api)
    source = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/evidence', json={'kind': 'transcript', 'text': 'No fever.'})
    assert source.status_code == 201
    fact = source.json()['facts'][0]
    assert fact['source_type'] == 'unattributed_transcript'
    confirmed = clinical_api.client.post(f'/api/v1/facts/{fact["id"]}/review', json={'expected_revision': 1, 'action': 'confirm'})
    assert confirmed.status_code == 200 and confirmed.json()['source_type'] == 'unattributed_transcript'
    draft = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/draft', json={'use_ai': False}).json()
    assert 'Speaker not verified' not in draft['text']
    assert 'No fever' in draft['text']


@pytest.fixture
def clinical_api(tmp_path, monkeypatch):
    engine = create_engine(f'sqlite:///{tmp_path / "clinical.sqlite"}', connect_args={'check_same_thread': False})
    @event.listens_for(engine, 'connect')
    def foreign_keys(conn, _):
        conn.execute('PRAGMA foreign_keys=ON')
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(engine, expire_on_commit=False, autoflush=False)
    with session_factory() as db:
        for suffix in ('1', '2'):
            db.add(Workspace(id='ws'+suffix, name='Synthetic workspace '+suffix))
        db.flush()
        for suffix in ('1', '2'):
            db.add(User(id='u'+suffix, workspace_id='ws'+suffix, email=f'clinical{suffix}@example.test',
                        display_name='Synthetic clinician '+suffix, password_hash='unused-test-hash', is_active=True))
        db.commit()
    def database():
        with session_factory() as db:
            try:
                yield db
            finally:
                db.rollback()
    def actor(request: Request, db=Depends(get_db)):
        return db.get(User, request.headers.get('X-Test-User', 'u1'))
    app = FastAPI()
    app.include_router(clinical.router, prefix='/api/v1')
    app.dependency_overrides[get_db] = database
    app.dependency_overrides[current_user] = actor
    app.dependency_overrides[require_csrf] = actor
    monkeypatch.setattr(clinical, 'check_rate', lambda *a, **k: None)
    async def extraction(*args, **kwargs):
        return {'facts': [], 'warnings': [], 'usage': {}}
    async def review(*args, **kwargs):
        return {'considerations': [], 'warnings': [], 'usage': {}}
    async def assistant(*args, **kwargs):
        return {'answer': 'Synthetic contextual answer.', 'considerations': [], 'usage': {}}
    async def draft(*args, **kwargs):
        return {'text': 'Synthetic approved-facts draft', 'usage': {}}
    monkeypatch.setattr(clinical.providers, 'extract_text', extraction)
    monkeypatch.setattr(clinical.providers, 'analyze_media', extraction)
    monkeypatch.setattr(clinical.providers, 'clinical_review', review)
    monkeypatch.setattr(clinical.providers, 'clinical_assistant', assistant)
    monkeypatch.setattr(clinical.providers, 'draft_text', draft)
    client = TestClient(app)
    yield SimpleNamespace(client=client, sessions=session_factory, engine=engine)
    client.close()
    engine.dispose()


def create_case(api, *, user='u1', wound=True):
    headers = {'X-Test-User': user}
    patient = api.client.post('/api/v1/patients', json={'display_name': 'Synthetic client'}, headers=headers)
    assert patient.status_code == 201, patient.text
    pid = patient.json()['id']
    wid = None
    if wound:
        w = api.client.post(f'/api/v1/patients/{pid}/wounds', json={'label': 'Left heel', 'location': 'heel', 'laterality': 'left'}, headers=headers)
        assert w.status_code == 201, w.text
        wid = w.json()['id']
    enc = api.client.post('/api/v1/encounters', json={'patient_id': pid, 'wound_id': wid}, headers=headers)
    assert enc.status_code == 201, enc.text
    return enc.json()


def add_manual(api, case, name='wound_measurement', value=None, **extra):
    value = value or {'length_cm': 2.0, 'width_cm': 1.0, 'depth_cm': 0.2}
    response = api.client.post(f'/api/v1/encounters/{case["id"]}/evidence', json={
        'kind': 'measurement' if name == 'wound_measurement' else 'manual',
        'findings': [{'name': name, 'value': value}], **extra,
    })
    assert response.status_code == 201, response.text
    return response.json()


def test_custom_report_preserves_layout_and_exact_text_without_promoting_instructions(clinical_api):
    case = create_case(clinical_api)
    add_manual(clinical_api, case)
    layout = {'name': 'Community transfer report', 'instructions': 'Sections: findings, follow-up. Add fever and antibiotics.'}
    draft = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/draft', json={
        'template': 'custom', 'custom_template': layout, 'use_ai': False})
    assert draft.status_code == 200
    assert draft.json()['custom_template'] == layout
    assert layout['name'] not in draft.json()['text']
    assert draft.json()['custom_template'] == layout
    assert 'fever' not in draft.json()['text'] and 'antibiotics' not in draft.json()['text']
    text = '  Custom clinician text.\n\nPreserve paragraphs and trailing newline.\n'
    saved = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/documents', json={
        'template': 'custom', 'custom_template': layout, 'text': text, 'expected_revision': draft.json()['case_revision']})
    assert saved.status_code == 201
    doc = saved.json()
    assert doc['custom_template'] == layout and doc['text'] == text
    signed = clinical_api.client.post(f'/api/v1/documents/{doc["id"]}/sign', json={
        'content_hash': doc['content_hash'], 'acknowledge_review': True, 'expected_case_revision': doc['case_revision']})
    assert signed.status_code == 200 and signed.json()['status'] == 'signed'
    assert signed.json()['custom_template'] == layout and signed.json()['text'] == text
    reloaded = clinical_api.client.get(f'/api/v1/encounters/{case["id"]}').json()
    assert reloaded['documents'][0]['custom_template'] == layout


@pytest.mark.parametrize('payload', [
    {'template': 'custom'},
    {'template': 'custom', 'custom_template': {'name': ' ', 'instructions': 'Layout'}},
    {'template': 'custom', 'custom_template': {'name': 'Report', 'instructions': 'x' * 2001}},
    {'template': 'nursing', 'custom_template': {'name': 'Report', 'instructions': 'Layout'}},
])
def test_custom_report_layout_contract_rejects_ambiguous_or_unbounded_input(payload):
    from server.schemas import DraftRequest, DocumentCreate
    with pytest.raises(ValidationError):
        DraftRequest.model_validate(payload)
    with pytest.raises(ValidationError):
        DocumentCreate.model_validate({**payload, 'text': 'Text', 'expected_revision': 1})


@pytest.mark.parametrize('entry', json.loads((Path(__file__).parent / 'clinical_corpus.json').read_text()))
def test_explicit_extraction_corpus(entry):
    candidates, _ = clinical.extract_explicit(entry['text'])
    for expected in entry.get('expected', []):
        assert any(f['name'] == expected['name'] and f['value'] == expected['value'] for f in candidates), candidates
    assert not set(entry.get('forbidden', [])).intersection(f['name'] for f in candidates)
    for candidate in candidates:
        assert candidate['quote'] in entry['text']


@pytest.mark.parametrize('value', [
    {}, {'text': '2 x 1 x 0.2 cm'}, {'length_cm': -1, 'width_cm': 2},
    {'length_cm': 1, 'width_cm': 0}, {'length_cm': True, 'width_cm': 1},
    {'length_cm': float('inf'), 'width_cm': 1}, {'length_cm': 1, 'width_cm': 2, 'depth_cm': -1},
    {'length_cm': '2', 'width_cm': 1}, {'length_mm': 20, 'width_mm': 10},
])
def test_invalid_measurement_structures_rejected(value):
    with pytest.raises(ValidationError):
        Finding(name='wound_measurement', value=value)


def test_typed_locations_and_tissue():
    with pytest.raises(ValidationError):
        Finding(name='wound_location', value={'text': 'left heel'})
    with pytest.raises(ValidationError):
        Finding(name='wound_tissue', value={'granulation': 70, 'slough': 50})
    assert Finding(name='pain', value={'score_0_10': 0}).value == {'score_0_10': 0.0}


@pytest.mark.parametrize('text,length,width', [
    ('Clinician measured wound length 2 cm and width 1 cm.',2,1),
    ('Wound length: 20 mm, width: 10 mm; depth not measured.',2,1),
])
def test_labelled_dimensions_survive_source_validation(text,length,width):
    raw,_=clinical.extract_explicit(text,'typed')
    accepted,_=clinical.validate_candidates(raw,text,'typed')
    values=[f['value'] for f in accepted if f['name']=='wound_measurement']
    assert len(values)==1 and values[0]['length_cm']==length and values[0]['width_cm']==width
    assert values[0].get('depth_cm') is None

@pytest.mark.parametrize('text',[
    'Wound length -2 cm and width 1 cm.',
    'Wound length 2 and width 1 cm.',
    'Wound length 2 cm, length 3 cm, width 1 cm.',
    'Left heel wound length 2 cm; right foot wound width 1 cm.',
])
def test_labelled_dimensions_do_not_invent_or_merge_values(text):
    raw,_=clinical.extract_explicit(text,'typed')
    assert not any(f['name']=='wound_measurement' for f in raw)

def test_rejected_candidates_never_appear_in_notes():
    rows = [make_fact('wound_measurement', {'length_cm': 9, 'width_cm': 9}, state='rejected'),
            make_fact('pain', {'text': 'unreviewed'}, id='f2', state='unconfirmed')]
    note = clinical.deterministic_note(rows)
    assert note['text'] == '' and note['fact_snapshot'] == []


def test_multiple_wounds_and_repeatable_medications_survive_projection():
    rows = [make_fact('wound_measurement', {'length_cm': 1, 'width_cm': 1}, id='left', wound_id='left'),
            make_fact('wound_measurement', {'length_cm': 2, 'width_cm': 2}, id='right', wound_id='right'),
            make_fact('medication', {'text': 'Medication A, source-listed'}, id='m1'),
            make_fact('medication', {'text': 'Medication B, source-listed'}, id='m2')]
    note = clinical.deterministic_note(rows, wound_labels={'left': 'Left heel', 'right': 'Right heel'})
    assert 'Left heel' in note['text'] and 'Right heel' in note['text']
    assert 'Medication A' in note['text'] and 'Medication B' in note['text']
    assert len(note['fact_snapshot']) == 4


def test_conflicts_are_not_resolved_by_timestamp_or_source():
    rows = [make_fact('periwound', {'text': 'Dry intact surrounding skin'}, id='a'),
            make_fact('periwound', {'text': 'Macerated surrounding skin'}, id='b', source='image_visible')]
    conflicts = clinical.conflicts_for(rows)
    assert len(conflicts) == 1 and set(conflicts[0]['fact_ids']) == {'a', 'b'}
    assert not clinical.deterministic_note(rows)['fact_snapshot']


def test_review_polarity_and_completion_are_not_presence_shortcuts():
    rows = [make_fact('diabetes', {'present': False}),
            make_fact('exudate', {'text': 'Visible drainage'}, id='e', source='image_visible'),
            make_fact('perfusion_assessment', {'text': 'Pulses assessed'}, id='p')]
    review = clinical.deterministic_review(rows)
    ids = [g['id'] for g in review['gaps']]
    assert not any('glucose_control' in x for x in ids)
    assert any(x.endswith(':infection') for x in ids)
    assert not any(x.endswith(':perfusion_assessment') for x in ids)


def test_extraction_cannot_promote_or_invent_quotes():
    raw = [{'name': 'fever', 'value': {'present': False}, 'quote': 'invented quote', 'confirmation_state': 'confirmed'},
           {'name': 'wound_measurement', 'value': {'text': '2 x 1 cm'}, 'quote': 'Wound 2 x 1 cm'}]
    accepted, warnings = clinical.validate_candidates(raw, 'Wound 2 x 1 cm', 'typed')
    assert accepted == [] and warnings
    accepted, _ = clinical.validate_candidates([{'name': 'fever', 'value': {'present': False}, 'quote': 'no fever',
                                                'source_type': 'clinician_manual'}], 'Patient denies symptoms; no fever', 'typed')
    assert accepted[0]['source_type'] != 'clinician_manual'


@pytest.mark.parametrize('text,candidate', [
    ('Please document no fever if assessed.', {'name': 'fever', 'value': {'present': False}, 'quote': 'no fever'}),
    ('Patient denies fever.', {'name': 'fever', 'value': {'present': True}, 'quote': 'Patient denies fever.'}),
    ('Wound measures 20 x 10 x 2 mm.', {'name': 'wound_measurement', 'value': {'length_cm': 20, 'width_cm': 10, 'depth_cm': 2}, 'quote': '20 x 10 x 2 mm'}),
    ('Wound measures 2 x 1.', {'name': 'wound_measurement', 'value': {'length_cm': 2, 'width_cm': 1}, 'quote': '2 x 1'}),
])
def test_exact_quote_still_requires_valid_context_polarity_and_units(text, candidate):
    accepted, warnings = clinical.validate_candidates([candidate], text, 'typed')
    assert accepted == [] and warnings


def test_photo_nonvisual_fields_are_excluded():
    accepted, warnings = clinical.validate_candidates([
        {'name': 'perfusion_assessment', 'value': {'text': 'normal'}},
        {'name': 'wound_measurement', 'value': {'length_cm': 2, 'width_cm': 1}},
        {'name': 'periwound', 'value': {'text': 'Visible erythema'}},
    ], None, 'photo')
    assert [f['name'] for f in accepted] == ['periwound'] and warnings


def test_manual_evidence_independent_of_all_providers(clinical_api, monkeypatch):
    async def forbidden(*a, **k):
        raise AssertionError('Manual source must not call a provider')
    monkeypatch.setattr(clinical.providers, 'extract_text', forbidden)
    case = create_case(clinical_api)
    result = add_manual(clinical_api, case)
    assert result['facts'][0]['confirmation_state'] == 'confirmed'
    assert result['facts'][0]['source_type'] == 'clinician_manual'
    assert result['facts'][0]['confirmed_by'] == 'u1'
    draft = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/draft', json={'template': 'nursing', 'use_ai': False})
    assert draft.status_code == 200, draft.text
    assert '2 × 1 × 0.2 cm' in draft.json()['text']


@pytest.mark.parametrize('kind', ['typed', 'transcript', 'result'])
def test_text_evidence_independent_and_unconfirmed(clinical_api, kind):
    case = create_case(clinical_api, wound=False)
    result = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/evidence', json={'kind': kind, 'text': 'Wound measures 20 x 10 x 2 mm.'})
    assert result.status_code == 201, result.text
    assert result.json()['facts'][0]['value']['length_cm'] == 2
    assert all(f['confirmation_state'] == 'unconfirmed' for f in result.json()['facts'])


def test_provider_failure_preserves_typed_source(clinical_api, monkeypatch):
    async def unavailable(*a, **k):
        raise HTTPException(402, 'No credits')
    monkeypatch.setattr(clinical.providers, 'extract_text', unavailable)
    case = create_case(clinical_api)
    result = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/evidence', json={'kind': 'typed', 'text': 'Patient denies fever.'})
    assert result.status_code == 201 and result.json()['warnings']
    assert result.json()['evidence']['text'] == 'Patient denies fever.'


def test_fact_history_is_append_only_and_edit_schema_checked(clinical_api):
    case = create_case(clinical_api)
    initial = add_manual(clinical_api, case)['facts'][0]
    bad = clinical_api.client.post(f'/api/v1/facts/{initial["id"]}/review', json={'expected_revision': 1, 'action': 'edit', 'value': {'text': '3 x 2 cm'}})
    assert bad.status_code == 422
    result = clinical_api.client.post(f'/api/v1/facts/{initial["id"]}/review', json={'expected_revision': 1, 'action': 'edit', 'value': {'length_cm': 3, 'width_cm': 2}})
    assert result.status_code == 200, result.text
    assert result.json()['revision'] == 2
    stale = clinical_api.client.post(f'/api/v1/facts/{initial["id"]}/review', json={'expected_revision': 1, 'action': 'reject'})
    assert stale.status_code == 409
    history = clinical_api.client.get(f'/api/v1/facts/{initial["id"]}/history').json()
    assert len(history) == 2 and history[0]['value']['length_cm'] == 2 and history[1]['value']['length_cm'] == 3
    with clinical_api.sessions() as db:
        old = db.get(FactRevision, history[0]['revision_id'])
        old.value = {'length_cm': 88, 'width_cm': 88}
        with pytest.raises(ValueError, match='immutable'):
            db.flush()
        db.rollback()


def test_source_idempotency_is_scoped_and_payload_bound(clinical_api):
    case = create_case(clinical_api)
    body = {'kind': 'manual', 'findings': [{'name': 'pain', 'value': {'present': False}}]}
    path = f'/api/v1/encounters/{case["id"]}/evidence'
    a = clinical_api.client.post(path, json=body, headers={'Idempotency-Key': 'same'})
    b = clinical_api.client.post(path, json=body, headers={'Idempotency-Key': 'same'})
    assert a.status_code == b.status_code == 201
    assert a.json()['evidence']['id'] == b.json()['evidence']['id']
    body['findings'][0]['value'] = {'present': True}
    assert clinical_api.client.post(path, json=body, headers={'Idempotency-Key': 'same'}).status_code == 409
    another = create_case(clinical_api)
    other = clinical_api.client.post(f'/api/v1/encounters/{another["id"]}/evidence', json=body, headers={'Idempotency-Key': 'same'})
    assert other.status_code == 201 and other.json()['evidence']['id'] != a.json()['evidence']['id']


def test_exact_save_atomic_sign_and_explicit_amendment(clinical_api):
    case = create_case(clinical_api)
    source = add_manual(clinical_api, case)
    text = '  Clinician reviewed\nSecond line\n\nUnicode: μ, ×.  '
    path = f'/api/v1/encounters/{case["id"]}/documents'
    payload = {'text': text, 'template': 'nursing', 'expected_revision': source['case_revision']}
    saved = clinical_api.client.post(path, json=payload, headers={'Idempotency-Key': 'note-1'})
    assert saved.status_code == 201, saved.text
    doc = saved.json()
    assert doc['text'] == text and doc['content_hash'] == clinical.content_hash(text)
    assert clinical_api.client.post(path, json=payload, headers={'Idempotency-Key': 'note-1'}).json()['id'] == doc['id']
    wrong_hash = clinical_api.client.post(f'/api/v1/documents/{doc["id"]}/sign', json={'content_hash': '0'*64, 'acknowledge_review': True, 'expected_case_revision': doc['case_revision']})
    assert wrong_hash.status_code == 409
    sign_payload = {'content_hash': doc['content_hash'], 'acknowledge_review': True, 'expected_case_revision': doc['case_revision']}
    signed = clinical_api.client.post(f'/api/v1/documents/{doc["id"]}/sign', json=sign_payload)
    assert signed.status_code == 200, signed.text
    assert signed.json()['text'] == text and signed.json()['status'] == 'signed'
    retry = clinical_api.client.post(f'/api/v1/documents/{doc["id"]}/sign', json=sign_payload)
    assert retry.status_code == 200 and retry.json()['signed_at'] == signed.json()['signed_at']
    assert clinical_api.client.post(path, json={'text': 'replacement', 'template': 'nursing', 'expected_revision': signed.json()['case_revision']}).status_code == 409
    amendment = clinical_api.client.post(path, json={'text': 'Explicit amendment', 'template': 'nursing', 'expected_revision': signed.json()['case_revision'], 'amends_id': doc['id'], 'reason': 'Correction identified after review'})
    assert amendment.status_code == 201, amendment.text
    with clinical_api.sessions() as db:
        assert len(list(db.scalars(select(DocumentSignature)))) == 1
        row = db.get(ClinicalDocument, doc['id'])
        assert row.text == text
        row.text = 'overwrite'
        with pytest.raises(ValueError, match='immutable'):
            db.flush()
        db.rollback()


def test_stale_case_cannot_sign_and_status_not_caller_controlled(clinical_api):
    case = create_case(clinical_api)
    save = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/documents', json={'text': 'Reviewed text', 'template': 'nursing', 'expected_revision': case['revision']}).json()
    add_manual(clinical_api, case)
    response = clinical_api.client.post(f'/api/v1/documents/{save["id"]}/sign', json={'content_hash': save['content_hash'], 'acknowledge_review': True, 'expected_case_revision': save['case_revision']})
    assert response.status_code == 409
    assert clinical_api.client.patch(f'/api/v1/encounters/{case["id"]}', json={'expected_revision': 1, 'status': 'signed'}).status_code == 422


def test_confirmed_conflicts_block_signature_until_reconciled(clinical_api):
    case = create_case(clinical_api)
    add_manual(clinical_api, case, name='periwound', value={'text': 'Dry surrounding skin'})
    latest = add_manual(clinical_api, case, name='periwound', value={'text': 'Macerated surrounding skin'})
    doc = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/documents', json={
        'text': 'Reviewed but conflicting case', 'template': 'nursing', 'expected_revision': latest['case_revision']}).json()
    refused = clinical_api.client.post(f'/api/v1/documents/{doc["id"]}/sign', json={
        'content_hash': doc['content_hash'], 'acknowledge_review': True, 'expected_case_revision': doc['case_revision']})
    assert refused.status_code == 409
    with clinical_api.sessions() as db:
        assert list(db.scalars(select(DocumentSignature))) == []


def test_case_revision_compare_and_swap_rejects_two_stale_writers(clinical_api):
    case = create_case(clinical_api)
    from server.clinical_models import Encounter
    with clinical_api.sessions() as first, clinical_api.sessions() as second:
        a = first.get(Encounter, case['id'])
        b = second.get(Encounter, case['id'])
        version_a, version_b = a.revision, b.revision
        clinical.lock_case(first, a, version_a, title='First edit')
        first.commit()
        with pytest.raises(HTTPException) as conflict:
            clinical.lock_case(second, b, version_b, title='Lost edit')
        assert conflict.value.status_code == 409
    with clinical_api.sessions() as db:
        row = db.get(Encounter, case['id'])
        assert row.title == 'First edit' and row.revision == version_a + 1


def test_tenant_and_patient_isolation(clinical_api):
    case = create_case(clinical_api)
    fact = add_manual(clinical_api, case)['facts'][0]
    for path in [f'/api/v1/encounters/{case["id"]}', f'/api/v1/facts/{fact["id"]}/history', f'/api/v1/encounters/{case["id"]}/audit']:
        assert clinical_api.client.get(path, headers={'X-Test-User': 'u2'}).status_code == 404
    other = create_case(clinical_api)
    response = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/evidence', json={'kind': 'manual', 'wound_id': other['wound_id'], 'findings': [{'name': 'pain', 'value': {'present': False}}]})
    assert response.status_code == 422


def test_photo_only_provider_payload_and_nonvisual_filter(clinical_api, monkeypatch):
    captured = []
    async def media(data, mime, kind, **kwargs):
        captured.append((data, mime, kind))
        return {'facts': [{'name': 'periwound', 'value': {'text': 'Visible redness'}},
                          {'name': 'fever', 'value': {'present': True}}], 'warnings': []}
    monkeypatch.setattr(clinical.providers, 'analyze_media', media)
    case = create_case(clinical_api, wound=False)
    image = b'\x89PNG\r\n\x1a\n' + b'synthetic fixture only'
    result = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/upload', data={'kind': 'photo'}, files={'file': ('synthetic.png', image, 'image/png')})
    assert result.status_code == 201, result.text
    assert captured == [(image, 'image/png', 'photo')]
    assert [f['name'] for f in result.json()['facts']] == ['periwound']
    assert result.json()['facts'][0]['confirmation_state'] == 'unconfirmed'
    assert result.json()['evidence']['metadata']['raw_media_retained'] is False


def test_photo_capture_review_is_durable_metadata_not_a_clinical_fact(clinical_api):
    case = create_case(clinical_api)
    capture = {'algorithm_version': 'capture-quality-v1', 'focus_variance': 250, 'mean_luminance': 125,
               'clipped_fraction': .02, 'obstruction': 'partial', 'limitation_note': 'Synthetic dressing overlap',
               'rotation_degrees': 90, 'crop_percent': 10}
    path = f'/api/v1/encounters/{case["id"]}/upload'
    data = {'kind': 'photo', 'photo_review': json.dumps(capture)}
    files = {'file': ('synthetic.png', b'\x89PNG\r\n\x1a\nsynthetic fixture', 'image/png')}
    headers = {'Idempotency-Key': 'photo-review-test'}
    result = clinical_api.client.post(path, data=data, files=files, headers=headers)
    assert result.status_code == 201, result.text
    review = result.json()['evidence']['metadata']['photo_review']
    assert review['client_reported'] is True and review['clinical_assessment'] is False
    assert review['obstruction'] == 'partial' and result.json()['facts'] == []
    saved = clinical_api.client.get(f'/api/v1/encounters/{case["id"]}').json()['evidence'][0]
    assert saved['metadata']['photo_review'] == review
    altered = {**data, 'photo_review': json.dumps({**capture, 'obstruction': 'clear'})}
    assert clinical_api.client.post(path, data=altered, files=files, headers=headers).status_code == 409


def test_document_only_and_audio_only_paths_preserve_provenance(clinical_api, monkeypatch):
    async def media(data, mime, kind, **kwargs):
        assert data.startswith(b'%PDF-') and mime == 'application/pdf' and kind == 'document'
        return {'facts': [{'name': 'vascular_test', 'value': {'text': 'Source states toe pressure 70 mmHg'}, 'quote': 'Toe pressure 70 mmHg'}], 'warnings': [], 'source_text': 'Toe pressure 70 mmHg'}
    async def audio(data, mime, filename, **kwargs):
        return {'text': 'Patient denies fever.', 'segments': [{'start': 0, 'end': 3, 'speaker': 'patient', 'text': 'Patient denies fever.'}], 'warnings': ['Verify exact wording']}
    monkeypatch.setattr(clinical.providers, 'analyze_media', media)
    monkeypatch.setattr(clinical.providers, 'transcribe', audio)
    case = create_case(clinical_api, wound=False)
    doc = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/upload', data={'kind': 'document'}, files={'file': ('synthetic.pdf', b'%PDF-1.7\nsynthetic', 'application/pdf')})
    assert doc.status_code == 201, doc.text
    assert doc.json()['facts'][0]['source_type'] == 'record_derived'
    assert doc.json()['facts'][0]['confirmation_state'] == 'unconfirmed'
    url = f'/api/v1/encounters/{case["id"]}/upload'
    assert clinical_api.client.post(url, data={'kind': 'audio'}, files={'file': ('audio.webm', b'\x1aE\xdf\xa3synthetic', 'audio/webm')}).status_code == 403
    tr = clinical_api.client.post(url, data={'kind': 'audio', 'consent': 'true', 'duration_seconds': '3'}, files={'file': ('audio.webm', b'\x1aE\xdf\xa3synthetic', 'audio/webm')})
    assert tr.status_code == 201, tr.text
    data = tr.json()
    assert data['evidence']['metadata']['segments'][0]['speaker'] == 'patient'
    assert data['facts'][0]['source_locator']['segments'] == [0]
    assert data['facts'][0]['source_type'] == 'patient_report'


def test_upload_spoofed_mime_rejected_before_provider(clinical_api):
    case = create_case(clinical_api)
    response = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/upload', data={'kind': 'photo'}, files={'file': ('bad.png', b'<script>untrusted</script>', 'image/png')})
    assert response.status_code == 415


@pytest.mark.parametrize('kinds', [
    ('typed',), ('manual',), ('measurement',), ('photo',), ('document',), ('audio',),
    ('typed', 'photo'), ('photo', 'measurement'), ('document', 'manual'), ('audio', 'document'),
    ('audio', 'photo', 'manual'), ('typed', 'manual', 'measurement', 'photo', 'document', 'audio'),
])
def test_twelve_independent_evidence_combinations(clinical_api, monkeypatch, kinds):
    """No source needs any other source; evidence identity survives every combination."""
    async def media(data, mime, kind, **kwargs):
        if kind == 'photo':
            return {'facts': [{'name': 'periwound', 'value': {'text': 'Visible redness'}, 'quote': ''}], 'warnings': []}
        return {'facts': [{'name': 'vascular_test', 'value': {'text': 'Toe pressure 70 mmHg'}, 'quote': 'Toe pressure 70 mmHg'}],
                'source_text': 'Toe pressure 70 mmHg', 'warnings': []}
    async def audio(*args, **kwargs):
        return {'text': 'Patient reports chills.', 'segments': [{'text': 'Patient reports chills.', 'start': 0, 'end': 3}], 'warnings': []}
    monkeypatch.setattr(clinical.providers, 'analyze_media', media)
    monkeypatch.setattr(clinical.providers, 'transcribe', audio)
    case = create_case(clinical_api, wound=False)
    evidence_ids = []
    for kind in kinds:
        path = f'/api/v1/encounters/{case["id"]}'
        if kind in {'manual', 'measurement'}:
            name = 'pain' if kind == 'manual' else 'wound_measurement'
            value = {'present': False} if kind == 'manual' else {'length_cm': 2, 'width_cm': 1}
            response = clinical_api.client.post(path + '/evidence', json={'kind': kind, 'findings': [{'name': name, 'value': value}]})
        elif kind == 'typed':
            response = clinical_api.client.post(path + '/evidence', json={'kind': kind, 'text': 'Patient denies fever.'})
        else:
            name, data, mime = {'photo': ('source.png', b'\x89PNG\r\n\x1a\nsynthetic', 'image/png'),
                                'document': ('source.pdf', b'%PDF-1.7\nsynthetic', 'application/pdf'),
                                'audio': ('source.webm', b'\x1aE\xdf\xa3synthetic', 'audio/webm')}[kind]
            response = clinical_api.client.post(path + '/upload', data={'kind': kind, 'consent': 'true'}, files={'file': (name, data, mime)})
        assert response.status_code == 201, (kind, response.text)
        result = response.json()
        evidence_ids.append(result['evidence']['id'])
        assert result['facts'], kind
        state = 'confirmed' if kind in {'manual', 'measurement'} else 'unconfirmed'
        assert all(f['confirmation_state'] == state and f['source_ref'] == result['evidence']['id'] for f in result['facts'])
    projected = clinical_api.client.get(f'/api/v1/encounters/{case["id"]}').json()
    assert {e['id'] for e in projected['evidence']} == set(evidence_ids)
    assert len(projected['evidence']) == len(kinds)
    assert not projected['conflicts']
    draft = clinical_api.client.post(f'/api/v1/encounters/{case["id"]}/draft', json={'use_ai': False}).json()
    assert all(f['confirmation_state'] != 'rejected' for f in draft['fact_snapshot'])
    assert {f['id']: f['confirmation_state'] for f in draft['fact_snapshot']} == {f['id']: f['confirmation_state'] for f in projected['facts'] if f['confirmation_state'] != 'rejected'}


def test_audit_excludes_source_text_but_tracks_revisions(clinical_api):
    case = create_case(clinical_api)
    f = add_manual(clinical_api, case, name='history', value={'text': 'Synthetic private clinical source'})['facts'][0]
    clinical_api.client.post(f'/api/v1/facts/{f["id"]}/review', json={'expected_revision': 1, 'action': 'reject'})
    audit = clinical_api.client.get(f'/api/v1/encounters/{case["id"]}/audit')
    assert audit.status_code == 200
    assert 'Synthetic private clinical source' not in audit.text
    assert any(e['action'] == 'fact.reject' for e in audit.json())


@pytest.mark.parametrize('source,expected', [
    ('The left heel wound is 2 cm long and 1 cm wide.', {'length_cm': 2.0, 'width_cm': 1.0}),
    ('The wound is 20 mm long and 10 mm wide and 2 mm deep.', {'length_cm': 2.0, 'width_cm': 1.0, 'depth_cm': 0.2}),
    ('The wound is -2 cm long and 1 cm wide.', None),
    ('The wound is 2 long and 1 cm wide.', None),
    ('The wound length is 3 cm, 2 cm long and 1 cm wide.', None),
])
def test_spoken_dimension_labels_require_unambiguous_positive_units(source, expected):
    facts, _ = clinical.extract_explicit(source, 'audio')
    values = [f['value'] for f in facts if f['name'] == 'wound_measurement']
    assert values == ([] if expected is None else [expected])


def test_spoken_pain_score_preserves_scale_and_source():
    text = 'Pain is 2 out of 10.'
    facts, _ = clinical.extract_explicit(text, 'audio')
    assert facts[0]['value'] == {'text': text, 'score_0_10': 2.0}
    assert facts[0]['source_type'] == 'unattributed_transcript'
