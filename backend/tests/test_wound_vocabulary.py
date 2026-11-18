"""Owner-supplied wound wording list reaches report writing and checking, below the owner's earlier rules."""
from types import SimpleNamespace
import pytest
from server import documentation_layers as layers, providers


@pytest.mark.parametrize('task', ['quick_draft', 'quick_qa', 'quick_repair'])
@pytest.mark.parametrize('profession', ['RPN', 'NP', 'MD', ''])
def test_wound_wording_list_reaches_generation_and_checking(task, profession):
    token = layers.select(SimpleNamespace(profile={'profession': profession}), 'nursing')
    try:
        prompt = providers.prompt(task)
        assert 'wound_vocabulary_v1' in prompt
        assert 'inner ankle bone = medial malleolus' in prompt
        # The list comes after the owner's charting rules, and says those rules win.
        assert prompt.index('charting_rules_v1') < prompt.index('wound_vocabulary_v1')
        assert 'every rule above this list wins over it' in prompt
    finally:
        layers.selection.reset(token)


def test_wording_list_keeps_the_owners_earlier_choices():
    text = (layers.ROOT / 'wound_vocabulary_v1.md').read_text(encoding='utf-8')
    assert 'hallux' not in text                      # "great toe", as already agreed
    assert 'big toe = great toe' in text
    assert 'mild erythema' not in text and 'copious' not in text   # severity and amount stay the speaker's words
    assert 'slough stays slough' in text and 'eschar stays eschar' in text
    assert 'drainage noted' not in text and 'infection noted' not in text


def test_consultant_does_not_receive_the_wording_list():
    assert 'wound_vocabulary_v1' not in providers.prompt('consult')
