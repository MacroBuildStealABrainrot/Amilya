from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from hashlib import sha256
import json
from types import SimpleNamespace
from urllib.parse import urlsplit
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import pytest
from sqlalchemy import create_engine, event, select, update
from sqlalchemy.orm import sessionmaker

from server import identity, transfers
from server.clinical_models import ClinicalDocument, DocumentSignature, Encounter, Patient
from server.core import settings
from server.db import Base, get_db, utcnow
from server.transfer_models import ReportTransfer


@pytest.fixture
def api(tmp_path, monkeypatch):
    engine = create_engine('sqlite:///' + str(tmp_path / 'transfers.db'), connect_args={'check_same_thread': False, 'timeout': 20})
    @event.listens_for(engine, 'connect')
    def sqlite_config(connection, _):
        connection.execute('PRAGMA foreign_keys=ON')
        connection.execute('PRAGMA journal_mode=WAL')
    Base.metadata.create_all(engine)
    factory = sessionmaker(engine, expire_on_commit=False)
    app = FastAPI()
    app.include_router(identity.router, prefix='/api/v1')
    app.include_router(transfers.router, prefix='/api/v1')
    app.include_router(transfers.receiver_router)
    def database():
        with factory() as db:
            yield db
    app.dependency_overrides[get_db] = database
    monkeypatch.setattr(identity, 'SessionLocal', factory)
    monkeypatch.setattr(identity, 'engine', engine)
    monkeypatch.setattr(settings, 'app_secret', 'transfer-test-secret-at-least-thirty-two-characters')
    monkeypatch.setattr(settings, 'public_base_url', 'http://testserver')
    monkeypatch.setattr(settings, 'allow_signup', True)
    monkeypatch.setattr(settings, 'secure_cookies', False)
    monkeypatch.setattr(settings, 'legal_version', '2026-09-05')
    client = TestClient(app)
    result = client.post('/api/v1/auth/signup', json={'email': 'transfer@example.test', 'password': 'Synthetic-long-password!',
        'display_name': 'Synthetic user', 'workspace_name': 'Synthetic workspace', 'accept_terms': True,
        'terms_version': '2026-09-05', 'privacy_version': '2026-09-05'})
    assert result.status_code == 201
    session = result.json()
    headers = {'X-CSRF-Token': session['csrf_token']}
    with factory() as db:
        patient = Patient(workspace_id=session['user']['workspace_id'], display_name='Synthetic')
        db.add(patient); db.flush()
        encounter = Encounter(workspace_id=patient.workspace_id, patient_id=patient.id, title='Synthetic', created_by=session['user']['id'])
        db.add(encounter); db.flush()
        text = '<script>alert("untrusted")</script>\nSynthetic exact report.\nPain: 2/10.\n'
        document = ClinicalDocument(workspace_id=patient.workspace_id, encounter_id=encounter.id, text=text,
            template='nursing', version=1, case_revision=1, fact_snapshot=[], request_hash='f'*64,
            content_hash=sha256(text.encode()).hexdigest(), created_by=session['user']['id'])
        db.add(document); db.flush()
        signature = DocumentSignature(document_id=document.id, content_hash=document.content_hash,
            signed_case_revision=1, signed_by=session['user']['id'])
        db.add(signature); db.commit()
    yield SimpleNamespace(client=client, app=app, factory=factory, headers=headers, user=session['user'], document=document, text=text, encounter=encounter)
    client.close(); engine.dispose()


def create(api, identifiers=None):
    result = api.client.post('/api/v1/transfers', json={'document_ids': identifiers or [api.document.id]}, headers=api.headers)
    assert result.status_code == 201, result.text
    data = result.json()
    return data, urlsplit(data['url']).fragment


def redeem(api, token, code):
    return api.client.post('/api/v1/transfers/redeem', json={'token': token, 'code': code})


