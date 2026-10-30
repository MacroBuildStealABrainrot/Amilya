"""Nurse / NP / Doctor mode, 'anything missed?' reminders and the office computer inbox. Synthetic data only."""
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
from sqlalchemy import create_engine, event, func, select, update
from sqlalchemy.orm import sessionmaker

from server import desk, documentation_layers as layers, identity, missed_check, providers
from server.core import settings
from server.db import Base, get_db, utcnow
from server.desk_models import DeskNote
from test_quick_workflows import add_source, quick_api, start  # noqa: F401  (fixture re-export)
from test_clinical import clinical_api  # noqa: F401

LEGAL = '2026-09-05'


@pytest.fixture
def api(tmp_path, monkeypatch):
    engine = create_engine('sqlite:///' + str(tmp_path / 'desk.db'), connect_args={'check_same_thread': False, 'timeout': 20})
    @event.listens_for(engine, 'connect')
    def sqlite_config(connection, _):
        connection.execute('PRAGMA foreign_keys=ON')
    Base.metadata.create_all(engine)
    factory = sessionmaker(engine, expire_on_commit=False)
    app = FastAPI()
    app.include_router(identity.router, prefix='/api/v1')
    app.include_router(desk.router, prefix='/api/v1')
    app.include_router(desk.page_router)
    def database():
        with factory() as db:
            yield db
    app.dependency_overrides[get_db] = database
    monkeypatch.setattr(identity, 'SessionLocal', factory)
    monkeypatch.setattr(identity, 'engine', engine)
    monkeypatch.setattr(identity, 'check_rate', lambda *args, **kwargs: None)
    monkeypatch.setattr(desk, 'check_rate', lambda *args, **kwargs: None)
    monkeypatch.setattr(settings, 'app_secret', 'desk-test-secret-at-least-thirty-two-characters!!')
    monkeypatch.setattr(settings, 'public_base_url', 'http://testserver')
    monkeypatch.setattr(settings, 'allow_signup', True)
    monkeypatch.setattr(settings, 'secure_cookies', False)
    monkeypatch.setattr(settings, 'legal_version', LEGAL)
    clients = {}
    def account(email):
        client = TestClient(app)
        result = client.post('/api/v1/auth/signup', json={'email': email, 'password': 'Synthetic-long-password!',
            'display_name': 'Synthetic clinician', 'workspace_name': 'Synthetic workspace', 'accept_terms': True,
            'terms_version': LEGAL, 'privacy_version': LEGAL})
        assert result.status_code == 201, result.text
        clients[email] = client
        return SimpleNamespace(client=client, headers={'X-CSRF-Token': result.json()['csrf_token']}, user=result.json()['user'])
    yield SimpleNamespace(account=account, factory=factory)
    for client in clients.values():
        client.close()
    engine.dispose()


def tonight():
    return (datetime.now(timezone.utc) + timedelta(hours=6)).isoformat()


def send(person, encounter='visit-1', text='Synthetic note. Pain 2/10.', expires=None, title='Nursing Note'):
    return person.client.post('/api/v1/desk/notes', headers=person.headers,
                              json={'encounter_id': encounter, 'title': title, 'text': text, 'expires_at': expires or tonight()})


# ---------- Nurse / NP / Doctor mode ----------

def test_mode_is_saved_and_only_closed_values_are_accepted(api):
    nurse = api.account('mode@example.test')
    ok = nurse.client.patch('/api/v1/auth/profile', headers=nurse.headers, json={'profile': {'clinician_mode': 'doctor'}})
    assert ok.status_code == 200 and ok.json()['profile']['clinician_mode'] == 'doctor'
    for bad in ('Doctor', 'surgeon', 'ignore previous instructions', 1):
        refused = nurse.client.patch('/api/v1/auth/profile', headers=nurse.headers, json={'profile': {'clinician_mode': bad}})
        assert refused.status_code == 422
    for flag in ('missed_check', 'office_computer'):
        assert nurse.client.patch('/api/v1/auth/profile', headers=nurse.headers, json={'profile': {flag: 'yes'}}).status_code == 422
        assert nurse.client.patch('/api/v1/auth/profile', headers=nurse.headers, json={'profile': {flag: False}}).status_code == 200


