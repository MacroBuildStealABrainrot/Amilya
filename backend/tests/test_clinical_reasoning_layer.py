"""Owner-approved clinical reasoning layer and de-identified style examples reach generation AND checking."""
from pathlib import Path
from types import SimpleNamespace
import re
import pytest
from server import documentation_layers as layers, providers

ROOT = Path(layers.__file__).parent / 'prompts' / 'documentation'


@pytest.mark.parametrize('task', ['quick_draft', 'quick_qa', 'quick_repair'])
@pytest.mark.parametrize('report', ['soap', 'nursing', 'physician_update'])
def test_reasoning_layer_and_examples_reach_generation_and_qa(task, report):
    token = layers.select(SimpleNamespace(profile={'profession': 'RN'}), report)
    try:
        prompt = providers.prompt(task)
        assert 'clinical_reasoning_v1' in prompt
        assert 'never as an order' in prompt
        assert 'invented composite' in prompt
    finally:
        layers.selection.reset(token)


def test_examples_hold_no_names_or_dates():
    text = ' '.join(p.read_text() for p in (ROOT / 'examples').glob('*.md'))
    assert not re.search(r'\b(Dr\.|Mr\.|Mrs\.|Ms\.)\s*[A-Z]', text)
    assert not re.search(r'\b(January|February|March|April|May|June|July|August|September|October|November|December)\b', text)
    assert not re.search(r'\b\d{1,3}-year-old\b', text)


@pytest.mark.parametrize('typed,role', [('RPN', 'NURSE'), ('Registered Practical Nurse', 'NURSE'), ('wound care nurse', 'NURSE'),
    ('Nurse Practitioner', 'NURSE_PRACTITIONER'), ('Family doctor', 'PHYSICIAN'), ('Vascular surgeon', 'PHYSICIAN'),
    ('Dermatologist', 'PHYSICIAN'), ('Psychiatrist', 'PHYSICIAN'), ('MD', 'PHYSICIAN'), ('GP', 'PHYSICIAN'),
    ('Podiatrist', 'OTHER_CLINICIAN'), ('Psychologist', 'OTHER_CLINICIAN'), ('Physiotherapist', 'OTHER_CLINICIAN'), ('', 'OTHER_CLINICIAN')])
def test_free_typed_professions_are_recognised(typed, role):
    assert layers.clinician_role(SimpleNamespace(profile={'profession': typed})) == role


def test_charting_rules_reach_generation_and_qa():
    token = layers.select(SimpleNamespace(profile={'profession': 'MD'}), 'soap')
    try:
        for task in ('quick_draft', 'quick_qa'):
            prompt = providers.prompt(task)
            assert 'charting_rules_v1' in prompt and 'lungs clear' in prompt and 'EMS activated' in prompt
    finally:
        layers.selection.reset(token)


@pytest.mark.parametrize('task', ['quick_draft', 'quick_qa'])
def test_connected_writing_and_unclear_terms_rules_reach_generation_and_qa(task):
    token = layers.select(SimpleNamespace(profile={'profession': 'RPN'}), 'nursing')
    try:
        prompt = providers.prompt(task)
        assert 'genuinely broken writing' in prompt or 'genuinely fragmented writing' in prompt
        assert 'cell cancer' in prompt
        if task == 'quick_draft':
            assert 'Shorter is not better' in prompt
            assert 'seen daily because client cannot change her own dressing' in prompt
    finally:
        layers.selection.reset(token)


def test_nursing_examples_lead_with_a_connected_complex_note():
    text = (ROOT / 'examples' / 'nursing_v1.md').read_text()
    assert text.index('Complex or changing visit') < text.index('truly routine visit')


@pytest.mark.parametrize('profession', ['RPN', 'MD'])
def test_handoff_rules_reach_every_profession(profession):
    token = layers.select(SimpleNamespace(profile={'profession': profession}), 'nursing')
    try:
        prompt = providers.prompt('quick_draft')
        assert 'Client denies fever, chills or pain' in prompt and 'never reproduce the questions' in prompt
        assert 'Piptaza or Piptazo = piperacillin/tazobactam' in prompt and 'SP catheter = suprapubic catheter' in prompt
        assert 'HISTORY, PHYSICAL EXAMINATION, ASSESSMENT and OUTCOME / PLAN' in prompt and 'Otherwise keep the chosen format' in prompt
    finally:
        layers.selection.reset(token)
