"""The permanent evaluation visits are well formed, and every planted wrong report is caught by the fixed checks."""
import re
from server import eval_cases, report_eval


def test_cases_are_complete_and_unique():
    ids = [c['id'] for c in eval_cases.CASES]
    assert len(ids) == len(set(ids)) and len(ids) >= 30
    areas = ' '.join(c['area'] for c in eval_cases.CASES).lower()
    for area in ('nursing', 'medicine', 'surg', 'emergency', 'palliative', 'dermatology', 'photo', 'long visit'):
        assert area in areas, area
    for case in eval_cases.CASES:
        assert case['expected'] and (case['must'] or case.get('expect_no_note')) and case['profession'] and case['template']
        assert case.get('text') or case.get('facts')
        for pattern in case['must'] + case['must_not']:
            re.compile(pattern)


def test_earlier_failure_patterns_are_covered():
    patterns = ' '.join(c.get('pattern', '') for c in eval_cases.CASES).lower()
    for needed in ('unclear term', 'dressing names', 'correction', 'chatter', 'long conversation', 'repeated', 'invented'):
        assert needed in patterns, needed


def test_every_planted_wrong_report_is_caught_by_the_fixed_checks():
    planted = [(c, p) for c in eval_cases.CASES for p in c.get('planted', [])]
    assert len(planted) >= 10
    for case, wrong in planted:
        assert not report_eval._check(case, {'text': wrong['text'], 'ui_alerts': []})['passed'], (case['id'], wrong['mistake'])


def test_review_file_lists_every_case():
    text = eval_cases.review_markdown()
    for case in eval_cases.CASES:
        assert case['id'] in text


def test_noisy_recordings_rule_is_given_to_every_report():
    from server import providers
    rules = providers.prompt('quick_draft')
    assert 'Noisy recordings' in rules and 'Thanks for watching' in rules and 'not separate clients' in rules
