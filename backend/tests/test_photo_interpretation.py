from server import providers, clinical


def _photo(facts):
    return {'facts': facts, 'warnings': [], 'quality': {'status': 'sufficient', 'reason': 'none'}}


def _fact(name, text):
    return {'name': name, 'value': {'text': text}, 'quote': ''}


def test_covered_toe_keeps_visible_findings_interpretation_and_next_steps_apart():
    result = providers.validate_result(_photo([
        _fact('visible_finding', 'Redness of the exposed second toe. The wound under the dressing cannot be assessed.'),
        _fact('photo_interpretation', 'For clinician review: redness is concerning for infection or inflammation.'),
        _fact('photo_next_steps', 'Check warmth, tenderness, swelling, drainage and spread of redness.')]), 'image')
    assert [f['name'] for f in result['facts']] == ['visible_finding', 'photo_interpretation', 'photo_next_steps']


def test_unusable_photo_still_returns_no_findings():
    result = providers.validate_result({'facts': [_fact('photo_interpretation', 'x')], 'warnings': [],
        'quality': {'status': 'insufficient', 'reason': 'blur'}}, 'image')
    assert result['facts'] == []


def test_advisory_fields_are_known_but_not_chartable():
    assert clinical.PHOTO_ADVISORY_FIELDS <= clinical.VISUAL_FIELDS
