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
    assert 'wound bed: granulation, slough' in text and 'periwound: erythema, maceration' in text
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


def test_photo_ai_suggestions_never_reach_the_charting_note_sources(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']

    async def analyze(data, mime, kind, **kwargs):
        return {'facts': [{'name': 'wound_tissue', 'value': {'text': 'Moist granulation tissue.'}, 'quote': ''},
                          {'name': 'photo_interpretation', 'value': {'text': 'For clinician review: possible eschar.'}, 'quote': ''},
                          {'name': 'photo_next_steps', 'value': {'text': 'Check: probe depth.'}, 'quote': ''}], 'warnings': []}
    monkeypatch.setattr(providers, 'analyze_media', analyze)
    assert upload(api, visit, 'advisory', clinician_findings=json.dumps({'wound_bed': ['granulation'], 'drainage_type': 'serosanguineous', 'drainage_amount': 'small'})).status_code == 201
    with api.sessions() as db:
        context = quick_workflows.source_context(db, db.get(Encounter, visit))
    names = {fact['name'] for fact in context['case_facts']}
    assert 'photo_interpretation' not in names and 'photo_next_steps' not in names
    photo_text = ' '.join(s['text'] or '' for s in context['untrusted_sources'] if s['kind'] == 'photo')
    # The clinician's selections reach the note exactly, as the clinician's own findings.
    assert 'wound bed: granulation' in photo_text and 'drainage: small serosanguineous' in photo_text


def test_rules_give_the_clinician_priority_flag_differences_and_keep_uncertainty():
    from types import SimpleNamespace
    from server import documentation_layers as layers
    token = layers.select(SimpleNamespace(profile={'profession': 'RPN'}), 'nursing')
    try:
        note_rules = providers.prompt('quick_draft')
    finally:
        layers.selection.reset(token)
    assert "clinician's own bedside findings" in note_rules and 'take priority' in note_rules
    assert 'Please confirm wound-bed tissue. Your selection and the image assessment may differ.' in note_rules
    assert 'stays out of the note' in note_rules and 'can coexist' in note_rules
    assert 'recorded as' in note_rules and 'upper-left' in note_rules
    assert 'not a nursing-only app' in note_rules and 'callused edges and a small amount of serosanguineous drainage' in note_rules
    consultant = providers.prompt('consult')
    assert 'never restate it as a fact' in consultant


# The owner's Samsung test of 2026-11-06 (made-up words in the same shape): granulation, callus and small
# serosanguineous picked; the photo assessment described slough, dark crusted tissue and no moist drainage.
SAMSUNG_CASE = [{'name': 'wound_tissue', 'value': {'text': 'Adherent slough and dark crusted tissue sit at the centre of the crater.'}},
                {'name': 'periwound', 'value': {'text': 'Thickened callus, scaling and yellow-orange discolouration at the margin.'}},
                {'name': 'exudate', 'value': {'text': 'No moist drainage visible on the wound surface.'}},
                {'name': 'photo_interpretation', 'value': {'text': 'The dark crusted tissue may be eschar.'}}]


def test_possible_differences_are_flagged_separately_and_never_decide():
    from server.media_review import selection_differences, BED_DIFFERENCE, DRAINAGE_DIFFERENCE
    picked = ClinicianFindings(wound_bed=['granulation'], surrounding_skin=['callus'], drainage_type='serosanguineous', drainage_amount='small')
    assert selection_differences(picked, SAMSUNG_CASE) == [BED_DIFFERENCE, DRAINAGE_DIFFERENCE]
    assert BED_DIFFERENCE == 'Please confirm wound-bed tissue. Your selection and the image assessment may differ.'
    # Tissues can coexist: picking both granulation and slough is not a difference.
    both = ClinicianFindings(wound_bed=['granulation', 'slough'])
    assert selection_differences(both, [{'name': 'wound_tissue', 'value': {'text': 'Red granulation with a little slough.'}}]) == []
    # "No slough" in the photo is not slough.
    assert selection_differences(ClinicianFindings(wound_bed=['granulation']), [{'name': 'wound_tissue', 'value': {'text': 'Granulation base, no slough or eschar.'}}]) == []
    # Blank fields create nothing; interpretation text is never compared.
    assert selection_differences(None, SAMSUNG_CASE) == []
    assert selection_differences(ClinicianFindings(length_cm=0.5), SAMSUNG_CASE) == []
    assert selection_differences(ClinicianFindings(wound_bed=['granulation']), SAMSUNG_CASE[3:]) == []


def test_samsung_case_keeps_selections_and_sends_the_confirm_message(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']

    async def analyze(data, mime, kind, **kwargs):
        return {'facts': [dict(f, quote='') for f in SAMSUNG_CASE], 'warnings': []}
    monkeypatch.setattr(providers, 'analyze_media', analyze)
    picks = {'wound_bed': ['granulation'], 'surrounding_skin': ['callus'], 'drainage_type': 'serosanguineous', 'drainage_amount': 'small',
             'length_cm': 0.5, 'width_cm': 0.5, 'depth_cm': 0.3}
    result = upload(api, visit, 'samsung', clinician_note='DFU', clinician_findings=json.dumps(picks))
    assert result.status_code == 201, result.text
    assert result.json()['evidence']['warnings'][0].startswith('Please confirm wound-bed tissue.')
    with api.sessions() as db:
        context = quick_workflows.source_context(db, db.get(Encounter, visit))
    text = ' '.join(s['text'] or '' for s in context['untrusted_sources'] if s['kind'] == 'photo')
    for kept in ('wound bed: granulation', 'periwound: callus', 'drainage: small serosanguineous', 'length 0.5 cm, width 0.5 cm, depth 0.3 cm'):
        assert kept in text
    from server.source_coverage import selection_difference_alerts
    alerts = selection_difference_alerts(context)
    assert [a['message'] for a in alerts] == ['Please confirm wound-bed tissue. Your selection and the image assessment may differ.',
                                             'Please confirm drainage. Your selection and the image assessment may differ.']
    assert all(a['field'] == 'clarify' for a in alerts)
    assert selection_difference_alerts(context, alerts) == []  # never added twice


def test_edges_deeper_structures_and_local_infection_signs():
    picked = ClinicianFindings(wound_bed=['bone', 'tendon', 'hypergranulation'], wound_edges=['unattached', 'rolled', 'undermining', 'tunnelling'], surrounding_skin=['callus'],
                               infection_signs=['warmth', 'odour', 'spreading_erythema'])
    text = picked.as_text()
    assert 'wound bed: bone, tendon, hypergranulation' in text and 'wound edges: unattached, rolled (epibole), undermining, tunnelling' in text and 'periwound: callus' in text
    assert 'local signs of infection: warmth, odour, spreading erythema' in text
    assert 'no local signs of infection' in ClinicianFindings(infection_signs=['none']).as_text()
    assert not ClinicianFindings(wound_edges=['attached']).is_empty()


def test_infection_none_cannot_be_combined_and_unknown_edge_is_refused(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']; capture(monkeypatch)
    assert upload(api, visit, 'mix', clinician_findings=json.dumps({'infection_signs': ['none', 'warmth']})).status_code == 422
    assert upload(api, visit, 'edge', clinician_findings=json.dumps({'wound_edges': ['attached', 'unattached']})).status_code == 422
    assert upload(api, visit, 'edge2', clinician_findings=json.dumps({'wound_edges': ['raised']})).status_code == 422
    ok = upload(api, visit, 'fine', clinician_findings=json.dumps({'wound_edges': ['attached', 'undermining'], 'infection_signs': ['none']}))
    assert ok.status_code == 201, ok.text


def test_healing_percentage_is_calculated_not_guessed():
    shrinking = ClinicianFindings(length_cm=0.5, width_cm=0.5, previous_length_cm=1, previous_width_cm=1, previous_weeks_ago=4)
    assert shrinking.area_change_percent() == 75
    assert 'healing progress: 75% area reduction (area 0.25 cm2, earlier 1 x 1 cm (1 cm2), 4 weeks ago)' in shrinking.as_text()
    growing = ClinicianFindings(length_cm=3, width_cm=2, previous_length_cm=2, previous_width_cm=2.5)
    assert growing.area_change_percent() == -20 and 'area increased by 20%' in growing.as_text()
    assert ClinicianFindings(length_cm=2, width_cm=1).healing_progress() == ''  # optional: nothing earlier, nothing said


def test_healing_progress_needs_both_sizes(quick_api, monkeypatch):
    api = quick_api; visit = start(api, 'photo')['encounter']['id']; capture(monkeypatch)
    only_earlier = {'previous_length_cm': 2, 'previous_width_cm': 1}
    assert upload(api, visit, 'p1', clinician_findings=json.dumps(only_earlier)).status_code == 422
    assert upload(api, visit, 'p2', clinician_findings=json.dumps({'length_cm': 1, 'width_cm': 1, 'previous_length_cm': 2})).status_code == 422
    ok = upload(api, visit, 'p3', clinician_findings=json.dumps({'length_cm': 1, 'width_cm': 1, **only_earlier, 'previous_weeks_ago': 2}))
    assert ok.status_code == 201, ok.text


def test_intact_periwound_against_erythema_or_maceration_is_flagged():
    from server.media_review import selection_differences, PERIWOUND_DIFFERENCE
    facts = [{'name': 'wound_edges', 'value': {'text': 'Irregular macerated wound edges.'}},
             {'name': 'periwound', 'value': {'text': 'Periwound erythema.'}}]
    assert PERIWOUND_DIFFERENCE in selection_differences(ClinicianFindings(surrounding_skin=['intact']), facts)
    calm = [{'name': 'periwound', 'value': {'text': 'Periwound intact, no erythema.'}}]
    assert selection_differences(ClinicianFindings(surrounding_skin=['intact']), calm) == []


def test_pain_score_zero_to_ten_and_infection_judged_from_clinician_entries():
    assert 'pain 0/10' in ClinicianFindings(pain_score=0).as_text()  # 0 is a real answer, never "blank"
    assert 'pain 7/10' in ClinicianFindings(pain_score=7).as_text()
    assert not ClinicianFindings(pain_score=0).is_empty() and ClinicianFindings().is_empty()
    import pytest
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        ClinicianFindings(pain_score=11)
    consultant = providers.prompt('consult')
    assert 'never from the photo appearance alone' in consultant


def test_photo_clinical_review_is_a_senior_wound_consultant_review():
    rules = providers.prompt('image')
    for item in ('senior advanced wound care consultant', 'probe to bone', 'tendon integrity', 'ABI/TBI', '- Urgent review if',
                 'never a repeat of what is visible'):
        assert item.lower() in rules.lower(), item
