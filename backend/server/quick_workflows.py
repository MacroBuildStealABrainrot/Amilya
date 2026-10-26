"""Independent documentation, photo and conversation entry points.

The existing ledger remains the source of truth. A quick workspace container has
no patient identity, and generating prose never confirms extracted facts.
"""
from __future__ import annotations

import json
from typing import Annotated, Literal
from uuid import UUID, NAMESPACE_URL, uuid5

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import Field, StringConstraints, model_validator
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session

from server import clinical, providers, documentation
from server.clinical_models import Encounter, Evidence, Patient, TimelineEvent
from server.core import settings
from server.db import get_db
from server.identity import User, check_rate, current_user, require_csrf
from server.schemas import StrictModel, TemplateSelection

router = APIRouter(tags=['quick workflows'])
MUTATION = [Depends(require_csrf)]
QuickMode = Literal['document', 'photo', 'consultant']
REVIEW_WARNING = 'Draft for clinician review. Source statements and AI suggestions are not automatically confirmed.'


class QuickStart(StrictModel):
    mode: QuickMode
    request_id: UUID


class QuickReport(TemplateSelection):
    use_ai: bool = True
    preferred_length: Literal['concise', 'standard', 'detailed'] = 'concise'


class ConversationTurn(StrictModel):
    role: Literal['user', 'assistant']
    content: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=10000)]


class ConsultRequest(StrictModel):
    message: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]
    history: list[ConversationTurn] = Field(default_factory=list, max_length=20)

    @model_validator(mode='after')
    def bounded_history(self):
        if sum(len(turn.content) for turn in self.history) > 40000:
            raise ValueError('Conversation history exceeds 40,000 characters')
        return self


class RefineReport(TemplateSelection):
    text: Annotated[str, StringConstraints(min_length=1, max_length=60000)]
    instruction: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]

    @model_validator(mode='after')
    def nonblank_report(self):
        if not self.text.strip():
            raise ValueError('Provide the current report to revise')
        return self


def stable_id(*parts):
    return str(uuid5(NAMESPACE_URL, 'woundcopilot:quick:' + ':'.join(str(part) for part in parts)))


@router.post('/quick/start', status_code=201, dependencies=MUTATION)
def start_quick(payload: QuickStart, user: User = Depends(current_user), db: Session = Depends(get_db)):
    check_rate(f'quick-start:{user.id}', limit=30)
    workspace_id, actor_id = user.workspace_id, user.id
    encounter_id = stable_id('encounter', workspace_id, actor_id, payload.request_id)
    patient_id = stable_id('anonymous-container', workspace_id)
    # Deterministic primary keys make a lost response safe to retry, including
    # races between the first two quick actions in a workspace.
    for attempt in range(2):
        existing = db.get(Encounter, encounter_id)
        if existing:
            clinical.owned(db, Encounter, encounter_id, user)
            event = db.scalar(select(TimelineEvent).where(TimelineEvent.encounter_id == encounter_id,
                TimelineEvent.workspace_id == workspace_id, TimelineEvent.action == 'quick.start'))
            if existing.created_by != actor_id or not event or event.detail.get('mode') != payload.mode:
                raise HTTPException(409, 'This quick request was already used for another action')
            return clinical.get_encounter(encounter_id, user, db)
        try:
            patient = db.get(Patient, patient_id)
            if patient is None:
                db.add(Patient(id=patient_id, workspace_id=workspace_id,
                    display_name='Quick workspace — no patient profile', external_ref=None))
                db.flush()
            elif patient.workspace_id != workspace_id:
                raise HTTPException(409, 'Quick workspace identity conflict')
            titles = {'document': 'New report', 'photo': 'Photo assessment', 'consultant': 'Wound Consultant'}
            encounter = Encounter(id=encounter_id, workspace_id=workspace_id, patient_id=patient_id,
                wound_id=None, title=titles[payload.mode], status='open', revision=1, created_by=actor_id,
                draft_data={'retention':'temporary','quick_mode': payload.mode, 'quick_request_id': str(payload.request_id)})
            db.add(encounter)
            clinical.timeline(db, user, encounter_id, 'quick.start', encounter_id, {'mode': payload.mode})
            db.commit()
            return clinical.get_encounter(encounter_id, user, db)
        except (IntegrityError, OperationalError) as error:
            db.rollback()
            if attempt:
                raise HTTPException(409, 'The workspace is being updated; retry this same action') from error
    raise HTTPException(409, 'Retry this same quick action')


