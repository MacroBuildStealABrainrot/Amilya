"""Clinical evidence ledger and clinician-controlled documentation workflow.

Source extraction, interpretation, approval and signed text are different objects.
Provider calls happen before staging clinical writes. Every clinical mutation uses
an optimistic encounter revision lock; immutable rows preserve earlier decisions.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import timezone
from hashlib import sha256
import json
import re
from typing import Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from pydantic import ValidationError
from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session

from server import providers
from server.audit import write_audit
from server.core import settings
from server.db import get_db, uid, utcnow
from server.identity import User, check_rate, current_user, require_csrf
from server.media_review import parse_photo_review
from server.clinical_models import (ClinicalDocument, ClinicalFact, ClinicalReview, DocumentSignature,
                                    Encounter, Evidence, FactRevision, Patient, TimelineEvent, Wound)
from server.schemas import (AssistantRequest, DocumentCreate, DraftRequest, EncounterCreate, EncounterPatch, EvidenceCreate,
                            FactReview, Finding, PatientCreate, ReviewRequest, SignDocument, WoundCreate,
                            validated_value)

router = APIRouter(tags=['clinical'])
MUTATION = [Depends(require_csrf)]
VISUAL_FIELDS = {'wound_tissue', 'wound_edges', 'periwound', 'exudate'}
SOURCE_LABELS = {'clinician_manual': 'Clinician assessed', 'clinician_statement': 'Clinician stated',
                 'patient_report': 'Patient reported', 'caregiver_report': 'Caregiver reported',
                 'record_derived': 'Record states', 'image_visible': 'Photo-visible observation',
                 'unattributed_transcript': 'Speaker not verified'}
REPEATABLE_FIELDS = {'allergy', 'medication', 'history', 'lab', 'culture', 'imaging', 'vascular_test',
                     'narrative', 'treatment_performed', 'patient_education'}
UNCERTAIN_CONTEXT = re.compile(
    r'\b(if|would|could|should|please|consider|may|might|possible|possibly|suspected|uncertain|unknown|'
    r'unassessed|proposed|planned|previously|historical|yesterday|not assessed|rule out|cannot exclude)\b', re.I)


def content_hash(text: str) -> str:
    return sha256(text.encode('utf-8')).hexdigest()


def digest(payload: Any) -> str:
    return content_hash(json.dumps(payload, sort_keys=True, ensure_ascii=False, separators=(',', ':'), default=str))


def iso(value):
    if value is None:
        return None
    return (value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value).isoformat()


CONSENT_METHODS = {'phone_tap', 'watch_tap'}


def consent_record(method: str | None, confirmed_at: str | None, wording: str | None) -> dict:
    """How the clinician confirmed the client's consent to record, as sent by the app.

    Older apps send only consent=true; that is stored honestly as 'not_recorded'. A spoken answer is never accepted
    as a method: the app requires a tap by the clinician holding the phone or wearing the watch.
    """
    if method not in CONSENT_METHODS:
        return {'method': 'not_recorded'}
    record = {'method': method}
    if isinstance(confirmed_at, str) and 0 < len(confirmed_at) <= 40 and re.fullmatch(r'[0-9T:.+\-Z]+', confirmed_at):
        record['confirmed_at_device'] = confirmed_at
    if isinstance(wording, str) and re.fullmatch(r'[a-z0-9-]{1,40}', wording):
        record['wording'] = wording
    return record


def owned(db: Session, model, identifier: str, user: User):
    row = db.get(model, identifier)
    if row is None or row.workspace_id != user.workspace_id:
        raise HTTPException(404, 'Clinical record not found')
    return row


def selected_wound(db: Session, encounter: Encounter, wound_id: str | None, user: User):
    target = wound_id or encounter.wound_id
    if target:
        wound = owned(db, Wound, target, user)
        if wound.patient_id != encounter.patient_id:
            raise HTTPException(422, 'Wound must belong to the encounter patient')
    return target


def lock_case(db: Session, encounter: Encounter, expected: int, **values) -> int:
    new_revision = expected + 1
    stmt = update(Encounter).where(Encounter.id == encounter.id, Encounter.workspace_id == encounter.workspace_id,
                                   Encounter.revision == expected).values(revision=new_revision, updated_at=utcnow(), **values)
    try:
        changed = db.execute(stmt.execution_options(synchronize_session=False)).rowcount
    except OperationalError as error:
        db.rollback()
        raise HTTPException(409, 'The encounter is being updated; reload and retry') from error
    if changed != 1:
        db.rollback()
        raise HTTPException(409, 'The encounter changed. Reload before applying this action')
    db.expire(encounter)
    return new_revision


def finish(db: Session):
    try:
        db.commit()
    except (IntegrityError, OperationalError) as error:
        db.rollback()
        raise HTTPException(409, 'Another request updated this record; reload and retry') from error


def timeline(db: Session, user: User, encounter_id: str, action: str, resource_id: str, detail=None):
    safe = detail or {}
    db.add(TimelineEvent(workspace_id=user.workspace_id, encounter_id=encounter_id, user_id=user.id,
                         action=action, resource_id=resource_id, detail=safe))
    write_audit(db, user, action, resource_id, {'encounter_id': encounter_id, **safe})


def request_key(request: Request):
    value = request.headers.get('Idempotency-Key')
    if value is not None and (not value.strip() or len(value) > 128):
        raise HTTPException(422, 'Idempotency-Key must contain 1 to 128 characters')
    return value


def previous_request(db, model, user, encounter_id, key, payload_hash):
    if not key:
        return None
    row = db.scalar(select(model).where(model.workspace_id == user.workspace_id,
                                        model.encounter_id == encounter_id, model.request_key == key))
    if row and row.request_hash != payload_hash:
        raise HTTPException(409, 'This idempotency key was already used for different content')
    return row


def patient_out(row):
    return {'id': row.id, 'display_name': row.display_name, 'external_ref': row.external_ref, 'created_at': iso(row.created_at)}


def wound_out(row):
    return {'id': row.id, 'patient_id': row.patient_id, 'label': row.label, 'location': row.location,
            'laterality': row.laterality, 'wound_type': row.wound_type}


def encounter_out(row):
    return {'id': row.id, 'patient_id': row.patient_id, 'wound_id': row.wound_id, 'title': row.title,
            'status': row.status, 'revision': row.revision, 'draft_data': row.draft_data,
            'created_at': iso(row.created_at), 'updated_at': iso(row.updated_at)}


def evidence_out(row):
    return {'id': row.id, 'encounter_id': row.encounter_id, 'wound_id': row.wound_id, 'kind': row.kind,
            'text': row.text, 'metadata': row.metadata_json, 'warnings': row.warnings,
            'created_by': row.created_by, 'created_at': iso(row.created_at)}


def fact_out(fact, revision):
    return {'id': fact.id, 'revision_id': revision.id, 'revision': revision.revision, 'name': fact.name,
            'value': revision.value, 'confirmation_state': revision.confirmation_state,
            'source_type': revision.source_type, 'source_ref': revision.source_ref,
            'source_quote': revision.source_quote, 'source_locator': revision.source_locator,
            'wound_id': fact.wound_id, 'confirmed_by': revision.confirmed_by,
            'confirmed_at': iso(revision.confirmed_at), 'authored_by': revision.authored_by,
            'action': revision.action, 'created_at': iso(revision.created_at)}


def case_facts(db, encounter):
    pairs = db.execute(select(ClinicalFact, FactRevision).join(
        FactRevision, (FactRevision.fact_id == ClinicalFact.id) & (FactRevision.revision == ClinicalFact.current_revision)
    ).where(ClinicalFact.workspace_id == encounter.workspace_id, ClinicalFact.encounter_id == encounter.id)
      .order_by(ClinicalFact.created_at, ClinicalFact.id)).all()
    return [fact_out(fact, revision) for fact, revision in pairs]


def document_out(db, document):
    signature = db.scalar(select(DocumentSignature).where(DocumentSignature.document_id == document.id))
    return {'id': document.id, 'text': document.text, 'template': document.template, 'custom_template': document.custom_template,
            'content_hash': document.content_hash, 'status': 'signed' if signature else 'draft',
            'version': document.version, 'case_revision': signature.signed_case_revision if signature else document.case_revision,
            'saved_case_revision': document.case_revision, 'signed_at': iso(signature.signed_at) if signature else None,
            'signed_by': signature.signed_by if signature else None, 'amends_id': document.amends_id,
            'reason': document.amendment_reason, 'fact_snapshot': document.fact_snapshot,
            'created_at': iso(document.created_at)}


def source_type(text: str, kind: str):
    if kind in {'result', 'document'}:
        return 'record_derived'
    if re.search(r'\b(caregiver|daughter|son|spouse)\s+(reports?|states?|says)', text, re.I):
        return 'caregiver_report'
    if re.search(r'\b(patient|client)\s+(reports?|states?|says|denies)', text, re.I):
        return 'patient_report'
    if kind in {'audio', 'transcript'} and not re.search(r'\b(clinician|nurse|physician|doctor)\s+(reports?|states?|says|observes?|assesses?|notes?)', text, re.I):
        return 'unattributed_transcript'
    return 'clinician_statement'


def extract_explicit(text: str, kind: str = 'typed') -> tuple[list[dict], list[str]]:
    """Small precision-first extractor, never a substitute for source review.

    It requires explicit units, local laterality, contextual drainage wording and
    factual (not hypothetical) sentences. Unsupported statements stay in evidence.
    """
    candidates, warnings = [], []
    number = r'(?:\d+(?:\.\d+)?|\.\d+)'
    measurement = re.compile(rf'(?<![\w.\-])({number})\s*[x×]\s*({number})(?:\s*[x×]\s*({number}))?\s*(cm|mm)\b', re.I)
    site = r'(plantar foot|dorsal foot|lower leg|great toe|heel|ankle|foot|toe|sacrum|coccyx|buttock|arm|hand|leg)'
    location = re.compile(rf'\b(left|right|bilateral|midline)\s+{site}\b', re.I)
    parts = re.split(r'(?<=[.!?])\s+|\n+', text)
    multi_site = len({(m.group(1).lower(), m.group(2).lower()) for m in location.finditer(text)}) > 1
    for sentence in parts:
        if not sentence.strip() or UNCERTAIN_CONTEXT.search(sentence):
            continue
        origin = source_type(sentence, kind)

        def add(name, value, quote=None):
            try:
                value = Finding(name=name, value=value).value
            except ValidationError:
                warnings.append('An extracted candidate had an invalid structure and was omitted.')
                return
            candidates.append({'name': name, 'value': value, 'quote': quote or sentence,
                               'source_type': origin})

        if re.search(r'\b(wounds?|ulcers?|lesions?)\b', sentence, re.I):
            locations = list(location.finditer(sentence))
            if len(locations) == 1:
                loc = locations[0]
                add('wound_location', {'site': loc.group(2).lower(), 'laterality': loc.group(1).lower()})
            if not multi_site and not re.search(rf'(?:^|\s|[x×])\-\s*{number}', sentence):
                labelled = {}
                ambiguous_labels = False
                for field in ('length', 'width', 'depth'):
                    matches = list(re.finditer(rf'\b{field}\s*(?:(?:is|was|of)\s*)?[:=]?\s*({number})\s*(cm|mm)\b', sentence, re.I))
                    adjective = {'length': 'long', 'width': 'wide', 'depth': 'deep'}[field]
                    matches += list(re.finditer(rf'(?<![\w.\-])({number})\s*(cm|mm)\s+{adjective}\b', sentence, re.I))
                    if len(matches) > 1: ambiguous_labels = True
                    if len(matches) == 1:
                        labelled[field + '_cm'] = float(matches[0].group(1)) / (10 if matches[0].group(2).lower() == 'mm' else 1)
                if not ambiguous_labels and {'length_cm', 'width_cm'} <= labelled.keys():
                    add('wound_measurement', labelled)
                for match in measurement.finditer(sentence):
                    if re.search(r'[x×]\s*$', sentence[:match.start()], re.I):
                        continue  # Never salvage a suffix from an invalid longer measurement.
                    divisor = 10 if match.group(4).lower() == 'mm' else 1
                    value = {'length_cm': float(match.group(1)) / divisor, 'width_cm': float(match.group(2)) / divisor}
                    if match.group(3) is not None:
                        value['depth_cm'] = float(match.group(3)) / divisor
                    add('wound_measurement', value)
            elif measurement.search(sentence):
                warnings.append('Multiple wound sites occur in this source. Assign measurements to a specific wound manually.')
        if re.search(r'\b(drainage|exudate)\b', sentence, re.I):
            if re.search(r'\b(scant|small|moderate|large|copious|none|no|serous|purulent|serosanguineous|sanguineous)\b', sentence, re.I):
                add('exudate', {'text': sentence})
        for name, terms in {'fever': 'fever', 'chills': 'chills', 'diabetes': 'diabetes', 'odour': '(?:odour|odor)',
                            'undermining': 'undermining', 'tunnelling': '(?:tunnelling|tunneling)'}.items():
            negative = re.search(rf'\b(?:no|denies|without)\s+{terms}\b', sentence, re.I)
            positive = re.search(rf'\b(?:has|reports|observed|present|with)\s+(?:a\s+)?{terms}\b', sentence, re.I)
            if negative:
                add(name, {'present': False, 'text': sentence})
            elif positive:
                add(name, {'present': True, 'text': sentence})
        pain = re.search(r'\bpain\s*(?:(?:is|score)\s*)?(\d+(?:\.\d+)?)\s*(?:/|out of)\s*10\b', sentence, re.I)
        if pain and float(pain.group(1)) <= 10:
            add('pain', {'score_0_10': float(pain.group(1)), 'text': sentence})
        elif re.search(r'\b(?:denies|no)\s+pain\b', sentence, re.I):
            add('pain', {'present': False, 'text': sentence})
    return candidates, list(dict.fromkeys(warnings))


def validate_candidates(raw, text: str | None, kind: str) -> tuple[list[dict], list[str]]:
    accepted, warnings = [], []
    if not isinstance(raw, list):
        return [], ['The extraction response had an invalid facts structure.']
    for item in raw[:100]:
        try:
            finding = Finding(name=item['name'], value=item['value'])
            if kind == 'photo' and finding.name not in VISUAL_FIELDS:
                raise ValueError('Photo cannot support this field')
            quote = item.get('quote') or item.get('source_quote')
            if text is not None and (not isinstance(quote, str) or not quote or quote not in text):
                raise ValueError('No exact supporting source quote')
            if text is not None:
                # An exact substring alone is insufficient: "no fever" inside
                # "document no fever if assessed" is not negative evidence.
                containing = [part for part in re.split(r'(?<=[.!?])\s+|\n+', text) if quote in part]
                contexts = containing or [quote]
                if finding.name not in {'history', 'narrative', 'patient_education'} and any(
                    UNCERTAIN_CONTEXT.search(context)
                    for context in contexts
                ):
                    raise ValueError('Conditional content is not an assessed finding')
                if finding.name == 'wound_measurement':
                    measured, _ = extract_explicit(quote if re.search(r'\b(wounds?|ulcers?|lesions?)\b', quote, re.I) else 'Wound: ' + quote, kind)
                    if not any(f['name'] == finding.name and f['value'] == finding.value for f in measured):
                        raise ValueError('Dimensions require exact supported unit conversion')
                if 'present' in finding.value:
                    terms = {'fever': 'fever', 'chills': 'chills', 'diabetes': 'diabetes', 'odour': '(?:odour|odor)',
                             'undermining': 'undermining', 'tunnelling': '(?:tunnelling|tunneling)', 'pain': 'pain'}
                    if finding.name in terms:
                        term = terms[finding.name]
                        absent = re.search(rf'\b(?:no|denies|without)\s+{term}\b', quote, re.I)
                        present = re.search(rf'\b(?:has|reports|observed|present|with|noted|positive for)\s+(?:a\s+)?{term}\b|\b{term}\s+(?:is\s+)?(?:present|observed|noted|positive)\b', quote, re.I)
                        if (finding.value['present'] and (absent or not present)) or (not finding.value['present'] and not absent):
                            raise ValueError('Presence does not agree with explicit source polarity')
            if quote is not None and (not isinstance(quote, str) or len(quote) > 8000):
                raise ValueError('Invalid source quote')
            # Provider output cannot assign confirmation, source authority or wound identity.
            accepted.append({'name': finding.name, 'value': finding.value, 'quote': quote,
                             'source_type': 'image_visible' if kind == 'photo' else source_type(quote or '', kind)})
        except (KeyError, TypeError, ValueError, ValidationError):
            warnings.append('An unsupported or invalid extracted finding was omitted; review the original source.')
    return accepted, list(dict.fromkeys(warnings))


def conflicts_for(facts: list[dict]) -> list[dict]:
    groups = defaultdict(list)
    for fact in facts:
        if fact['confirmation_state'] == 'confirmed' and fact['name'] not in REPEATABLE_FIELDS:
            groups[(fact.get('wound_id'), fact['name'])].append(fact)
    conflicts = []
    for (wound_id, name), values in groups.items():
        if len({digest(f['value']) for f in values}) > 1:
            conflicts.append({'id': digest([wound_id, name, [f['id'] for f in values]])[:20], 'name': name,
                              'wound_id': wound_id, 'fact_ids': [f['id'] for f in values],
                              'message': f'Confirmed {name.replace("_", " ")} observations disagree. Review their sources and reject outdated observations or document the change.'})
    return conflicts


def approved_projection(facts):
    conflicts = conflicts_for(facts)
    blocked = {fid for conflict in conflicts for fid in conflict['fact_ids']}
    return [f for f in facts if f['confirmation_state'] == 'confirmed' and f['id'] not in blocked], conflicts


def deterministic_review(facts: list[dict], wounds: list[dict] | None = None):
    approved, conflicts = approved_projection(facts)
    gaps, warnings = [], []
    by_wound = defaultdict(list)
    for fact in approved:
        by_wound[fact.get('wound_id')].append(fact)
    for wound in wounds or []:
        by_wound.setdefault(wound['id'], [])
    if not by_wound:
        by_wound[None] = []
    for wound_id, values in by_wound.items():
        def has(name):
            return any(f['name'] == name for f in values)

        def add(key, title, text):
            gaps.append({'id': f'{wound_id or "encounter"}:{key}', 'title': title, 'message': text,
                         'wound_id': wound_id, 'severity': 'review', 'source': 'deterministic completeness check'})

        measurements = [f for f in values if f['name'] == 'wound_measurement']
        if not measurements:
            add('measurement', 'Measurements not confirmed', 'Enter current wound dimensions if measured. Missing dimensions remain unknown.')
        elif any('depth_cm' not in f['value'] for f in measurements):
            add('depth', 'Depth not recorded', 'Depth is unknown. A photograph or length/width alone does not establish depth.')
        for field, title in [('pain', 'Pain assessment'), ('periwound', 'Periwound assessment'),
                             ('perfusion_assessment', 'Perfusion assessment'), ('offloading_assessment', 'Pressure and offloading assessment')]:
            if not has(field) and not (field == 'perfusion_assessment' and has('vascular_test')):
                add(field, f'{title} not confirmed', 'Document this assessment when applicable; absence is not a normal finding.')
        if not has('infection_findings'):
            add('infection', 'Infection screen not confirmed', 'Visible drainage alone does not establish an infection screen. Record relevant bedside and systemic findings.')
        positive_systemic = any(f['name'] in {'fever', 'chills'} and f['value'].get('present') is True for f in values)
        positive_local = any(f['name'] == 'infection_findings' and f['value'].get('present') is True for f in values)
        if positive_systemic and positive_local:
            warnings.append('Confirmed systemic symptoms and positive local infection findings require prompt clinician assessment in context.')
        if any(f['name'] == 'diabetes' and f['value'].get('present') is True for f in values) and not has('glucose_control'):
            add('glucose_control', 'Glycemic context not confirmed', 'Review diabetes control when relevant to this encounter.')
    if any(f['confirmation_state'] == 'unconfirmed' for f in facts):
        warnings.append('Unconfirmed suggestions are excluded from chart-ready documentation.')
    if conflicts:
        warnings.append('Conflicting approved observations are excluded from documentation until reconciled.')
    return {'gaps': gaps, 'warnings': warnings, 'conflicts': conflicts, 'considerations': []}


def fact_phrase(fact):
    name, value = fact['name'], fact['value']
    if name == 'wound_measurement':
        number = lambda n: format(n, '.12g')
        size = f"{number(value['length_cm'])} × {number(value['width_cm'])}"
        if 'depth_cm' in value:
            size += f" × {number(value['depth_cm'])}"
        return f"Wound measured {size} cm"
    if name == 'wound_location':
        return 'Wound location: ' + ' '.join(filter(None, [value.get('laterality'), value['site']]))
    label = name.replace('_', ' ').capitalize()
    if value.get('text'):
        return value['text']
    if 'present' in value:
        return f"{label}: {'present' if value['present'] else 'absent'}"
    if name == 'pain' and 'score_0_10' in value:
        return f"Pain: {value['score_0_10']:g}/10"
    if name == 'wound_tissue':
        return 'Wound bed: ' + ', '.join(f'{v:g}% {k}' for k, v in value.items() if isinstance(v, (int, float)))
    raise ValueError('Unsupported clinical value')


def deterministic_note(facts, template='nursing', wound_labels=None, custom_template=None):
    approved, conflicts = approved_projection(facts)
    warnings = []
    if conflicts:
        warnings.append('Resolve conflicting confirmed findings before finalizing. Conflicting values were excluded.')
    if not approved:
        return {'text': '', 'fact_snapshot': [], 'warnings': warnings + ['No approved, nonconflicting findings are available.']}
    from .documentation import format_source_lines
    groups = defaultdict(list)
    for fact in approved:
        groups[fact.get('wound_id')].append(fact_phrase(fact))
    sections = []
    for wound_id, lines in groups.items():
        heading = (wound_labels or {}).get(wound_id, '')
        sections.append((heading + '\n' if heading else '') + format_source_lines(lines, template))
    return {'text': '\n\n'.join(sections), 'fact_snapshot': approved, 'warnings': warnings}



def add_facts(db, evidence, candidates, user, confirmed=False):
    rows, seen = [], set()
    for item in candidates:
        key = digest([item['name'], item['value'], item.get('source_type')])
        if key in seen:
            continue
        seen.add(key)
        fact = ClinicalFact(id=uid(), workspace_id=user.workspace_id, encounter_id=evidence.encounter_id,
                            evidence_id=evidence.id, wound_id=evidence.wound_id, name=item['name'], current_revision=1)
        db.add(fact)
        revision = FactRevision(id=uid(), fact_id=fact.id, revision=1, value=item['value'],
                                confirmation_state='confirmed' if confirmed else 'unconfirmed',
                                source_type='clinician_manual' if confirmed else item['source_type'], source_ref=evidence.id,
                                source_quote=item.get('quote'), source_locator=item.get('locator') or {},
                                confirmed_by=user.id if confirmed else None, confirmed_at=utcnow() if confirmed else None,
                                authored_by=user.id, action='attest' if confirmed else 'extract')
        db.add(revision)
        rows.append((fact, revision))
    return rows


def source_response(db, encounter, evidence):
    pairs = db.execute(select(ClinicalFact, FactRevision).join(FactRevision, (FactRevision.fact_id == ClinicalFact.id) &
                        (FactRevision.revision == ClinicalFact.current_revision)).where(ClinicalFact.evidence_id == evidence.id)).all()
    quality = evidence.metadata_json.get('photo_quality')
    return {'evidence': evidence_out(evidence), 'facts': [fact_out(f, r) for f, r in pairs],
            'warnings': evidence.warnings, 'case_revision': encounter.revision,
            **({'photo_quality': quality} if quality is not None else {})}


@router.get('/patients')
def list_patients(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return [patient_out(p) for p in db.scalars(select(Patient).where(Patient.workspace_id == user.workspace_id).order_by(Patient.created_at.desc()).limit(500))]


@router.post('/patients', status_code=201, dependencies=MUTATION)
def create_patient(payload: PatientCreate, user: User = Depends(current_user), db: Session = Depends(get_db)):
    row = Patient(id=uid(), workspace_id=user.workspace_id, **payload.model_dump())
    db.add(row)
    write_audit(db, user, 'patient.create', row.id, {})
    finish(db)
    return patient_out(row)


@router.get('/patients/{patient_id}/wounds')
def list_wounds(patient_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    owned(db, Patient, patient_id, user)
    return [wound_out(w) for w in db.scalars(select(Wound).where(Wound.workspace_id == user.workspace_id, Wound.patient_id == patient_id))]


@router.post('/patients/{patient_id}/wounds', status_code=201, dependencies=MUTATION)
def create_wound(patient_id: str, payload: WoundCreate, user: User = Depends(current_user), db: Session = Depends(get_db)):
    owned(db, Patient, patient_id, user)
    row = Wound(id=uid(), workspace_id=user.workspace_id, patient_id=patient_id, **payload.model_dump())
    db.add(row)
    write_audit(db, user, 'wound.create', row.id, {'patient_id': patient_id})
    finish(db)
    return wound_out(row)


@router.get('/encounters')
def list_encounters(patient_id: str | None = None, user: User = Depends(current_user), db: Session = Depends(get_db)):
    stmt = select(Encounter).where(Encounter.workspace_id == user.workspace_id)
    if patient_id:
        owned(db, Patient, patient_id, user)
        stmt = stmt.where(Encounter.patient_id == patient_id)
    return [encounter_out(e) for e in db.scalars(stmt.order_by(Encounter.updated_at.desc()).limit(500))]


@router.post('/encounters', status_code=201, dependencies=MUTATION)
def create_encounter(payload: EncounterCreate, user: User = Depends(current_user), db: Session = Depends(get_db)):
    patient = owned(db, Patient, payload.patient_id, user)
    if payload.wound_id:
        wound = owned(db, Wound, payload.wound_id, user)
        if wound.patient_id != patient.id:
            raise HTTPException(422, 'Wound must belong to the selected patient')
    row = Encounter(id=uid(), workspace_id=user.workspace_id, patient_id=patient.id, wound_id=payload.wound_id,
                    title=payload.title or 'Wound care encounter', created_by=user.id, revision=1, status='open')
    db.add(row)
    db.flush()
    timeline(db, user, row.id, 'encounter.create', row.id)
    finish(db)
    return encounter_out(row)


@router.get('/encounters/{encounter_id}')
def get_encounter(encounter_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = owned(db, Encounter, encounter_id, user)
    facts = case_facts(db, encounter)
    review = db.scalar(select(ClinicalReview).where(ClinicalReview.encounter_id == encounter_id).order_by(ClinicalReview.created_at.desc()))
    result = dict(review.result) if review else None
    if result:
        result['stale'] = review.case_revision != encounter.revision
    return {'encounter': encounter_out(encounter), 'patient': patient_out(owned(db, Patient, encounter.patient_id, user)),
            'wound': wound_out(owned(db, Wound, encounter.wound_id, user)) if encounter.wound_id else None,
            'evidence': [evidence_out(e) for e in db.scalars(select(Evidence).where(Evidence.encounter_id == encounter_id, Evidence.kind!='audio_part').order_by(Evidence.created_at))],
            'facts': facts, 'documents': [document_out(db, d) for d in db.scalars(select(ClinicalDocument).where(ClinicalDocument.encounter_id == encounter_id).order_by(ClinicalDocument.version.desc()))],
            'review': result, 'conflicts': conflicts_for(facts)}


@router.patch('/encounters/{encounter_id}', dependencies=MUTATION)
def patch_encounter(encounter_id: str, payload: EncounterPatch, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = owned(db, Encounter, encounter_id, user)
    values = payload.model_dump(exclude_none=True, exclude={'expected_revision'})
    if 'draft_data' in values:
        # Retention and photo grouping have explicit validated endpoints.
        for field in ('retention','photo_layout'):
            values['draft_data'].pop(field,None)
            if field in encounter.draft_data:values['draft_data'][field]=encounter.draft_data[field]
    if 'draft_data' in values and len(json.dumps(values['draft_data']).encode()) > 120000:
        raise HTTPException(413, 'Draft data is too large')
    lock_case(db, encounter, payload.expected_revision, **values)
    timeline(db, user, encounter_id, 'encounter.update', encounter_id, {'fields': list(values)})
    finish(db)
    return encounter_out(encounter)


@router.post('/encounters/{encounter_id}/evidence', status_code=201, dependencies=MUTATION)
async def create_evidence(encounter_id: str, payload: EvidenceCreate, request: Request,
                          user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = owned(db, Encounter, encounter_id, user)
    wound_id = selected_wound(db, encounter, payload.wound_id, user)
    key, payload_hash = request_key(request), digest(payload.model_dump(mode='json'))
    previous = previous_request(db, Evidence, user, encounter_id, key, payload_hash)
    if previous:
        return source_response(db, encounter, previous)
    expected = encounter.revision
    candidates, warnings = [], []
    attested = payload.kind in {'manual', 'measurement'}
    if attested:
        candidates = [{'name': f.name, 'value': f.value, 'quote': f.source_quote} for f in payload.findings]
    else:
        candidates, warnings = extract_explicit(payload.text or '', payload.kind)
        check_rate(f'clinical-extraction:{user.id}', limit=40)
        try:
            ai = await providers.extract_text(payload.text or '', payload.kind, user=user, db=db, encounter_id=encounter_id)
            extra, validation_warnings = validate_candidates(ai.get('facts', []), payload.text, payload.kind)
            candidates.extend(extra)
            warnings.extend(validation_warnings + [str(w) for w in ai.get('warnings', [])])
        except HTTPException as error:
            if error.status_code not in {402, 422, 429, 502, 503, 504}:
                raise
            from .error_feedback import extraction_warning
            warnings.append(extraction_warning(error))
            if error.status_code == 402:
                warnings.append('This account needs an AI entitlement or app credits. Ask the owner to enable access; adding provider funds alone does not activate this account.')
    lock_case(db, encounter, expected)
    evidence = Evidence(id=uid(), workspace_id=user.workspace_id, encounter_id=encounter_id, wound_id=wound_id,
                        kind=payload.kind, text=payload.text, created_by=user.id, request_key=key, request_hash=payload_hash,
                        metadata_json={'clinician_attested': attested, 'transcript_consent': payload.consent,
                                       'source_text_hash': content_hash(payload.text) if payload.text else None},
                        warnings=list(dict.fromkeys(warnings)))
    db.add(evidence)
    db.flush()
    add_facts(db, evidence, candidates, user, confirmed=attested)
    timeline(db, user, encounter_id, 'evidence.create', evidence.id, {'kind': payload.kind, 'fact_count': len(candidates), 'attested': attested})
    finish(db)
    return source_response(db, encounter, evidence)


def checked_media(data: bytes, mime: str, kind: str):
    if kind == 'photo':
        signatures = {'image/jpeg': data.startswith(b'\xff\xd8\xff'), 'image/png': data.startswith(b'\x89PNG\r\n\x1a\n'),
                      'image/webp': data.startswith(b'RIFF') and data[8:12] == b'WEBP'}
        if not signatures.get(mime, False):
            raise HTTPException(415, 'The photo content must match a supported JPEG, PNG or WebP format')
    elif kind == 'document':
        if mime == 'application/pdf':
            if not data.startswith(b'%PDF-'):
                raise HTTPException(415, 'The file content is not a PDF')
        elif mime in {'text/plain', 'text/csv'}:
            try:
                text = data.decode('utf-8-sig')
                if '\x00' in text:
                    raise ValueError()
            except (UnicodeDecodeError, ValueError):
                raise HTTPException(415, 'Text documents must contain UTF-8 text')
        else:
            raise HTTPException(415, 'Supported documents are PDF, UTF-8 text and CSV')
    elif kind == 'audio':
        if mime not in {'audio/webm', 'video/webm', 'audio/ogg', 'audio/wav', 'audio/x-wav', 'audio/mpeg', 'audio/mp4', 'audio/x-m4a'}:
            raise HTTPException(415, 'Unsupported audio format')
        supported = data.startswith((b'\x1aE\xdf\xa3', b'OggS', b'RIFF', b'ID3')) or data[4:8] == b'ftyp' or (len(data) > 2 and data[0] == 255 and data[1] & 224 == 224)
        if not supported:
            raise HTTPException(415, 'Audio content does not match a supported container')
    else:
        raise HTTPException(422, 'Upload kind must be photo, document or audio')


@router.post('/encounters/{encounter_id}/upload', status_code=201, dependencies=MUTATION)
async def upload_evidence(encounter_id: str, request: Request, file: UploadFile = File(...), kind: str = Form(...),
                          consent: bool = Form(False), duration_seconds: float | None = Form(None),
                          wound_id: str | None = Form(None), photo_review: str | None = Form(None),
                          consent_method: str | None = Form(None), consent_confirmed_at: str | None = Form(None),
                          consent_wording: str | None = Form(None),
                          user: User = Depends(current_user), db: Session = Depends(get_db)):
    from . import diagnostics
    diagnostics.bind(user)
    if kind == 'photo':diagnostics.task('photo_assessment')
    encounter = owned(db, Encounter, encounter_id, user)
    target_wound = selected_wound(db, encounter, wound_id, user)
    capture_review = parse_photo_review(photo_review, kind)
    if kind == 'audio' and not consent:
        raise HTTPException(403, 'Recording consent is required before processing audio')
    if duration_seconds is not None and (not 0 < duration_seconds <= settings.max_audio_seconds):
        raise HTTPException(422, 'Audio duration exceeds the configured recording limit')
    limit_mb = settings.max_audio_mb if kind == 'audio' else settings.max_image_mb if kind == 'photo' else settings.max_document_mb
    limit = limit_mb * 1024 * 1024
    with diagnostics.measure('upload_read'):
        data = await file.read(limit + 1)
    if not data or len(data) > limit:
        raise HTTPException(413, 'Upload is empty or exceeds its size limit')
    mime = (file.content_type or '').lower().split(';', 1)[0].strip()
    with diagnostics.measure('media_validation'):
        checked_media(data, mime, kind)
    filename = (file.filename or 'source').replace('\\', '/').split('/')[-1][:200]
    media_hash = sha256(data).hexdigest()
    payload_fingerprint = [kind, mime, filename, media_hash, target_wound, consent, duration_seconds]
    if capture_review is not None:
        payload_fingerprint.append(capture_review)
    payload_hash = digest(payload_fingerprint)
    key = request_key(request)
    previous = previous_request(db, Evidence, user, encounter_id, key, payload_hash)
    if previous:
        return source_response(db, encounter, previous)
    if kind=='photo' and (db.scalar(select(func.count()).select_from(Evidence).where(Evidence.encounter_id==encounter_id,Evidence.kind=='photo')) or 0)>=20:
        raise HTTPException(422,'This collection already contains 20 photos. Remove an unneeded photo before adding another.')
    expected = encounter.revision
    warnings, candidates, text, segments = [], [], None, []
    automatic_quality = None
    check_rate(f'clinical-upload:{user.id}', limit=20)
    if kind == 'audio':
        result = await providers.transcribe(data, mime, filename, duration_seconds=duration_seconds, user=user, db=db, encounter_id=encounter_id)
        text, segments = result.get('text') or '', result.get('segments') or []
        candidates, local_warnings = extract_explicit(text, 'transcript')
        warnings.extend(local_warnings)
        try:
            extracted = await providers.extract_text(text, 'transcript', user=user, db=db, encounter_id=encounter_id)
            extra, validation_warnings = validate_candidates(extracted.get('facts', []), text, 'transcript')
            candidates.extend(extra)
            warnings.extend(validation_warnings + [str(w) for w in extracted.get('warnings', [])])
        except HTTPException as error:
            from .error_feedback import extraction_warning
            warnings.append(extraction_warning(error))
    elif kind == 'document' and mime in {'text/plain', 'text/csv'}:
        text = data.decode('utf-8-sig')
        if len(text) > 40000:
            raise HTTPException(413, 'Text document exceeds 40,000 characters')
        result = await providers.extract_text(text, 'document', user=user, db=db, encounter_id=encounter_id)
        candidates, validation_warnings = validate_candidates(result.get('facts', []), text, 'document')
        warnings.extend(validation_warnings)
    else:
        result = await providers.analyze_media(data, mime, kind, user=user, db=db, encounter_id=encounter_id)
        if kind == 'photo':
            automatic_quality = result.get('quality')
        if kind == 'document':
            text = result.get('source_text')
            if not isinstance(text, str) or not text.strip() or len(text) > settings.max_text_chars:
                raise HTTPException(422, 'Document extraction requires readable source text for verification')
        candidates, validation_warnings = validate_candidates(result.get('facts', []), text, kind)
        if automatic_quality and automatic_quality.get('retake_required'):
            candidates = []
        warnings.extend(validation_warnings)
    warnings.extend(str(w) for w in result.get('warnings', []))
    if kind == 'photo':
        warnings.append(automatic_quality['message'] if automatic_quality and automatic_quality.get('retake_required')
                        else 'Photo observations require clinical correlation.')
    for candidate in candidates:
        if segments:
            candidate['locator'] = {'segments': [index for index, segment in enumerate(segments)
                                                 if isinstance(segment, dict) and candidate.get('quote') and candidate['quote'] in str(segment.get('text', ''))]}
    lock_case(db, encounter, expected)
    evidence = Evidence(id=uid(), workspace_id=user.workspace_id, encounter_id=encounter_id, wound_id=target_wound,
                        kind=kind, text=text, created_by=user.id, request_key=key, request_hash=payload_hash,
                        metadata_json={'filename': filename, 'mime': mime, 'bytes': len(data), 'media_hash': media_hash,
                                       'raw_media_retained': False, 'consent': consent, 'duration_seconds': duration_seconds,
                                       **({'consent_record': consent_record(consent_method, consent_confirmed_at, consent_wording)} if kind == 'audio' else {}),
                                       'segments': segments, 'source_text_hash': content_hash(text) if text is not None else None,
                                       **({'original_transcript': result['original_text']} if kind == 'audio' and isinstance(result.get('original_text'), str) else {}),
                                       **({'photo_quality': automatic_quality} if automatic_quality is not None else {}),
                                       **({'photo_review': capture_review} if capture_review is not None else {})},
                        warnings=list(dict.fromkeys(warnings)))
    db.add(evidence)
    db.flush()
    add_facts(db, evidence, candidates, user)
    timeline(db, user, encounter_id, 'evidence.upload', evidence.id, {'kind': kind, 'mime': mime, 'bytes': len(data), 'fact_count': len(candidates)})
    with diagnostics.measure('evidence_save'):
        finish(db)
    return source_response(db, encounter, evidence)


@router.post('/facts/{fact_id}/review', dependencies=MUTATION)
def review_fact(fact_id: str, payload: FactReview, user: User = Depends(current_user), db: Session = Depends(get_db)):
    fact = owned(db, ClinicalFact, fact_id, user)
    encounter = owned(db, Encounter, fact.encounter_id, user)
    old = db.scalar(select(FactRevision).where(FactRevision.fact_id == fact.id, FactRevision.revision == fact.current_revision))
    if payload.expected_revision != fact.current_revision:
        raise HTTPException(409, 'This finding changed. Review the latest revision before saving')
    value = old.value
    if payload.action == 'edit':
        try:
            value = validated_value(fact.name, payload.value)
        except (ValidationError, ValueError) as error:
            raise HTTPException(422, 'Edited value must retain the typed structure for this finding') from error
    lock_case(db, encounter, encounter.revision)
    new_revision = payload.expected_revision + 1
    updated = db.execute(update(ClinicalFact).where(ClinicalFact.id == fact.id, ClinicalFact.current_revision == payload.expected_revision)
                         .values(current_revision=new_revision).execution_options(synchronize_session=False)).rowcount
    if updated != 1:
        db.rollback()
        raise HTTPException(409, 'This finding was changed by another request')
    approved = payload.action != 'reject'
    edited = payload.action == 'edit'
    revision = FactRevision(id=uid(), fact_id=fact.id, revision=new_revision, value=value,
                            confirmation_state='confirmed' if approved else 'rejected',
                            source_type='clinician_manual' if edited else old.source_type,
                            source_ref=old.source_ref, source_quote=None if edited else old.source_quote,
                            source_locator={**old.source_locator, 'revised_from': old.id} if edited else old.source_locator,
                            confirmed_by=user.id if approved else None, confirmed_at=utcnow() if approved else None,
                            authored_by=user.id, action=payload.action)
    db.add(revision)
    timeline(db, user, encounter.id, f'fact.{payload.action}', fact.id, {'revision': new_revision, 'name': fact.name})
    finish(db)
    result = fact_out(fact, revision)
    result['case_revision'] = encounter.revision
    return result


@router.get('/facts/{fact_id}/history')
def fact_history(fact_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    fact = owned(db, ClinicalFact, fact_id, user)
    return [fact_out(fact, r) for r in db.scalars(select(FactRevision).where(FactRevision.fact_id == fact.id).order_by(FactRevision.revision))]


@router.post('/encounters/{encounter_id}/review', dependencies=MUTATION)
async def clinical_review(encounter_id: str, payload: ReviewRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = owned(db, Encounter, encounter_id, user)
    expected = encounter.revision
    facts = case_facts(db, encounter)
    wounds = [wound_out(w) for w in db.scalars(select(Wound).where(Wound.patient_id == encounter.patient_id, Wound.workspace_id == user.workspace_id))
              if w.id in {f['wound_id'] for f in facts} or w.id == encounter.wound_id]
    result = deterministic_review(facts, wounds)
    if payload.use_ai:
        check_rate(f'clinical-review:{user.id}', limit=20)
        try:
            ai = await providers.clinical_review([f for f in facts if f['confirmation_state'] != 'rejected'], payload.mode,
                                                 user=user, db=db, encounter_id=encounter_id)
            result['considerations'] = ai.get('considerations', [])
            result['routing'] = ai.get('routing', {})
            result['effort_level'] = ai.get('effort_level', '')
            result['usage'] = ai.get('usage', {})
            result['warnings'].extend(str(w) for w in ai.get('warnings', []))
        except HTTPException as error:
            if error.status_code not in {402, 422, 429, 502, 503, 504}:
                raise
            result['warnings'].append('AI clinical review was unavailable. Deterministic completeness checks remain available.')
    db.refresh(encounter)
    if encounter.revision != expected:
        raise HTTPException(409, 'The case changed during review; request a fresh review')
    result['case_revision'] = expected
    row = ClinicalReview(id=uid(), workspace_id=user.workspace_id, encounter_id=encounter_id,
                         case_revision=expected, result=result, created_by=user.id)
    db.add(row)
    timeline(db, user, encounter_id, 'clinical.review', row.id, {'case_revision': expected, 'mode': payload.mode, 'use_ai': payload.use_ai})
    finish(db)
    return result


@router.post('/encounters/{encounter_id}/assistant', dependencies=MUTATION)
async def contextual_assistant(encounter_id: str, payload: AssistantRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    encounter = owned(db, Encounter, encounter_id, user)
    expected = encounter.revision
    facts = [fact for fact in case_facts(db, encounter) if fact['confirmation_state'] != 'rejected']
    check_rate(f'clinical-assistant:{user.id}', limit=20)
    result = await providers.clinical_assistant(facts, payload.question, user=user, db=db, encounter_id=encounter_id)
    db.refresh(encounter)
    if encounter.revision != expected:
        raise HTTPException(409, 'The case changed while the assistant was responding; ask again against the current evidence')
    considerations = result['considerations']
    timeline(db, user, encounter_id, 'clinical.assistant', encounter_id,
             {'case_revision': expected, 'question_hash': content_hash(payload.question), 'fact_count': len(facts)})
    finish(db)
    return {'answer': result['answer'], 'considerations': considerations,
            'case_revision': expected, 'usage': result.get('usage', {}),
            'routing': result.get('routing', {}), 'effort_level': result.get('effort_level', '')}


@router.post('/encounters/{encounter_id}/draft', dependencies=MUTATION)
async def draft_document(encounter_id: str, payload: DraftRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    from .quick_workflows import quick_report, QuickReport
    return await quick_report(encounter_id, QuickReport(template=payload.template, custom_template=payload.custom_template, use_ai=payload.use_ai), user, db)


@router.post('/encounters/{encounter_id}/documents', status_code=201, dependencies=MUTATION)
def save_document(encounter_id: str, payload: DocumentCreate, request: Request,
                  user: User = Depends(current_user), db: Session = Depends(get_db)):
    from . import diagnostics
    diagnostics.task('document_save')
    encounter = owned(db, Encounter, encounter_id, user)
    key, payload_hash = request_key(request), digest(payload.model_dump(mode='json'))
    previous = previous_request(db, ClinicalDocument, user, encounter_id, key, payload_hash)
    if previous:
        diagnostics.saved_document(previous.id,request.headers.get('X-Report-Generation'))
        return document_out(db, previous)
    signed = db.scalars(select(ClinicalDocument).join(DocumentSignature, DocumentSignature.document_id == ClinicalDocument.id)
                        .where(ClinicalDocument.encounter_id == encounter_id).order_by(ClinicalDocument.version.desc())).first()
    if payload.amends_id:
        parent = owned(db, ClinicalDocument, payload.amends_id, user)
        if parent.encounter_id != encounter_id or not db.scalar(select(DocumentSignature).where(DocumentSignature.document_id == parent.id)):
            raise HTTPException(422, 'An amendment must refer to a signed document from this encounter')
        if signed and signed.id != parent.id:
            raise HTTPException(409, 'Amend the latest signed document to preserve a linear amendment history')
    elif signed:
        raise HTTPException(409, 'A signed document exists. Create an explicit amendment with a reason')
    revision = lock_case(db, encounter, payload.expected_revision)
    version = (db.scalar(select(func.max(ClinicalDocument.version)).where(ClinicalDocument.encounter_id == encounter_id)) or 0) + 1
    approved, _ = approved_projection(case_facts(db, encounter))
    row = ClinicalDocument(id=uid(), workspace_id=user.workspace_id, encounter_id=encounter_id, text=payload.text,
                           template=payload.template, custom_template=payload.custom_template.model_dump() if payload.custom_template else None,
                           content_hash=content_hash(payload.text), version=version, case_revision=revision,
                           fact_snapshot=approved, amends_id=payload.amends_id, amendment_reason=payload.reason,
                           request_key=key, request_hash=payload_hash, created_by=user.id)
    db.add(row)
    timeline(db, user, encounter_id, 'document.amendment_save' if payload.amends_id else 'document.save', row.id,
             {'version': version, 'content_hash': row.content_hash, 'case_revision': revision, 'amends_id': payload.amends_id})
    with diagnostics.measure('database_save'):finish(db)
    diagnostics.saved_document(row.id,request.headers.get('X-Report-Generation'))
    return document_out(db, row)


@router.post('/documents/{document_id}/sign', dependencies=MUTATION)
def sign_document(document_id: str, payload: SignDocument, user: User = Depends(current_user), db: Session = Depends(get_db)):
    document = owned(db, ClinicalDocument, document_id, user)
    encounter = owned(db, Encounter, document.encounter_id, user)
    if document.content_hash != payload.content_hash or content_hash(document.text) != payload.content_hash:
        raise HTTPException(409, 'Document content does not match the text you reviewed')
    existing = db.scalar(select(DocumentSignature).where(DocumentSignature.document_id == document_id))
    if existing:
        return document_out(db, document)
    if document.case_revision != payload.expected_case_revision or encounter.revision != payload.expected_case_revision:
        raise HTTPException(409, 'The case changed after this draft was saved. Save and review a new version before signing')
    if conflicts_for(case_facts(db, encounter)):
        raise HTTPException(409, 'Resolve conflicting confirmed findings before signing')
    revision = lock_case(db, encounter, payload.expected_case_revision, status='signed')
    signature = DocumentSignature(id=uid(), document_id=document.id, content_hash=document.content_hash,
                                  signed_case_revision=revision, signed_by=user.id)
    db.add(signature)
    timeline(db, user, encounter.id, 'document.sign', document.id,
             {'version': document.version, 'content_hash': document.content_hash, 'case_revision': revision, 'amends_id': document.amends_id})
    finish(db)
    return document_out(db, document)


@router.get('/encounters/{encounter_id}/audit')
def encounter_audit(encounter_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    owned(db, Encounter, encounter_id, user)
    return [{'id': e.id, 'action': e.action, 'resource_id': e.resource_id, 'user_id': e.user_id,
             'detail': e.detail, 'created_at': iso(e.created_at)}
            for e in db.scalars(select(TimelineEvent).where(TimelineEvent.encounter_id == encounter_id,
                                                            TimelineEvent.workspace_id == user.workspace_id)
                                .order_by(TimelineEvent.created_at, TimelineEvent.id))]
