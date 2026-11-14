"""Two false alarms that blocked a report on a made-up leg-ulcer visit (2026-11-13). Every name and detail is invented."""
from server import source_coverage
from server.quick_report_repair import GRAVE_REMAINING_POINT


def _context(text):
    return {'untrusted_sources': [{'kind': 'text', 'text': text}], 'case_facts': []}


def test_rt_and_lt_in_a_source_count_as_right_and_left():
    assert source_coverage.coverage_defects('Right leg venous ulcer.', _context('Rt leg venous ulcer')) == []
    assert source_coverage.coverage_defects('Left foot wound.', _context('Lt foot wound')) == []


def test_a_real_wrong_side_is_still_flagged():
    found = source_coverage.coverage_defects('Left leg venous ulcer.', _context('Rt leg venous ulcer'))
    assert any('check the side' in item for item in found)
    found = source_coverage.coverage_defects('Right leg ulcer.', _context('Lt leg ulcer'))
    assert any('check the side' in item for item in found)


def test_ordinary_words_containing_side_are_not_serious():
    for text in ('Dressing choice should be considered; inside the wound bed is moist.',
                 'Periwound outside the margin is dry; consider offloading.'):
        assert not GRAVE_REMAINING_POINT.search(text)


def test_real_serious_points_still_match():
    for text in ('The report names the wrong side.', 'The side is not stated in any source.', 'A negation was lost.',
                 'Laterality differs from the source.', 'This detail is invented.'):
        assert GRAVE_REMAINING_POINT.search(text), text
