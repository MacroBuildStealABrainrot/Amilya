"""Regression: the AI kept adding 'reinforced' and stock openers the nurse asked us to avoid."""
from server.documentation import avoid_unrequested_wording, clean_generated_layout

SRC = {'untrusted_sources': [{'text': 'Told him to keep the leg up. Wound cleansed with NS. Dressed with Mepilex.'}]}


def test_ai_added_reinforced_education_is_replaced_with_plain_wording():
    assert avoid_unrequested_wording('Education reinforced regarding leg elevation.', SRC) == 'Education provided regarding leg elevation.'
    assert avoid_unrequested_wording('Teaching was reinforced on offloading.', SRC) == 'Teaching was provided on offloading.'
    assert avoid_unrequested_wording('Reinforced education on diet.', SRC) == 'Provided education on diet.'
    assert avoid_unrequested_wording('Reinforced the importance of leg elevation.', SRC) == 'Discussed the importance of leg elevation.'
    assert avoid_unrequested_wording('Client and wife reinforced importance of offloading.', SRC) == 'Client and wife discussed importance of offloading.'


def test_dictated_reinforced_is_preserved_word_for_word():
    dictated = {'untrusted_sources': [{'text': 'I reinforced education about elevation.'}]}
    assert avoid_unrequested_wording('Education reinforced regarding elevation.', dictated) == 'Education reinforced regarding elevation.'


def test_other_clinical_uses_are_never_changed():
    for line in ['Dressing reinforced with ABD pad.', 'Outer dressing reinforced due to strike-through.']:
        assert avoid_unrequested_wording(line, SRC) == line


def test_stock_openers_are_removed_only_when_not_dictated():
    assert avoid_unrequested_wording('On assessment, wound bed is pink.', SRC) == 'Wound bed is pink.'
    assert avoid_unrequested_wording("Pain 2/10. During today's visit, wound cleansed with NS.", SRC) == 'Pain 2/10. Wound cleansed with NS.'
    dictated = {'untrusted_sources': [{'text': 'On assessment the wound is pink.'}]}
    assert avoid_unrequested_wording('On assessment, wound bed is pink.', dictated) == 'On assessment, wound bed is pink.'


def test_layout_cleaner_applies_the_rule_only_with_source_context():
    result = {'text': 'Education reinforced regarding elevation.', 'status': 'ready', 'ui_alerts': []}
    assert clean_generated_layout(result, {'template': 'nursing'}, SRC)['text'] == 'Education provided regarding elevation.'
    assert clean_generated_layout(result, {'template': 'nursing'})['text'] == 'Education reinforced regarding elevation.'


def test_nothing_else_changes():
    text = 'Wound cleansed with NS.\nDressed with Mepilex.\nNo increased warmth noted.'
    assert avoid_unrequested_wording(text, SRC) == text
