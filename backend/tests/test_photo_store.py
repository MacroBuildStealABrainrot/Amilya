"""Viewable wound-photo copies: encrypted at rest, workspace-scoped, gone after the retention period."""
import io

import pytest
from PIL import Image

from server import photo_collection, photo_store
from server.core import settings
from test_clinical import clinical_api, create_case
from test_photo_metadata_privacy import photo_with_location


@pytest.fixture
def stored(clinical_api, tmp_path, monkeypatch):
    monkeypatch.setattr(settings, 'photo_store_dir', str(tmp_path / 'photos'))
    monkeypatch.setattr(settings, 'photo_store_bucket', '')
    monkeypatch.setattr(settings, 'photo_retention_hours', 24)
    monkeypatch.setattr(settings, 'app_secret', 'synthetic-test-secret-' + 'x' * 20)
    clinical_api.client.app.include_router(photo_collection.router, prefix='/api/v1')
    return clinical_api, tmp_path / 'photos'


def upload(api, case, image):
    result = api.client.post(f'/api/v1/encounters/{case["id"]}/upload', data={'kind': 'photo'},
                             files={'file': ('synthetic.jpg', image, 'image/jpeg')})
    assert result.status_code == 201, result.text
    return result.json()['evidence']


def test_uploaded_photo_is_viewable_without_location_details_and_encrypted_at_rest(stored):
    api, folder = stored
    case = create_case(api)
    evidence = upload(api, case, photo_with_location())
    assert evidence['metadata']['raw_media_retained'] is True
    assert evidence['metadata']['photo_expires_at']
    photo = api.client.get(f'/api/v1/evidence/{evidence["id"]}/photo')
    assert photo.status_code == 200
    assert photo.headers['content-type'] == 'image/jpeg'
    assert 'no-store' in photo.headers['cache-control']
    with Image.open(io.BytesIO(photo.content)) as image:
        assert image.size == (48, 64) and not image.getexif()
    stored_files = list(folder.rglob('*'))
    at_rest = b''.join(p.read_bytes() for p in stored_files if p.is_file())
    assert at_rest and photo.content not in at_rest and b'\xff\xd8\xff' not in at_rest


def test_other_workspace_cannot_open_the_photo(stored):
    api, _ = stored
    evidence = upload(api, create_case(api), photo_with_location())
    assert api.client.get(f'/api/v1/evidence/{evidence["id"]}/photo', headers={'X-Test-User': 'u2'}).status_code == 404


def test_photo_is_refused_and_erased_after_its_retention_period(stored, monkeypatch):
    api, folder = stored
    evidence = upload(api, create_case(api), photo_with_location())
    monkeypatch.setattr(photo_store, 'retention_seconds', lambda: -1)
    expired = api.client.get(f'/api/v1/evidence/{evidence["id"]}/photo')
    assert expired.status_code == 410
    assert not [p for p in folder.rglob('*') if p.is_file()]


def test_removing_the_photo_erases_its_copy(stored):
    api, folder = stored
    case = create_case(api)
    evidence = upload(api, case, photo_with_location())
    assert [p for p in folder.rglob('*') if p.is_file()]
    assert api.client.delete(f'/api/v1/encounters/{case["id"]}/photos/{evidence["id"]}').status_code == 200
    assert not [p for p in folder.rglob('*') if p.is_file()]


def test_storage_failure_keeps_the_findings_and_says_the_photo_was_not_kept(stored, monkeypatch):
    api, _ = stored
    def broken(*args):
        raise OSError('synthetic storage outage')
    monkeypatch.setattr(photo_store, 'save', broken)
    evidence = upload(api, create_case(api), photo_with_location())
    assert evidence['metadata']['raw_media_retained'] is False
    assert any('could not be kept' in w for w in evidence['warnings'])
    assert api.client.get(f'/api/v1/evidence/{evidence["id"]}/photo').status_code == 404


def test_photos_are_not_kept_when_no_store_is_configured(clinical_api, monkeypatch):
    monkeypatch.setattr(settings, 'photo_store_dir', '')
    monkeypatch.setattr(settings, 'photo_store_bucket', '')
    evidence = upload(clinical_api, create_case(clinical_api), photo_with_location())
    assert evidence['metadata']['raw_media_retained'] is False
