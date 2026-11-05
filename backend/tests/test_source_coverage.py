"""Free source-to-report comparison: lost and invented values, dates, products, key diagnoses and side."""
from server.source_coverage import coverage_defects


def ctx(text):
    return {'untrusted_sources': [{'kind': 'audio', 'text': text}], 'case_facts': []}


ONCOLOGY = ctx('Client has lymphoma and is immunocompromised. Cell cancer, one lesion on the left thigh, bleeding, '
               'pain 6 out of 10, increase in size and drainage. She cannot change her own dressing so seen daily. '
               'Dressed with Mepilex. Surgery November 6th.')


def test_complete_report_passes():
    report = ('Client has lymphoma and is immunocompromised. Cancerous lesion to the left thigh has increased in size with '
              'bleeding and more drainage; pain 6/10. Unable to change her own dressing, so seen daily. Dressed with Mepilex. '
              'Surgery scheduled for November 6.')
    assert coverage_defects(report, ONCOLOGY) == []


def test_lost_score_product_date_and_diagnoses_are_found():
    found = ' '.join(coverage_defects('Lesion on thigh. Seen daily.', ONCOLOGY))
    for expected in ('6/10', 'Mepilex', 'november 6', 'lymphoma', 'immunocompromised', 'cancer'):
        assert expected in found


def test_invented_values_dates_and_wrong_side_are_found():
    found = ' '.join(coverage_defects('Right thigh lesion 4 x 3 cm, BP 150/90, follow-up December 2.', ONCOLOGY))
    assert '150/90' in found and '4x3cm' in found and 'december 2' in found and 'check the side' in found


def test_spoken_numbers_and_ordinals_match_digits():
    source = ctx('Wound measures four by three cm. Appointment on the sixth of November.')
    assert coverage_defects('Wound 4 x 3 cm. Appointment November 6.', source) == []


def test_structured_facts_count_as_sources():
    context = {'untrusted_sources': [{'text': 'Synthetic recorded statement.'}],
               'case_facts': [{'name': 'pain', 'value': {'score_0_10': 2}}]}
    assert not [d for d in coverage_defects('Pain reported as 2/10.', context) if 'not in any source' in d]


def test_empty_inputs_never_fail():
    assert coverage_defects('', ONCOLOGY) == [] and coverage_defects('Note.', {}) == []


def test_across_specialties():
    cases = [
        ('BP 88/50, HR 118, SpO2 91% on room air. EMS activated and transported to ED.', 'Hypotensive and tachycardic. Transported to ED.', ['88/50', '91%']),
        ('Started apixaban 5 mg twice daily. INR not needed.', 'Started apixaban twice daily.', ['5mg']),
        ('Post-op day 3 after hip fracture repair, incision 12 cm, staples intact.', 'Hip incision with staples intact.', ['12cm', 'fracture']),
        ('Blood glucose 18.2 mmol/L, known diabetes, given insulin 6 units.', 'Glucose elevated, insulin given.', ['18.2mmol/l', '6units', 'diabetes']),
    ]
    for source, report, expected in cases:
        found = ' '.join(coverage_defects(report, ctx(source))).lower()
        for item in expected:
            assert item.lower() in found, (source, item, found)


def test_evaluation_scenarios_are_made_up_and_only_run_on_the_isolated_test_server(monkeypatch):
    from server import report_eval
    monkeypatch.setenv('AMILYA_REPORT_EVAL', 'before')
    monkeypatch.delenv('AMILYA_RENDER_TEST', raising=False)
    assert report_eval.start_if_requested() is None
    assert len(report_eval.SCENARIOS) >= 10
    good = {'text': "Client has lymphoma and is immunocompromised. Cancerous lesion to the left thigh is bleeding, has increased in size with more drainage; pain 6/10. Unable to change her own dressing, so seen daily. Cleansed with saline, Mepilex applied. Surgery scheduled for November 6.",
            'ui_alerts': [{'field': 'clarify', 'message': "Confirm the cancer type: the recording says 'cell cancer'."}]}
    checked = report_eval._check(report_eval.SCENARIOS[0], good)
    assert checked['passed'], checked
    bad = report_eval._check(report_eval.SCENARIOS[0], {'text': 'Client has lymphoma. Also has cell cancer. Bleeding noted. Seen daily.'})
    assert not bad['passed'] and 'missing' in bad['problems'] and 'unsupported_or_wrong' in bad['problems'] and 'no_clarify_alert' in bad['problems']


def test_handoff_products_are_checked():
    from server.source_coverage import coverage_defects
    defects = coverage_defects('Wound cleansed with NS.', {'untrusted_sources': [{'text': 'Cleansed with NS, Prisma applied, Darco footwear on.'}]})
    assert any('Prisma' in d for d in defects) and any('Darco' in d for d in defects)
