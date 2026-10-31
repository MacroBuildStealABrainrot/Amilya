"""Owner-approved features added to the narrow live repair file; synthetic data, intercepted provider calls only."""
import asyncio
import importlib.util
import io
import json
import sys
from pathlib import Path

import pytest
from fastapi import HTTPException
from PIL import Image

from server import providers
from test_providers import gateway, completion


@pytest.fixture
def live(gateway, monkeypatch):
    path = Path(__file__).resolve().parents[2] / 'repairs/live-00021-lrx/providers.py'
    spec = importlib.util.spec_from_file_location('server.live_repair_features', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setattr(module, 'PROMPTS', providers.PROMPTS)
    monkeypatch.setattr(module, 'settings', gateway.config)
    monkeypatch.setattr(module, 'SessionLocal', gateway.sessions)
    monkeypatch.setattr(module, 'check_rate', lambda *a, **k: None)
    return module


def photo():
    out = io.BytesIO()
    Image.new('RGB', (40, 30), (120, 60, 50)).save(out, format='PNG')
    return out.getvalue()


def assess(module, gateway):
    return asyncio.run(module.analyze_media(photo(), 'image/png', 'photo', user=gateway.user, encounter_id='synthetic-photo'))


def wound_result():
    return completion({'facts': [{'name': 'wound_tissue', 'value': {'text': 'Wound bed with adherent slough.', 'slough': 60.0}, 'quote': ''}],
                       'warnings': [], 'quality': {'status': 'sufficient', 'reason': 'none'}})


def refused(error_type):
    return (200, {'error': {'code': 502, 'metadata': {'error_type': error_type}}})


def test_photo_uses_claude_with_a_careful_look_and_room_to_answer(live, gateway):
    gateway.http.responses = [wound_result()]
    result = assess(live, gateway)
    payload = gateway.http.calls[0]['json']
    assert payload['model'] == 'anthropic/claude-sonnet-5.5'
    assert payload['reasoning']['effort'] == 'high'
    assert payload['max_tokens'] >= 12000
    assert payload['provider'] == {'zdr': True, 'data_collection': 'deny', 'require_parameters': True}
    # Tissue is charted in the sentence only, without duplicate numbers.
    assert result['facts'][0]['value'] == {'text': 'Wound bed with adherent slough.'}


@pytest.mark.parametrize('error_type', ['provider_unavailable', 'content_policy_violation', 'refusal'])
def test_refused_photo_gets_one_backup_attempt(live, gateway, error_type):
    gateway.http.responses = [refused(error_type), wound_result()]
    assess(live, gateway)
    assert [c['json']['model'] for c in gateway.http.calls] == ['anthropic/claude-sonnet-5.5', 'openai/gpt-5.6-sol']


def test_no_backup_for_budget_problems(live, gateway):
    gateway.http.responses = [(402, {'error': {'code': 402}})]
    with pytest.raises(HTTPException):
        assess(live, gateway)
    assert len(gateway.http.calls) == 1


@pytest.mark.parametrize('template,expected', [('nursing', True), ('soap', True), ('custom', False)])
def test_standard_notes_use_haiku(live, template, expected):
    content = json.dumps({'template': template})
    assert live.simple_note_template(content) is expected
    assert 'anthropic/claude-haiku-5.5' in live.MODEL_PRICES and 'anthropic/claude-sonnet-5.5' in live.MODEL_PRICES


def test_repair_photo_instructions_match_the_reviewed_file():
    root = Path(__file__).resolve().parents[2]
    repair = (root / 'repairs/live-00021-lrx/40_wound_image_assessment.system.md').read_bytes()
    assert repair == (root / 'backend/server/prompts/40_wound_image_assessment.system.md').read_bytes()
    assert repair == (root / 'Amilya/repairs/live-00021-lrx/40_wound_image_assessment.system.md').read_bytes()


def test_live_consultant_answers_with_claude_and_the_owner_voice(live, gateway):
    answer = 'Check perfusion before any sharp debridement. If adequate, start autolytic debridement with hydrogel.'
    gateway.http.responses = [completion({'answer': answer, 'considerations': []})]
    context = {'case_facts': [], 'untrusted_sources': [], 'rejected_claims_do_not_reinstate': []}
    result = asyncio.run(live.wound_consultant(context, 'How should I treat this heel wound?', [],
                                               user=gateway.user, encounter_id='synthetic-consult'))
    payload = gateway.http.calls[0]['json']
    assert result['answer'] == answer
    assert payload['model'] == 'anthropic/claude-sonnet-5.5'
    assert payload['max_tokens'] >= 5000
    system = payload['messages'][0]['content']
    assert 'experienced wound care consultant' in system and 'Use conditions instead of refusing' in system


def test_live_consultant_instructions_ship_identically():
    root = Path(__file__).resolve().parents[2]
    name = '91_wound_consultant.system.md'
    shipped = (root / 'repairs/live-00021-lrx' / name).read_bytes()
    assert shipped == (root / 'Amilya/repairs/live-00021-lrx' / name).read_bytes()
    assert shipped == (providers.PROMPTS / name).read_bytes()
    assert f'COPY {name} /app/server/prompts/{name}' in (root / 'repairs/live-00021-lrx/Dockerfile').read_text()


def live_quick_workflows():
    path = Path(__file__).resolve().parents[2] / 'repairs/live-00021-lrx/quick_workflows.py'
    spec = importlib.util.spec_from_file_location('server.live_quick_workflows', path)
    module = importlib.util.module_from_spec(spec)
    # Registered like a normal import so pydantic can resolve the file's postponed type names.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_live_report_accepts_a_consultation_and_labels_who_said_what():
    module = live_quick_workflows()
    report = module.QuickReport.model_validate({'template': 'nursing', 'consultation': [
        {'role': 'user', 'content': 'Synthetic: pedal pulses palpable.'},
        {'role': 'assistant', 'content': 'Synthetic: consider toe pressures.'}]})
    context = module.consultation_context(report.consultation)
    assert [t['speaker'] for t in context['turns']] == ['clinician_message', 'ai_consultant_suggestion']
    assert 'Consultant suggestions (not confirmed)' in context['how_to_use']
    assert module.QuickReport.model_validate({'template': 'nursing'}).consultation == []
    with pytest.raises(Exception):
        module.QuickReport.model_validate({'template': 'nursing', 'consultation': [{'role': 'user', 'content': 'x' * 10000}] * 5})


def test_live_report_instructions_keep_the_live_rules_and_add_the_charting_voice():
    root = Path(__file__).resolve().parents[2] / 'repairs/live-00021-lrx'
    text = (root / '81_quick_documentation.system.md').read_text(encoding='utf-8')
    assert text.startswith('Generate the selected document from the original visit sources')
    assert 'Consultant suggestions (not confirmed)' in text and 'CHARTING VOICE' in text
    assert 'consultation' in (root / '86_quick_documentation_qa.system.md').read_text(encoding='utf-8')


@pytest.mark.parametrize('task,result,model,effort', [
    ('quick_draft', {'text': 'Synthetic note.', 'status': 'ready', 'ui_alerts': []}, 'anthropic/claude-sonnet-5.5', 'medium'),
    ('quick_qa', {'passed': True, 'discrepancies': []}, 'anthropic/claude-haiku-5.5', 'medium'),
])
def test_live_standard_notes_are_written_by_sonnet_and_checked_by_haiku(live, gateway, task, result, model, effort):
    gateway.http.responses = [completion(result)]
    content = json.dumps({'template': 'soap', 'case_facts': [], 'untrusted_sources': [
        {'id': 's1', 'kind': 'typed', 'text': 'Synthetic: purulent drainage today.'}]})
    asyncio.run(live.routed(task, content, user=gateway.user, encounter_id='synthetic-fast-note'))
    payload = gateway.http.calls[0]['json']
    assert payload['model'] == model
    assert payload['reasoning']['effort'] == effort


def test_live_extraction_keeps_the_date_when_a_time_is_attached(live, gateway):
    source = 'Synthetic: dressing changed this evening.'
    gateway.http.responses = [completion({'facts': [{'name': 'history', 'value': {'text': 'Dressing changed.', 'observed_on': '2026-10-30T18:00:00'},
                                                    'quote': 'dressing changed'}], 'warnings': []})]
    result = asyncio.run(live.extract_text(source, 'typed', user=gateway.user, encounter_id='synthetic-extract'))
    assert [str(f['value'].get('observed_on')) for f in result['facts']] == ['2026-10-30']