def _order_visit_parts(sources, rows):
    """Parts of the same recorded visit are listed together, part 1 first, at the position of the first part."""
    def visit(source):
        meta = getattr(rows.get(source['id']), 'metadata_json', None) or {}
        return (meta.get('visit_id'), meta.get('visit_part')) if source['kind'] == 'audio' and meta.get('visit_part') and meta.get('visit_id') else None
    if not any(visit(s) for s in sources):
        return sources
    ordered, placed = [], set()
    for source in sources:
        if source['id'] in placed:
            continue
        key = visit(source)
        if key is None:
            ordered.append(source); placed.add(source['id']); continue
        group = sorted((s for s in sources if visit(s) and visit(s)[0] == key[0] and s['id'] not in placed), key=lambda s: visit(s)[1])
        ordered.extend(group); placed.update(s['id'] for s in group)
    return ordered


def source_context(db: Session, encounter: Encounter):
    all_facts = clinical.case_facts(db, encounter)
    facts = [fact for fact in all_facts if fact['confirmation_state'] != 'rejected']
    rejected = [fact for fact in all_facts if fact['confirmation_state'] == 'rejected']
    rows = list(db.scalars(select(Evidence).where(Evidence.workspace_id == encounter.workspace_id,
        Evidence.encounter_id == encounter.id).order_by(Evidence.created_at, Evidence.id)))
    sources = [{'id': row.id, 'kind': row.kind, 'text': row.text,
                'warnings': row.warnings, 'created_at': clinical.iso(row.created_at),
                # A long visit recorded in parts: every part is one source; label them so they read as one visit, in order.
                **({'recording_part': f"Part {row.metadata_json['visit_part']} of {row.metadata_json['visit_parts']} of the same visit recording"}
                   if row.kind == 'audio' and isinstance(row.metadata_json, dict) and row.metadata_json.get('visit_part') else {})}
               for row in rows if row.kind not in {'audio_verification','audio_part'}]
    # Keep the parts of one recorded visit together and in part order, even if they were uploaded out of order.
    sources = _order_visit_parts(sources, {row.id: row for row in rows})
    context = {'photo_groups_clinician_assigned':encounter.draft_data.get('photo_layout',[]), 'case_facts': facts, 'untrusted_sources': sources, 'rejected_claims_do_not_reinstate': rejected}
    if len(json.dumps(context, ensure_ascii=False, default=str)) > 300000:
        raise HTTPException(413, 'This encounter exceeds the conversation context limit. Start a separate report with the relevant source material.')
    return context


def source_only_report(context, template, custom_template=None):
    return documentation.source_report(context, template, clinical.fact_phrase)['text']