def test_exact_signed_snapshot_encrypted_and_one_time(api):
    data, token = create(api)
    assert len(data['code']) == 7 and data['code'].isdigit()
    assert urlsplit(data['url']).path == '/receive/' and urlsplit(data['url']).query == ''
    assert len(token) == 43
    with api.factory() as db:
        row = db.get(ReportTransfer, data['transfer_id'])
        assert row.token_hash != token and row.pin_hash != data['code']
        assert api.text.encode() not in row.ciphertext
        assert api.text == json.loads(transfers.cipher().decrypt(row.ciphertext))['documents'][0]['text']
        assert 890 < (row.expires_at.replace(tzinfo=utcnow().tzinfo) - utcnow()).total_seconds() <= 900
    result = redeem(api, token, data['code'])
    assert result.status_code == 200
    doc = result.json()['documents'][0]
    assert doc['text'] == api.text and doc['status'] == 'signed'
    assert doc['content_hash'] == api.document.content_hash and doc['signed_at']
    assert result.headers['cache-control'] == 'no-store' and result.headers['referrer-policy'] == 'no-referrer'
    assert redeem(api, token, data['code']).status_code == 410
    with api.factory() as db:
        row = db.get(ReportTransfer, data['transfer_id'])
        assert row.ciphertext is None and row.pin_hash is None and row.consumed_at
        assert db.get(ClinicalDocument, api.document.id).text == api.text
        assert db.scalar(select(DocumentSignature)).content_hash == api.document.content_hash


def test_create_requires_account_and_csrf(api):
    assert api.client.post('/api/v1/transfers', json={'document_ids': [api.document.id]}).status_code == 403
    with TestClient(api.app) as anonymous:
        assert anonymous.post('/api/v1/transfers', json={'document_ids': [api.document.id]}).status_code == 401


@pytest.mark.parametrize('identifiers', [[], ['not-a-uuid'], [str(uuid4()) for _ in range(51)]])
def test_selection_size_and_ids_are_validated(api, identifiers):
    assert api.client.post('/api/v1/transfers', json={'document_ids': identifiers}, headers=api.headers).status_code == 422


def test_duplicate_missing_and_other_owner_documents_are_rejected(api):
    assert api.client.post('/api/v1/transfers', json={'document_ids': [api.document.id]*2}, headers=api.headers).status_code == 422
    assert api.client.post('/api/v1/transfers', json={'document_ids': [api.document.id, str(uuid4())]}, headers=api.headers).status_code == 404
    with api.factory() as db:
        other = identity.User(email='other@example.test', display_name='Other', password_hash='unused', workspace_id=api.user['workspace_id'])
        db.add(other); db.flush()
        document = ClinicalDocument(workspace_id=api.user['workspace_id'], encounter_id=api.encounter.id, text='OTHER USER',
            template='nursing', version=2, case_revision=1, fact_snapshot=[], request_hash='b'*64,
            content_hash='b'*64, created_by=other.id)
        db.add(document); db.commit()
    assert api.client.post('/api/v1/transfers', json={'document_ids': [document.id]}, headers=api.headers).status_code == 404
    with api.factory() as db:
        assert not list(db.scalars(select(ReportTransfer)))


def test_wrong_code_five_attempt_lock_erases_copy(api):
    data, token = create(api)
    wrong = '0000000' if data['code'] != '0000000' else '1111111'
    for _ in range(5):
        assert redeem(api, token, wrong).status_code == 403
    assert redeem(api, token, data['code']).status_code == 410
    with api.factory() as db:
        row = db.get(ReportTransfer, data['transfer_id'])
        assert row.attempts == 5 and row.ciphertext is None and row.pin_hash is None


def test_four_wrong_then_correct_is_permitted(api):
    data, token = create(api)
    for _ in range(4):
        assert redeem(api, token, 'invalid').status_code == 403
    assert redeem(api, token, data['code']).status_code == 200


def test_pin_is_bound_to_its_random_link(api):
    first, token = create(api)
    second, _ = create(api)
    with api.factory() as db:
        a = db.get(ReportTransfer, first['transfer_id']); b = db.get(ReportTransfer, second['transfer_id'])
        assert a.pin_hash != b.pin_hash
    assert redeem(api, 'A'*43, first['code']).status_code == 410
    assert redeem(api, token, first['code']).status_code == 200


def test_expiry_rejects_and_lazily_clears_copy(api):
    data, token = create(api)
    with api.factory() as db:
        db.execute(update(ReportTransfer).where(ReportTransfer.id == data['transfer_id']).values(expires_at=utcnow()-timedelta(seconds=1)))
        db.commit()
    assert redeem(api, token, data['code']).status_code == 410
    with api.factory() as db:
        assert db.get(ReportTransfer, data['transfer_id']).ciphertext is None


