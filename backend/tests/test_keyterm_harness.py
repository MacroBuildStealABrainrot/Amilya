import importlib.util
from pathlib import Path

_path = Path(__file__).resolve().parents[1] / 'scripts' / 'deepgram_keyterm_test' / 'run_test.py'
_spec = importlib.util.spec_from_file_location('keyterm_run_test', _path)
harness = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(harness)


def test_word_list_fits_deepgram_limits():
    terms = harness.load_terms()
    assert 1 <= len(terms) <= 100
    assert sum(len(t.split()) for t in terms) < 300  # well under 500 tokens
    for term in terms:
        assert not any(ch in term for ch in ',;:\n'), term


def test_term_matching_ignores_case_spaces_and_punctuation():
    assert harness.term_found('Aquacel Ag', 'applied aquacel AG today')
    assert harness.term_found('Bates-Jensen', 'bates jensen tool')
    assert not harness.term_found('slough', 'a slow wound')


def test_error_rate():
    assert harness.word_error_rate('a b c d', 'a b c d') == 0
    assert harness.word_error_rate('a b c d', 'a x c d') == 0.25


def test_report_counts_wrong_words():
    results = [{'id': 'x', 'truth': 'yellow slough noted', 'without': 'yellow slow noted', 'with': 'yellow slough noted'}]
    text = harness.report(results, ['slough'])
    assert '| slough | WRONG | right |' in text
