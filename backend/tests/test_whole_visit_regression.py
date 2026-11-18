"""Regression: a long recorded visit must reach the model whole, and the instructions for every report type must keep
what the client reported, not only the dressing. Contract test with a provider double; it is NOT evidence that the live
model writes a complete note. That is checked by running the same text through the app."""
import asyncio, json
from pathlib import Path
from types import SimpleNamespace
import pytest
from server import documentation_layers as layers, providers
from test_providers import gateway, completion

TRANSCRIPT = (Path(__file__).parent / 'fixtures/whole_visit_synthetic.txt').read_text()
MUST_REACH_MODEL = ['burning', 'numbness', 'Pins and needles', 'neuropathic', 'good pulse', 'itchy', 'no redness',
                    'doctor', 'Coban 2', 'iodine', 'last four times']
FORMATS = ['nursing', 'soap', 'sbar', 'physician_update']


@pytest.mark.parametrize('template', FORMATS)
def test_whole_visit_text_reaches_the_model_unchanged(gateway, template):
    gateway.user.profile = {'profession': 'PHYSICIAN' if template == 'physician_update' else 'NURSE'}
    gateway.http.responses = [completion({'text': 'Synthetic note.', 'status': 'ready', 'ui_alerts': []}),
                              completion({'passed': True, 'discrepancies': []})]
    context = {'case_facts': [], 'untrusted_sources': [{'kind': 'audio', 'text': TRANSCRIPT}], 'rejected_claims_do_not_reinstate': []}
    asyncio.run(providers.quick_documentation(context, template, 'concise', user=gateway.user, encounter_id='whole-visit'))
    sent = json.loads(gateway.http.calls[0]['json']['messages'][1]['content'])
    assert sent['untrusted_sources'][0]['text'] == TRANSCRIPT
    for phrase in MUST_REACH_MODEL:
        assert phrase in sent['untrusted_sources'][0]['text']
    assert len(TRANSCRIPT) < 40000


@pytest.mark.parametrize('template', FORMATS)
def test_every_report_type_keeps_client_reported_symptoms(template):
    profile = {'profession': 'PHYSICIAN' if template == 'physician_update' else 'NURSE'}
    token = layers.select(SimpleNamespace(profile=profile), template)
    try:
        prompt = providers.prompt('quick_draft')
    finally:
        layers.selection.reset(token)
    assert 'Whole-visit conversations' in prompt
    assert 'never means leaving out a symptom' in prompt
    assert 'never add a cause' in prompt.lower()
    assert 'television' in prompt