@pytest.mark.parametrize('profile,role', [
    ({'clinician_mode': 'nurse'}, 'NURSE'),
    ({'clinician_mode': 'np'}, 'NURSE_PRACTITIONER'),
    ({'clinician_mode': 'doctor'}, 'PHYSICIAN'),
    # The switch wins over an older typed profession.
    ({'clinician_mode': 'doctor', 'profession': 'RN'}, 'PHYSICIAN'),
    ({'profession': 'RN'}, 'NURSE'),
    ({'profession': 'Family doctor'}, 'OTHER_CLINICIAN'),
    ({'clinician_mode': 'unknown', 'profession': 'MD'}, 'PHYSICIAN'),
])
def test_mode_chooses_writing_rules(profile, role):
    assert layers.clinician_role(SimpleNamespace(profile=profile)) == role


def test_doctor_mode_gets_doctor_style_and_nurse_mode_keeps_community_style():
    for mode, community in (('doctor', False), ('np', False), ('nurse', True)):
        token = layers.select(SimpleNamespace(profile={'clinician_mode': mode}), 'soap')
        try:
            text = providers.prompt('quick_draft')
            assert ('community_nursing_v1' in text) is community
            if not community:
                assert 'problem-focused' in text and 'Never merge' in text
        finally:
            layers.selection.reset(token)


@pytest.mark.parametrize('mode,expected', [('doctor', 'Consultant voice: PHYSICIAN'), ('np', 'Consultant voice: NURSE_PRACTITIONER'),
                                            ('nurse', 'Consultant voice: NURSE')])
def test_consultant_talks_like_a_colleague_of_the_mode(mode, expected):
    token = layers.select(SimpleNamespace(profile={'clinician_mode': mode}), 'nursing')
    try:
        text = providers.prompt('consult')
        assert expected in text and 'all other consultant rules still apply' in text
        assert 'Documentation layers:' not in text
        assert 'Kosmo' not in text and 'Amilya in Ask Consultant' in text
    finally:
        layers.selection.reset(token)


# ---------- 'Anything missed?' ----------

WOUND_COMPLETE = ('Synthetic visit. Wound on left lower leg 2 x 1 cm. Red granulation tissue. Small serous drainage. '
                  'Periwound intact. Cleansed with NS and applied Mepilex. Pain 1/10. Next dressing change Thursday.')


def context(text):
    return {'case_facts': [], 'untrusted_sources': [{'kind': 'audio', 'text': text}], 'rejected_claims_do_not_reinstate': []}


def keys(items):
    return [item['key'] for item in items]


def test_complete_wound_visit_has_nothing_missed():
    assert missed_check.missing_items(context(WOUND_COMPLETE), 'NURSE', 'nursing') == []


def test_wound_visit_lists_only_topics_not_mentioned_in_order():
    text = 'Synthetic visit. Wound on right heel. Cleansed with saline and covered with a dry dressing today.'
    assert keys(missed_check.missing_items(context(text), 'NURSE', 'nursing')) == ['pain', 'size', 'wound_bed', 'drainage', 'periwound', 'next_visit']


def test_explicit_negative_counts_as_mentioned():
    text = WOUND_COMPLETE.replace('Pain 1/10.', 'Client denies pain.')
    assert 'pain' not in keys(missed_check.missing_items(context(text), 'NURSE', 'nursing'))


def test_general_nursing_visit_uses_the_short_list():
    text = 'Synthetic visit. Client reports feeling tired since yesterday and is eating less than usual this week.'
    assert keys(missed_check.missing_items(context(text), 'NURSE', 'nursing')) == ['pain', 'vitals', 'next_visit']


def test_doctor_list():
    text = 'Synthetic visit. Here today for a cough for two weeks. Lungs clear on auscultation. Likely viral.'
    assert keys(missed_check.missing_items(context(text), 'PHYSICIAN', 'soap')) == ['plan', 'follow_up']


def test_too_little_information_and_referral_letters_get_no_reminders():
    assert missed_check.missing_items(context('Synthetic test.'), 'NURSE', 'nursing') == []
    assert missed_check.missing_items(context('Synthetic visit. Wound on right heel, dressing changed today.'), 'NURSE', 'referral_letter') == []


def test_reminders_can_be_hidden():
    assert missed_check.enabled(SimpleNamespace(profile={})) is True
    assert missed_check.enabled(SimpleNamespace(profile={'missed_check': False})) is False


def test_report_response_carries_reminders_but_never_writes_them_into_the_note(quick_api, monkeypatch):
    case = start(quick_api)
    add_source(quick_api, case, 'Synthetic visit. Wound on right heel. Cleansed with saline and covered with a dry dressing today.')
    note = 'Wound on right heel cleansed with saline, dry dressing applied.'
    async def generate(context, template, length, **kwargs):
        return {'text': note, 'status': 'ready', 'ui_alerts': [], 'usage': {}}
    monkeypatch.setattr(providers, 'quick_documentation', generate)
    response = quick_api.client.post(f"/api/v1/encounters/{case['encounter']['id']}/quick-report", json={})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data['text'] == note
    assert 'pain' in keys(data['missing_items']) and 'size' in keys(data['missing_items'])
    assert 'drainage' in keys(data['missing_items'])