@pytest.mark.parametrize('invalid_payload', [b'damaged ciphertext', None])
def test_damaged_or_malformed_copy_fails_closed(api, invalid_payload):
    data, token = create(api)
    replacement = invalid_payload if invalid_payload else transfers.cipher().encrypt(b'[]')
    with api.factory() as db:
        db.execute(update(ReportTransfer).where(ReportTransfer.id == data['transfer_id']).values(ciphertext=replacement))
        db.commit()
    assert redeem(api, token, data['code']).status_code == 410
    with api.factory() as db:
        row = db.get(ReportTransfer, data['transfer_id'])
        assert row.ciphertext is None and row.pin_hash is None and row.revoked_at


def test_parallel_redemption_delivers_exactly_once(api):
    data, token = create(api)
    def attempt(_):
        with TestClient(api.app) as client:
            return client.post('/api/v1/transfers/redeem', json={'token': token, 'code': data['code']}).status_code
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(attempt, range(4)))
    assert sorted(results) == [200, 410, 410, 410]


def test_revoke_preserves_original_and_blocks_link(api):
    data, token = create(api)
    assert api.client.delete('/api/v1/transfers/' + data['transfer_id'], headers=api.headers).status_code == 204
    assert redeem(api, token, data['code']).status_code == 410
    with api.factory() as db:
        assert db.get(ClinicalDocument, api.document.id).text == api.text


def test_cross_origin_redemption_does_not_consume_attempt(api):
    data, token = create(api)
    response = api.client.post('/api/v1/transfers/redeem', json={'token': token, 'code': data['code']}, headers={'Origin': 'https://attacker.example'})
    assert response.status_code == 403
    with api.factory() as db:
        assert db.get(ReportTransfer, data['transfer_id']).attempts == 0


def test_rate_checks_are_applied_before_transfer_mutation(api, monkeypatch):
    data, token = create(api)
    calls = []
    def limited(*args):
        calls.append(args)
        raise HTTPException(429, 'Rate limited')
    monkeypatch.setattr(transfers, 'check_rate', limited)
    assert redeem(api, token, data['code']).status_code == 429
    assert api.client.post('/api/v1/transfers', json={'document_ids': [api.document.id]}, headers=api.headers).status_code == 429
    assert calls[0][1:] == (30, 60) and calls[1][1:] == (20, 3600)
    with api.factory() as db:
        assert db.get(ReportTransfer, data['transfer_id']).attempts == 0


def test_receiver_no_cache_no_external_assets_or_html_insertion(api):
    response = api.client.get('/receive/')
    assert response.status_code == 200 and response.headers['cache-control'] == 'no-store'
    assert response.headers['referrer-policy'] == 'no-referrer'
    script = api.client.get('/receive/app.js').text
    assert 'innerHTML' not in script and 'text.textContent = report.text' in script
    assert 'sessionStorage' not in script and 'localStorage' not in script
    assert 'history.replaceState' in script and "'/api/v1/transfers/redeem'" in script
    assert 'http://' not in response.text and 'https://' not in response.text


def test_migration_matches_model(tmp_path):
    import importlib.util
    from pathlib import Path
    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    from sqlalchemy import inspect
    project = Path(__file__).resolve().parents[1]
    def module_from(path, name):
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    migration = module_from(project / 'migrations/versions/0004_one_time_report_transfers.py', 'test_migration')
    engine = create_engine('sqlite:///' + str(tmp_path / 'migration.db'))
    with engine.begin() as connection:
        identity.Workspace.__table__.create(connection)
        identity.User.__table__.create(connection)
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()
    inspector = inspect(engine)
    columns = inspector.get_columns('report_transfers')
    assert {c['name'] for c in columns} == set(ReportTransfer.__table__.columns.keys())
    assert len(columns) == 12
    assert {i['name'] for i in inspector.get_indexes('report_transfers')} == {
        'ix_report_transfers_workspace_id', 'ix_report_transfers_created_by', 'ix_report_transfers_expires_at'}
    engine.dispose()
