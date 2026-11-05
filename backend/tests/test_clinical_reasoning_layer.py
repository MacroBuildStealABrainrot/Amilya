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