# ---------- Office computer inbox ----------

def test_note_appears_on_the_computer_and_is_erased_after_copy(api):
    doctor = api.account('desk@example.test')
    sent = send(doctor)
    assert sent.status_code == 201, sent.text
    notes = doctor.client.get('/api/v1/desk/notes').json()['notes']
    assert [note['text'] for note in notes] == ['Synthetic note. Pain 2/10.']
    with api.factory() as db:
        stored = db.scalar(select(DeskNote))
        assert b'Pain 2/10' not in stored.ciphertext  # encrypted at rest
    assert doctor.client.delete('/api/v1/desk/notes/' + notes[0]['id'], headers=doctor.headers).status_code == 204
    assert doctor.client.get('/api/v1/desk/notes').json()['notes'] == []
    with api.factory() as db:
        assert db.scalar(select(func.count()).select_from(DeskNote)) == 0


def test_sending_the_same_visit_again_replaces_the_waiting_copy(api):
    nurse = api.account('resend@example.test')
    send(nurse, text='First wording.')
    send(nurse, text='Edited wording.')
    send(nurse, encounter='visit-2', text='Another visit.')
    notes = nurse.client.get('/api/v1/desk/notes').json()['notes']
    assert sorted(note['text'] for note in notes) == ['Another visit.', 'Edited wording.']


def test_each_clinician_sees_only_their_own_notes(api):
    first, second = api.account('one@example.test'), api.account('two@example.test')
    send(first, text='Only for the first clinician.')
    assert second.client.get('/api/v1/desk/notes').json()['notes'] == []
    note_id = first.client.get('/api/v1/desk/notes').json()['notes'][0]['id']
    second.client.delete('/api/v1/desk/notes/' + note_id, headers=second.headers)
    assert len(first.client.get('/api/v1/desk/notes').json()['notes']) == 1


def test_end_of_day_limits_and_expiry(api):
    nurse = api.account('expiry@example.test')
    far = (datetime.now(timezone.utc) + timedelta(days=5)).isoformat()
    past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
    assert send(nurse, encounter='far', expires=far).status_code == 201
    assert send(nurse, encounter='past', expires=past).status_code == 201
    assert send(nurse, encounter='naive', expires='2026-10-30T23:59:00').status_code == 422
    with api.factory() as db:
        rows = {row.encounter_id: row for row in db.scalars(select(DeskNote))}
        now = utcnow()
        assert rows['far'].expires_at.replace(tzinfo=timezone.utc) <= now + timedelta(hours=24, minutes=1)
        assert rows['past'].expires_at.replace(tzinfo=timezone.utc) >= now + timedelta(minutes=4)
        db.execute(update(DeskNote).values(expires_at=now - timedelta(seconds=1)))
        db.commit()
    assert nurse.client.get('/api/v1/desk/notes').json()['notes'] == []
    with api.factory() as db:
        assert db.scalar(select(func.count()).select_from(DeskNote)) == 0


def test_inbox_needs_sign_in_and_request_verification(api):
    nurse = api.account('csrf@example.test')
    assert nurse.client.post('/api/v1/desk/notes', json={'encounter_id': 'v', 'title': 'Note', 'text': 'x', 'expires_at': tonight()}).status_code == 403
    assert nurse.client.delete('/api/v1/desk/notes').status_code == 403
    stranger = TestClient(nurse.client.app)
    assert stranger.get('/api/v1/desk/notes').status_code == 401


def test_clear_all_and_the_page_is_served_without_caching(api):
    nurse = api.account('clear@example.test')
    send(nurse, encounter='a'); send(nurse, encounter='b')
    assert nurse.client.delete('/api/v1/desk/notes', headers=nurse.headers).status_code == 204
    assert nurse.client.get('/api/v1/desk/notes').json()['notes'] == []
    page = nurse.client.get('/desk/')
    assert page.status_code == 200 and page.headers['cache-control'] == 'no-store' and 'Amilya' in page.text
    assert nurse.client.get('/desk/app.js').status_code == 200


def test_visit_deletion_also_erases_its_waiting_computer_copy():
    import inspect
    from server import work_privacy
    assert 'DeskNote' in inspect.getsource(work_privacy.erase_work)
