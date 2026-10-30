"""Office computer inbox.

The phone sends a finished note here; a computer signed in to the same account shows it with a
Copy button. Each copy is encrypted, belongs to one clinician, and is erased as soon as it is
copied or removed, or at the end of the clinician's day, whichever comes first.
"""
from __future__ import annotations

import base64
import hashlib
import json
from datetime import datetime, timedelta
from pathlib import Path
from typing import Annotated

from cryptography.fernet import Fernet, InvalidToken
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, ConfigDict, StringConstraints
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from server.audit import write_audit
from server.core import settings
from server.db import aware, get_db, utcnow
from server.desk_models import DeskNote
from server.identity import User, check_rate, current_user, require_csrf

router = APIRouter(prefix='/desk', tags=['office-computer'])
page_router = APIRouter(tags=['office-computer'], include_in_schema=False)
HEADERS = {'Cache-Control': 'no-store', 'Pragma': 'no-cache', 'Referrer-Policy': 'no-referrer',
           'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY'}
ASSETS = Path(__file__).with_name('desk_page')
MAX_NOTES = 60
MAX_HOURS = 24
MIN_MINUTES = 5


class DeskNoteIn(BaseModel):
    model_config = ConfigDict(extra='forbid')
    encounter_id: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=36, pattern=r'^[A-Za-z0-9-]+$')]
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
    text: Annotated[str, StringConstraints(min_length=1, max_length=60000)]
    # The phone's own "end of today" (local midnight with its UTC offset), so the day ends where the clinician is.
    expires_at: datetime


def cipher():
    if len(settings.app_secret) < 32:
        raise HTTPException(503, 'The office computer inbox is not configured.', headers=HEADERS)
    key = hashlib.sha256(('desk-note-encryption-v1:' + settings.app_secret).encode()).digest()
    return Fernet(base64.urlsafe_b64encode(key))


def clear_expired(db: Session, limit: int = 200):
    """Bounded lazy clean-up; the caller commits."""
    ids = list(db.scalars(select(DeskNote.id).where(DeskNote.expires_at <= utcnow()).order_by(DeskNote.expires_at).limit(limit)))
    if ids:
        db.execute(delete(DeskNote).where(DeskNote.id.in_(ids)))
    return len(ids)


def end_of_day(requested: datetime) -> datetime:
    """Honour the phone's end of day, but never longer than 24 hours or shorter than 5 minutes."""
    now = utcnow()
    if requested.tzinfo is None:
        raise HTTPException(422, 'Send the end of day with its time zone.', headers=HEADERS)
    return min(max(requested, now + timedelta(minutes=MIN_MINUTES)), now + timedelta(hours=MAX_HOURS))


def note_out(row: DeskNote, crypt: Fernet):
    try:
        data = json.loads(crypt.decrypt(row.ciphertext))
    except (InvalidToken, ValueError, TypeError):
        return None
    return {'id': row.id, 'title': row.title, 'text': data.get('text', ''),
            'updated_at': aware(row.updated_at).isoformat(), 'expires_at': aware(row.expires_at).isoformat()}


@router.post('/notes', status_code=201)
def send_note(payload: DeskNoteIn, user: User = Depends(require_csrf), db: Session = Depends(get_db)):
    check_rate('desk-send:' + user.id, 120, 3600)
    crypt = cipher()
    clear_expired(db)
    expires = end_of_day(payload.expires_at)
    ciphertext = crypt.encrypt(json.dumps({'text': payload.text}, ensure_ascii=False).encode())
    now = utcnow()
    for attempt in range(2):
        row = db.scalar(select(DeskNote).where(DeskNote.created_by == user.id, DeskNote.encounter_id == payload.encounter_id))
        if row is None:
            count = db.scalar(select(DeskNote.id).where(DeskNote.created_by == user.id).offset(MAX_NOTES - 1).limit(1))
            if count:
                raise HTTPException(409, 'Your office computer has 60 notes waiting. Copy or clear some first.', headers=HEADERS)
            row = DeskNote(workspace_id=user.workspace_id, created_by=user.id, encounter_id=payload.encounter_id,
                           title=payload.title, ciphertext=ciphertext, created_at=now, updated_at=now, expires_at=expires)
            db.add(row)
        else:
            # Sending the same visit again replaces the waiting copy with the newest wording.
            row.title, row.ciphertext, row.updated_at, row.expires_at = payload.title, ciphertext, now, expires
        try:
            db.flush()
            write_audit(db, user, 'desk_note.sent', row.id)
            db.commit()
            break
        except IntegrityError:
            db.rollback()
            if attempt:
                raise HTTPException(409, 'Try sending again.', headers=HEADERS)
    return JSONResponse({'id': row.id, 'expires_at': aware(row.expires_at).isoformat()}, status_code=201, headers=HEADERS)


@router.get('/notes')
def list_notes(user: User = Depends(current_user), db: Session = Depends(get_db)):
    crypt = cipher()
    clear_expired(db)
    db.commit()
    rows = db.scalars(select(DeskNote).where(DeskNote.created_by == user.id, DeskNote.workspace_id == user.workspace_id)
                      .order_by(DeskNote.updated_at.desc()).limit(MAX_NOTES))
    notes = [note for note in (note_out(row, crypt) for row in rows) if note]
    return JSONResponse({'notes': notes}, headers=HEADERS)


@router.delete('/notes/{note_id}', status_code=204)
def remove_note(note_id: Annotated[str, StringConstraints(max_length=36)], user: User = Depends(require_csrf), db: Session = Depends(get_db)):
    """Called after Copy, or when the clinician removes a note. Already gone is fine."""
    db.execute(delete(DeskNote).where(DeskNote.id == note_id, DeskNote.created_by == user.id))
    write_audit(db, user, 'desk_note.removed', note_id)
    db.commit()
    return Response(status_code=204, headers=HEADERS)


@router.delete('/notes', status_code=204)
def clear_notes(user: User = Depends(require_csrf), db: Session = Depends(get_db)):
    db.execute(delete(DeskNote).where(DeskNote.created_by == user.id))
    write_audit(db, user, 'desk_note.cleared', user.id)
    db.commit()
    return Response(status_code=204, headers=HEADERS)


@page_router.get('/desk/')
def desk_page():
    return FileResponse(ASSETS / 'index.html', media_type='text/html', headers=HEADERS)


@page_router.get('/desk/app.js')
def desk_script():
    return FileResponse(ASSETS / 'app.js', media_type='application/javascript', headers=HEADERS)
