"""Optional work-phone transfer. Capability and PIN are never placed in request URLs."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
from pathlib import Path
import re
import secrets
from datetime import timedelta
from uuid import UUID

from cryptography.fernet import Fernet, InvalidToken
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from server.audit import write_audit
from server.clinical_models import ClinicalDocument, DocumentSignature
from server.core import settings
from server.db import aware, get_db, utcnow
from server.identity import User, check_rate, require_csrf
from server.transfer_models import ReportTransfer

router = APIRouter(prefix='/transfers', tags=['report-transfer'])
receiver_router = APIRouter(tags=['report-transfer'], include_in_schema=False)
HEADERS = {'Cache-Control': 'no-store', 'Pragma': 'no-cache', 'Referrer-Policy': 'no-referrer',
           'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY'}
ASSETS = Path(__file__).with_name('transfer_receiver')
MAX_ATTEMPTS = 5


class CreateTransfer(BaseModel):
    model_config = ConfigDict(extra='forbid')
    document_ids: list[UUID] = Field(min_length=1, max_length=50)

    @field_validator('document_ids')
    @classmethod
    def unique_documents(cls, value):
        if len(set(value)) != len(value):
            raise ValueError('Select each saved document once')
        return value


class RedeemTransfer(BaseModel):
    model_config = ConfigDict(extra='forbid')
    token: str = Field(max_length=128)
    code: str = Field(max_length=32)


def cipher():
    if len(settings.app_secret) < 32:
        raise HTTPException(503, 'Secure report transfer is not configured.', headers=HEADERS)
    key = hashlib.sha256(('report-transfer-encryption-v1:' + settings.app_secret).encode()).digest()
    return Fernet(base64.urlsafe_b64encode(key))


def token_digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


def pin_digest(token_hash, code):
    return hmac.new(settings.app_secret.encode(), ('report-transfer-pin-v1:' + token_hash + ':' + code).encode(), hashlib.sha256).hexdigest()


def clear_expired(db: Session, limit: int = 100):
    """Bounded lazy retention cleanup; caller commits. Also safe for a maintenance job."""
    ids = list(db.scalars(select(ReportTransfer.id).where(
        ReportTransfer.expires_at <= utcnow(), ReportTransfer.ciphertext.is_not(None)
    ).order_by(ReportTransfer.expires_at).limit(limit)))
    if ids:
        db.execute(update(ReportTransfer).where(ReportTransfer.id.in_(ids)).values(ciphertext=None, pin_hash=None))
    return len(ids)


def unavailable():
    return HTTPException(410, 'This transfer is unavailable, expired or already opened. Ask the sender for a new transfer.', headers=HEADERS)


@router.post('', status_code=201)
def create_transfer(payload: CreateTransfer, user: User = Depends(require_csrf), db: Session = Depends(get_db)):
    check_rate('transfer-create:' + user.id, 20, 3600)
    crypt = cipher()
    identifiers = [str(value) for value in payload.document_ids]
    documents = list(db.scalars(select(ClinicalDocument).where(
        ClinicalDocument.id.in_(identifiers), ClinicalDocument.workspace_id == user.workspace_id,
        ClinicalDocument.created_by == user.id)))
    if len(documents) != len(identifiers):
        raise HTTPException(404, 'One or more saved documents are unavailable.', headers=HEADERS)
    by_id = {doc.id: doc for doc in documents}
    signatures = {sig.document_id: sig for sig in db.scalars(select(DocumentSignature).where(DocumentSignature.document_id.in_(identifiers)))}
    snapshots = []
    for identifier in identifiers:
        doc = by_id[identifier]
        signature = signatures.get(identifier)
        snapshots.append({'id': doc.id, 'text': doc.text, 'template': doc.template,
            'version': doc.version, 'content_hash': doc.content_hash,
            'status': 'signed' if signature else 'draft',
            'signed_at': aware(signature.signed_at).isoformat() if signature else None,
            'created_at': aware(doc.created_at).isoformat()})
    encoded = json.dumps({'documents': snapshots}, ensure_ascii=False, separators=(',', ':')).encode()
    if len(encoded) > 2 * 1024 * 1024:
        raise HTTPException(413, 'Choose fewer reports for this transfer.', headers=HEADERS)
    token = secrets.token_urlsafe(32)
    code = str(secrets.randbelow(10_000_000)).zfill(7)
    hashed = token_digest(token)
    now = utcnow()
    transfer = ReportTransfer(workspace_id=user.workspace_id, created_by=user.id,
        token_hash=hashed, pin_hash=pin_digest(hashed, code), ciphertext=crypt.encrypt(encoded),
        document_count=len(snapshots), expires_at=now + timedelta(minutes=15))
    clear_expired(db)
    db.add(transfer)
    db.flush()
    write_audit(db, user, 'report_transfer.created', transfer.id, {'document_count': len(snapshots)})
    db.commit()
    return JSONResponse({'transfer_id': transfer.id,
        'url': settings.public_base_url.rstrip('/') + '/receive/#' + token,
        'code': code, 'expires_at': aware(transfer.expires_at).isoformat(),
        'document_count': len(snapshots)}, status_code=201, headers=HEADERS)


@router.post('/redeem')
def redeem_transfer(payload: RedeemTransfer, request: Request, db: Session = Depends(get_db)):
    # No cookie authorization: possession of the random URL AND generated PIN is required.
    # Secrets travel only in the JSON body, so proxy and application URL logs cannot retain them.
    origin = request.headers.get('origin')
    if origin and origin.rstrip('/') != settings.public_base_url.rstrip('/'):
        raise HTTPException(403, 'Request origin is not permitted.', headers=HEADERS)
    check_rate('transfer-redeem:' + str(request.client.host if request.client else 'unknown'), 30, 60)
    crypt = cipher()
    clear_expired(db)
    if not re.fullmatch(r'[A-Za-z0-9_-]{43}', payload.token):
        db.commit()
        raise unavailable()
    now = utcnow()
    hashed = token_digest(payload.token)
    # This atomic write serializes all redemption attempts even when there are multiple
    # workers or SQLite ignores SELECT FOR UPDATE. The lock stays held until commit.
    row = db.execute(update(ReportTransfer).where(
        ReportTransfer.token_hash == hashed, ReportTransfer.expires_at > now,
        ReportTransfer.consumed_at.is_(None), ReportTransfer.revoked_at.is_(None),
        ReportTransfer.attempts < MAX_ATTEMPTS, ReportTransfer.ciphertext.is_not(None)
    ).values(attempts=ReportTransfer.attempts + 1).returning(
        ReportTransfer.id, ReportTransfer.pin_hash, ReportTransfer.ciphertext, ReportTransfer.attempts)).first()
    if not row:
        db.commit()
        raise unavailable()
    valid_code = re.fullmatch(r'[0-9]{7}', payload.code) is not None
    if not valid_code or not hmac.compare_digest(row.pin_hash or '', pin_digest(hashed, payload.code)):
        if row.attempts >= MAX_ATTEMPTS:
            db.execute(update(ReportTransfer).where(ReportTransfer.id == row.id).values(ciphertext=None, pin_hash=None))
        db.commit()
        raise HTTPException(403, 'The code was not accepted. Check the seven digits or ask the sender for a new transfer.', headers=HEADERS)
    try:
        result = json.loads(crypt.decrypt(row.ciphertext))
        if not isinstance(result, dict) or not isinstance(result.get('documents'), list):
            raise ValueError('Invalid transfer')
    except (InvalidToken, ValueError, TypeError, UnicodeError):
        db.execute(update(ReportTransfer).where(ReportTransfer.id == row.id).values(ciphertext=None, pin_hash=None, revoked_at=now))
        db.commit()
        raise unavailable()
    db.execute(update(ReportTransfer).where(ReportTransfer.id == row.id).values(ciphertext=None, pin_hash=None, consumed_at=now))
    db.commit()
    return JSONResponse(result, headers=HEADERS)


@router.delete('/{transfer_id}', status_code=204)
def revoke_transfer(transfer_id: UUID, user: User = Depends(require_csrf), db: Session = Depends(get_db)):
    changed = db.execute(update(ReportTransfer).where(ReportTransfer.id == str(transfer_id),
        ReportTransfer.workspace_id == user.workspace_id, ReportTransfer.created_by == user.id
    ).values(ciphertext=None, pin_hash=None, revoked_at=utcnow()))
    if changed.rowcount != 1:
        raise HTTPException(404, 'Transfer not found.', headers=HEADERS)
    write_audit(db, user, 'report_transfer.revoked', str(transfer_id))
    db.commit()
    return Response(status_code=204, headers=HEADERS)


@receiver_router.get('/receive/')
def receive_page():
    return FileResponse(ASSETS / 'index.html', media_type='text/html', headers=HEADERS)


@receiver_router.get('/receive/app.js')
def receive_script():
    return FileResponse(ASSETS / 'app.js', media_type='application/javascript', headers=HEADERS)
