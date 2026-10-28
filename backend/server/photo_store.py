"""Short-lived encrypted copies of uploaded wound photos so clinicians can view them on the visit.

Each copy is the metadata-stripped JPEG that photo analysis receives, encrypted with a key derived
from APP_SECRET. Copies expire after PHOTO_RETENTION_HOURS: the Fernet token carries its creation
time and is refused once older, so expiry holds even before the bucket lifecycle rule deletes it.
Cloud Run stores copies in the private bucket PHOTO_STORE_BUCKET; without a bucket, PHOTO_STORE_DIR
holds them for local development and tests. With neither set, photos are not kept (the earlier behaviour).
"""
from __future__ import annotations

import base64
import logging
import re
import threading
import time
from hashlib import sha256
from pathlib import Path
from urllib.parse import quote

import httpx
from cryptography.fernet import Fernet, InvalidToken

from server.core import settings

log = logging.getLogger(__name__)
_SAFE_ID = re.compile(r'^[A-Za-z0-9_-]{1,64}$')
_METADATA_TOKEN = 'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token'
_token: tuple[str, float] | None = None
_token_lock = threading.Lock()


def enabled() -> bool:
    return bool(settings.photo_store_bucket or settings.photo_store_dir) and settings.photo_retention_hours > 0


def retention_seconds() -> int:
    return settings.photo_retention_hours * 3600


def _cipher() -> Fernet:
    return Fernet(base64.urlsafe_b64encode(sha256(('photo-store:' + settings.app_secret).encode()).digest()))


def _name(workspace_id: str, evidence_id: str) -> str:
    if not (_SAFE_ID.match(workspace_id) and _SAFE_ID.match(evidence_id)):
        raise ValueError('Unexpected photo identifier')
    return f'photos/{workspace_id}/{evidence_id}'


def _access_token() -> str:
    global _token
    with _token_lock:
        if _token and _token[1] > time.time() + 60:
            return _token[0]
        response = httpx.get(_METADATA_TOKEN, headers={'Metadata-Flavor': 'Google'}, timeout=5)
        response.raise_for_status()
        body = response.json()
        _token = (body['access_token'], time.time() + int(body.get('expires_in', 300)))
        return _token[0]


def _object_url(name: str) -> str:
    return f'https://storage.googleapis.com/storage/v1/b/{settings.photo_store_bucket}/o/{quote(name, safe="")}'


def save(workspace_id: str, evidence_id: str, image: bytes) -> None:
    """Blocking: store one encrypted photo copy. Raises when it cannot be stored."""
    name, token = _name(workspace_id, evidence_id), _cipher().encrypt(image)
    if settings.photo_store_bucket:
        response = httpx.post(f'https://storage.googleapis.com/upload/storage/v1/b/{settings.photo_store_bucket}/o',
                              params={'uploadType': 'media', 'name': name}, content=token, timeout=30,
                              headers={'Authorization': 'Bearer ' + _access_token(), 'Content-Type': 'application/octet-stream'})
        response.raise_for_status()
        return
    path = Path(settings.photo_store_dir) / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(token)


def load(workspace_id: str, evidence_id: str) -> bytes | None:
    """Blocking: the decrypted photo, or None when it is missing or past its retention period."""
    name = _name(workspace_id, evidence_id)
    if settings.photo_store_bucket:
        response = httpx.get(_object_url(name), params={'alt': 'media'}, timeout=30,
                             headers={'Authorization': 'Bearer ' + _access_token()})
        if response.status_code == 404:
            return None
        response.raise_for_status()
        token = response.content
    else:
        path = Path(settings.photo_store_dir) / name
        if not path.exists():
            return None
        token = path.read_bytes()
    try:
        return _cipher().decrypt(token, ttl=retention_seconds())
    except InvalidToken:
        remove(workspace_id, [evidence_id])
        return None


def remove(workspace_id: str, evidence_ids: list[str]) -> None:
    """Blocking, best effort: erase photo copies. Anything missed still expires with its retention period."""
    if not enabled():
        return
    for evidence_id in evidence_ids:
        try:
            name = _name(workspace_id, evidence_id)
            if settings.photo_store_bucket:
                response = httpx.delete(_object_url(name), timeout=15, headers={'Authorization': 'Bearer ' + _access_token()})
                if response.status_code not in (200, 204, 404):
                    response.raise_for_status()
            else:
                (Path(settings.photo_store_dir) / name).unlink(missing_ok=True)
        except Exception:
            log.warning('photo copy erase failed; it will expire with its retention period', exc_info=True)
