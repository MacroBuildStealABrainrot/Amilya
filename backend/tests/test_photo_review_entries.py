"""Photo review screen: optional note and wound details travel with the photo (all data invented)."""
import json
from io import BytesIO

from PIL import Image

from server import providers, quick_workflows
from server.clinical_models import Encounter, Evidence
from server.media_review import ClinicianFindings
from test_clinical import clinical_api  # noqa: F401  (fixture)
from test_quick_workflows import quick_api, start  # noqa: F401  (fixture)


def image_bytes(colour=(100, 20, 20)):
    stream = BytesIO()
    Image.new('RGB', (32, 32), colour).save(stream, format='PNG')
    return stream.getvalue()


def capture(monkeypatch):
    calls = []

    async def analyze(data, mime, kind, **kwargs):
        calls.append(kwargs)
        return {'facts': [{'name': 'wound_edges', 'value': {'text': 'Irregular edges.'}, 'quote': ''}], 'warnings': []}
    monkeypatch.setattr(providers, 'analyze_media', analyze)
    return calls


def upload(api, visit, key, **fields):
    data = {'kind': 'photo', **fields}
    return api.client.post(f'/api/v1/encounters/{visit}/upload', files={'file': ('photo.png', image_bytes(), 'image/png')},
                           data=data, headers={'Idempotency-Key': key})


FINDINGS = {'wound_bed': ['granulation', 'slough'], 'surrounding_skin': ['erythema', 'maceration'],
            'drainage_type': 'serous', 'drainage_amount': 'small', 'length_cm': 3.2, 'width_cm': 2.1}


def test_photo_alone_with_every_optional_field_skipped(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']; calls = capture(monkeypatch)
    result = upload(api, visit, 'alone')
    assert result.status_code == 201, result.text
    assert calls[0]['clinician_note'] == '' and calls[0]['clinician_findings'] == ''
    with api.sessions() as db:
        row = db.get(Evidence, result.json()['evidence']['id'])
        assert 'clinician_entries' not in row.metadata_json


def test_empty_details_object_counts_as_skipped(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']; calls = capture(monkeypatch)
    assert upload(api, visit, 'empty', clinician_findings=json.dumps({'wound_bed': [], 'length_cm': None})).status_code == 201
    assert calls[0]['clinician_findings'] == ''


def test_photo_with_note_and_selected_findings_goes_in_the_same_request(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']; calls = capture(monkeypatch)
    result = upload(api, visit, 'full', clinician_note='Left heel, 3 weeks, warm on assessment.', clinician_findings=json.dumps(FINDINGS))
    assert result.status_code == 201, result.text
    assert len(calls) == 1
    assert calls[0]['clinician_note'] == 'Left heel, 3 weeks, warm on assessment.'
    text = calls[0]['clinician_findings']
    assert 'wound bed: granulation, slough' in text and 'surrounding skin: erythema, maceration' in text
    assert 'drainage: small serous' in text and 'measured length 3.2 cm, width 2.1 cm' in text
    with api.sessions() as db:
        context = quick_workflows.source_context(db, db.get(Encounter, visit))
    photo_sources = [s for s in context['untrusted_sources'] if s['kind'] == 'photo']
    assert 'Clinician note sent with this photo: Left heel, 3 weeks' in photo_sources[0]['text']
    assert 'Clinician-recorded findings on assessment' in photo_sources[0]['text']


def test_blank_measurements_stay_unknown_and_zero_is_refused(quick_api, monkeypatch):
    assert 'depth' not in ClinicianFindings(length_cm=3.2).as_text()
    assert ClinicianFindings().as_text() == '' and ClinicianFindings().is_empty()
    api = quick_api; visit = start(api, 'photo')['encounter']['id']; capture(monkeypatch)
    assert upload(api, visit, 'zero', clinician_findings=json.dumps({'depth_cm': 0})).status_code == 422
    assert upload(api, visit, 'bad', clinician_findings=json.dumps({'wound_bed': ['osteomyelitis']})).status_code == 422
    assert upload(api, visit, 'extra', clinician_findings=json.dumps({'diagnosis': 'cellulitis'})).status_code == 422


def test_retry_keeps_entries_and_never_charges_twice(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']; calls = capture(monkeypatch)
    fields = {'clinician_note': 'Sacrum, client reports pain 4/10.', 'clinician_findings': json.dumps(FINDINGS)}
    first = upload(api, visit, 'retry-1', **fields)
    again = upload(api, visit, 'retry-1', **fields)
    assert first.status_code == 201 and again.status_code in (200, 201)
    assert again.json()['evidence']['id'] == first.json()['evidence']['id']
    assert len(calls) == 1  # the retry replayed the saved assessment: no second AI request, no second charge
    changed = upload(api, visit, 'retry-1', clinician_note='Different words.', clinician_findings=json.dumps(FINDINGS))
    assert changed.status_code == 409 and len(calls) == 1


def test_two_visits_keep_their_photos_and_entries_apart(quick_api, monkeypatch):
    api = quick_api; capture(monkeypatch)
    first = start(api, 'photo')['encounter']['id']; second = start(api, 'photo')['encounter']['id']
    assert upload(api, first, 'a', clinician_note='Visit A: right ankle.').status_code == 201
    assert upload(api, second, 'b', clinician_note='Visit B: left heel.').status_code == 201
    with api.sessions() as db:
        a = json.dumps(quick_workflows.source_context(db, db.get(Encounter, first)))
        b = json.dumps(quick_workflows.source_context(db, db.get(Encounter, second)))
    assert 'Visit A' in a and 'Visit B' not in a
    assert 'Visit B' in b and 'Visit A' not in b


def test_findings_reach_the_photo_model_marked_as_the_clinicians(monkeypatch):
    from test_photo_recovery import photo
    sent = []

    async def routed(task, content, *args, **kwargs):
        sent.append(content)
        return {'facts': [], 'warnings': [], 'quality': {'status': 'sufficient', 'reason': 'none'}}
    monkeypatch.setattr(providers, 'routed', routed)
    import asyncio
    from types import SimpleNamespace
    asyncio.run(providers.analyze_media(photo(), 'image/png', 'photo', user=SimpleNamespace(id='u', workspace_id='w'),
        encounter_id='e', clinician_note='', clinician_findings='wound bed: slough'))
    text = json.dumps(sent[0])
    assert 'CLINICIAN_RECORDED_FINDINGS' in text and 'Conflict:' in text and 'wound bed: slough' in text
