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
        assert 'clinician facts only' in prompt
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


PROMPTS = Path(providers.PROMPTS)


def _read(name):
    return (PROMPTS / name).read_text(encoding='utf-8')


def test_chart_layer_keeps_ai_inference_out_of_the_chart():
    layer = (ROOT / 'clinical_reasoning_v1.md').read_text(encoding='utf-8')
    assert 'clinician facts only' in layer
    assert 'overrides' not in layer.lower()
    assert 'cannot be excluded given' not in layer          # the old example sentence is gone
    assert 'ONE short reasoning sentence' not in layer
    assert 'not an unsupported addition' not in layer
    assert 'explicitly adopts' in layer


def test_consultant_suggestions_never_go_inside_the_saved_report():
    from server import quick_workflows
    how = quick_workflows.consultation_context([])['how_to_use']
    assert 'Consultant suggestions (not confirmed)' not in how
    assert 'not include them in the report at all' in how
    assert 'Consultant suggestions (not confirmed)' not in _read('81_quick_documentation.system.md')
    qa = _read('86_quick_documentation_qa.system.md')
    assert 'must not appear anywhere in the report' in qa
    assert 'is allowed and is not a defect' not in qa


def test_pain_zero_is_kept_and_infection_is_kept_when_recorded():
    p40 = _read('40_wound_image_assessment.system.md')
    assert 'only when it is above 0' not in p40 and 'pain only if above 0' not in p40
    assert 'including 0' in p40 and 'Pain 0/10.' in p40
    assert 'never write "infection" or "infected" in the note' not in p40
    assert 'The word infection does not appear in the note.' not in p40
    assert 'keep it in their own words and certainty' in p40


def test_consultant_prompt_role_infection_and_headings():
    p91 = _read('91_wound_consultant.system.md')
    assert 'as the nurse speaking' not in p91
    assert 'never assume the writer is a nurse' in p91
    assert 'all four sections' not in p91 and 'all five sections' in p91
    assert 'even when local wound signs are absent' in p91
    assert 'not assessed' in p91


def test_generated_report_is_not_treated_as_clinician_information():
    from server import quick_workflows as qw
    ctx = qw.current_report_context(None, None, 'Dark crust seen on photo.', 'ai_draft_unreviewed')
    assert 'clinician_current_report' not in ctx
    assert 'not reviewed or adopted' in ctx['ai_draft_report_unreviewed']['version']
    ctx = qw.current_report_context(None, None, 'Small drainage.')
    assert 'clinician_current_report' not in ctx and 'ai_draft_report_unreviewed' in ctx
    assert 'ai_draft_report_unreviewed' in _read('91_wound_consultant.system.md')


def test_report_origin_never_substitutes_for_explicit_adoption():
    from server import quick_workflows as qw
    for origin in (None, 'clinician', 'ai_draft_unreviewed'):
        ctx = qw.current_report_context(None, None, 'Synthetic draft: dark crust.', origin)
        assert 'clinician_current_report' not in ctx
        assert 'ai_draft_report_unreviewed' in ctx
    class DB:
        def scalar(self, query):
            return SimpleNamespace(text='Synthetic saved draft.', version=1)
    encounter = SimpleNamespace(workspace_id='fake-workspace', id='fake-encounter')
    assert 'ai_draft_report_unreviewed' in qw.current_report_context(DB(), encounter, None)
    class EmptyDB:
        def scalar(self, query):
            return SimpleNamespace(text='   ', version=1)
    assert qw.current_report_context(EmptyDB(), encounter, None) == {}


def test_checkers_reject_unstated_assessment_and_monitoring():
    assert 'never reject a report for it' not in _read('86_quick_documentation_qa.system.md')
    marker = _read('95_evaluation_marker.system.md')
    assert 'even when those signs were not mentioned' not in marker
    assert 'not supplied or explicitly adopted by the clinician' in marker