@router.post('/encounters/{encounter_id}/quick-report', dependencies=MUTATION)
async def quick_report(encounter_id: str, payload: QuickReport, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = clinical.owned(db, Encounter, encounter_id, user)
    expected = encounter.revision
    context = source_context(db, encounter)
    if not context['case_facts'] and not any(source['text'] for source in context['untrusted_sources']):
        return {**documentation.visible_result({'text': '', 'status': 'no_content'}), 'case_revision': expected, 'template': payload.template, 'fact_snapshot': [], 'source_ids': [], 'review_required': True, 'warnings': []}
    custom_template = payload.custom_template.model_dump() if payload.custom_template else None
    warnings = ['Draft — Clinician Review Required']
    if payload.use_ai:
        check_rate(f'clinical-draft:{user.id}', limit=30)
        try:
            result = await providers.quick_documentation(context, payload.template, payload.preferred_length,
                custom_template=custom_template, user=user, encounter_id=encounter_id)
        except HTTPException as error:
            if error.status_code not in {402,429,502,503,504}:raise
            # Never display the draft rejected by semantic QA. Recover from original source
            # using the already-supported manual projection, including rejected/edited facts.
            result = documentation.source_report(context, payload.template, clinical.fact_phrase)
            result['qa'] = {'passed': False, 'status':'source_only_unchecked', 'discrepancies': []}
            result['recovery'] = {'mode':'source_only','cause': (error.headers or {}).get('X-WCP-Error','REPORT_CHECK_OR_GENERATION_FAILED')}
            result['ui_alerts'] = [{'field':'review','message':'AI generation or checking could not complete. This source-only draft has not passed an AI check. Review it against the original source before using it.'}]
            warnings.append('A source-only draft was recovered. It may retain transcript noise; review speaker attribution, corrections and clinical wording.')
            from . import diagnostics
            diagnostics.event({'source':'server','stage':'report_source_recovery','status':'success','error_code':result['recovery']['cause']})
            from . import monitoring
            monitoring.emit('ai_extraction','failed','extraction_failed')
            monitoring.emit('report','recovered','fallback_without_ai')
    else:
        result = documentation.source_report(context, payload.template, clinical.fact_phrase)
    db.refresh(encounter)
    if encounter.revision != expected:
        raise HTTPException(409, 'The source changed during drafting; generate again against the current information')
    clinical.timeline(db, user, encounter_id, 'document.quick_draft', encounter_id,
        {'case_revision': expected, 'template': payload.template, 'use_ai': payload.use_ai,
         'fact_count': len(context['case_facts']), 'source_count': len(context['untrusted_sources'])})
    clinical.finish(db)
    return {**documentation.visible_result(result), 'case_revision': expected, 'template': payload.template, 'custom_template': custom_template,
            'fact_snapshot': context['case_facts'], 'source_ids': [source['id'] for source in context['untrusted_sources']],
            'review_required': True, 'warnings': list(dict.fromkeys(warnings))}


@router.post('/encounters/{encounter_id}/consult', dependencies=MUTATION)
async def consult(encounter_id: str, payload: ConsultRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = clinical.owned(db, Encounter, encounter_id, user)
    expected = encounter.revision
    context = source_context(db, encounter)
    check_rate(f'clinical-assistant:{user.id}', limit=20)
    result = await providers.wound_consultant(context, payload.message,
        [turn.model_dump() for turn in payload.history], user=user, encounter_id=encounter_id)
    db.refresh(encounter)
    if encounter.revision != expected:
        raise HTTPException(409, 'The source changed during this response; ask again against the current information')
    clinical.timeline(db, user, encounter_id, 'clinical.consult', encounter_id,
        {'case_revision': expected, 'question_hash': clinical.content_hash(payload.message),
         'fact_count': len(context['case_facts']), 'history_count': len(payload.history)})
    clinical.finish(db)
    return {**result, 'case_revision': expected, 'routing': result.get('routing', {}),
            'effort_level': result.get('effort_level', '')}


@router.post('/encounters/{encounter_id}/refine-report', dependencies=MUTATION)
async def refine_report(encounter_id: str, payload: RefineReport, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = clinical.owned(db, Encounter, encounter_id, user)
    expected = encounter.revision
    context = source_context(db, encounter)
    check_rate(f'clinical-draft:{user.id}', limit=30)
    custom_template = payload.custom_template.model_dump() if payload.custom_template else None
    result = await providers.refine_quick_documentation(context, payload.text, payload.instruction, payload.template,
        custom_template=custom_template, user=user, encounter_id=encounter_id)
    db.refresh(encounter)
    if encounter.revision != expected:
        raise HTTPException(409, 'The source changed during editing; review the current information before applying corrections')
    clinical.timeline(db, user, encounter_id, 'document.quick_refine', encounter_id,
        {'case_revision': expected, 'template': payload.template,
         'instruction_hash': clinical.content_hash(payload.instruction), 'report_hash': clinical.content_hash(payload.text)})
    clinical.finish(db)
    return {**documentation.visible_result(result), 'case_revision': expected, 'template': payload.template, 'custom_template': custom_template,
            'review_required': True, 'warnings': [REVIEW_WARNING,
                'Review every changed value and clinical term. Editing this draft does not change source facts.']}


@router.post('/encounters/{encounter_id}/transcribe-correction', dependencies=MUTATION)
async def transcribe_correction(encounter_id: str, file: UploadFile = File(...), consent: bool = Form(False),
    duration_seconds: float | None = Form(None), user: User = Depends(current_user), db: Session = Depends(get_db)):
    return await _transcribe_reviewable_audio(encounter_id, file, consent, duration_seconds, user, db, purpose='correction')


@router.post('/encounters/{encounter_id}/transcribe-message', dependencies=MUTATION)
async def transcribe_message(encounter_id: str, file: UploadFile = File(...), consent: bool = Form(False),
    duration_seconds: float | None = Form(None), user: User = Depends(current_user), db: Session = Depends(get_db)):
    return await _transcribe_reviewable_audio(encounter_id, file, consent, duration_seconds, user, db, purpose='message')


async def _transcribe_reviewable_audio(encounter_id: str, file: UploadFile, consent: bool,
    duration_seconds: float | None, user: User, db: Session, *, purpose: Literal['correction', 'message']):
    """Return reviewable wording only; never add speech messages to clinical source facts."""
    encounter = clinical.owned(db, Encounter, encounter_id, user)
    expected = encounter.revision
    if not consent:
        raise HTTPException(403, 'Recording consent is required before processing audio')
    if duration_seconds is not None and not 0 < duration_seconds <= settings.max_audio_seconds:
        raise HTTPException(422, 'Audio duration exceeds the configured recording limit')
    limit = settings.max_audio_mb * 1024 * 1024
    data = await file.read(limit + 1)
    if not data or len(data) > limit:
        raise HTTPException(413, 'Upload is empty or exceeds its size limit')
    mime = (file.content_type or '').lower().split(';', 1)[0].strip()
    clinical.checked_media(data, mime, 'audio')
    filename = (file.filename or purpose).replace('\\', '/').split('/')[-1][:200]
    check_rate(f'clinical-upload:{user.id}', limit=20)
    result = await providers.transcribe(data, mime, filename, duration_seconds=duration_seconds,
        user=user, encounter_id=encounter_id)
    db.refresh(encounter)
    if encounter.revision != expected:
        action = 'review the current visit before sending the message' if purpose == 'message' else 'review the current report before correcting it'
        raise HTTPException(409, 'The source changed during transcription; ' + action)
    event = 'clinical.message_transcribe' if purpose == 'message' else 'document.correction_transcribe'
    clinical.timeline(db, user, encounter_id, event, encounter_id,
        {'case_revision': expected, 'bytes': len(data), 'mime': mime})
    clinical.finish(db)
    review_warning = ('Review the recognized message before sending it. No message was sent and no clinical evidence was added.'
        if purpose == 'message' else 'Review the recognized correction before applying it. No clinical evidence was added.')
    return {**result, 'case_revision': expected, 'review_required': True,
        'warnings': list(dict.fromkeys([*result.get('warnings', []), review_warning]))}


class ImproveReport(TemplateSelection):
    text: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=30000)]
    expected_revision: int = Field(ge=1)


class ImproveAssessment(StrictModel):
    expected_revision: int = Field(ge=1)


@router.post('/encounters/{encounter_id}/improve-report', dependencies=MUTATION)
async def improve_report(encounter_id: str, payload: ImproveReport, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = clinical.owned(db, Encounter, encounter_id, user)
    if encounter.revision != payload.expected_revision:
        raise HTTPException(409, 'The visit changed; review the current report before improving it')
    check_rate(f'clinical-draft:{user.id}', limit=30)
    result = await providers.checked_quick_text(source_context(db, encounter), 'improve_report',
        {'template': payload.template, 'untrusted_existing_report': payload.text}, user=user, encounter_id=encounter_id)
    db.refresh(encounter)
    if encounter.revision != payload.expected_revision:
        raise HTTPException(409, 'The visit changed while improving the report')
    clinical.timeline(db, user, encounter_id, 'document.improve', encounter_id,
        {'case_revision': payload.expected_revision, 'report_hash': clinical.content_hash(payload.text)})
    clinical.finish(db)
    return {**documentation.visible_result(result), 'case_revision': payload.expected_revision, 'review_required': True}


@router.post('/encounters/{encounter_id}/improve-assessment', dependencies=MUTATION)
async def improve_assessment(encounter_id: str, payload: ImproveAssessment, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = clinical.owned(db, Encounter, encounter_id, user)
    if encounter.revision != payload.expected_revision:
        raise HTTPException(409, 'The visit changed; review current information first')
    check_rate(f'clinical-assistant:{user.id}', limit=20)
    result = await providers.routed('improve_assessment', json.dumps(source_context(db, encounter), ensure_ascii=False, default=str), user=user, encounter_id=encounter_id)
    db.refresh(encounter)
    if encounter.revision != payload.expected_revision:
        raise HTTPException(409, 'The visit changed while reviewing assessment questions')
    clinical.timeline(db, user, encounter_id, 'clinical.improve_assessment', encounter_id, {'case_revision': payload.expected_revision})
    clinical.finish(db)
    return {**result, 'case_revision': payload.expected_revision}
