"""Owner principle (2026-11-07): an accurate note, or a clear alert that the recording is not reliable. All data invented."""
from server import documentation, recording_quality
from server.source_coverage import coverage_defects, negation_defects, strong_findings

FILLER = ('Thank you. thud  Thank you.  sounds of water  Thank you. Thank you.  you  Thanks for watching!  Thank you. '
          'Any pain? No. Thank you.  Thank you.  click  Thank you. Thank you. Thank you.')


def ctx(text, warnings=None, seconds=None):
    source = {'kind': 'audio', 'text': text, 'warnings': warnings or []}
    if seconds is not None:
        source['duration_seconds'] = seconds
    return {'untrusted_sources': [source], 'case_facts': []}


def test_a_clear_recording_gets_no_quality_alert():
    clear = 'Client denies pain, fever and chills. Dressing changed with Mepilex. Wound 3 by 2 cm, granulation, small serous drainage. ' * 3
    result = recording_quality.apply({'text': 'Note.', 'ui_alerts': []}, ctx(clear, seconds=60))
    assert result['ui_alerts'] == [] and 'recording_unreliable' not in result


def test_mostly_filler_adds_one_plain_alert_first():
    result = recording_quality.apply({'text': 'Denies pain.', 'ui_alerts': [{'field': 'clarify', 'message': 'Please confirm the dose.'}]}, ctx(FILLER * 2))
    assert result['ui_alerts'][0]['message'] == recording_quality.UNRELIABLE_ALERT
    assert result['ui_alerts'][1]['message'] == 'Please confirm the dose.'
    assert 'mostly_filler' in result['recording_unreliable']


def test_the_report_ai_can_flag_background_tv_that_rules_cannot_see():
    flagged = {'text': 'Denies pain.', 'ui_alerts': [{'field': 'recording_quality', 'message': 'Recording quality: much of this recording was unclear.'}]}
    result = recording_quality.apply(flagged, ctx('Any pain? No. The captain said the engines are failing and we must land the ship now.'))
    assert [a['message'] for a in result['ui_alerts']] == [recording_quality.UNRELIABLE_ALERT]


def test_speech_service_warnings_and_long_silence_count():
    assert 'speech_service_warning' in recording_quality.free_signals(ctx('Any pain? No.', ['Some words were repeated by mistake ...']))
    assert 'mostly_silence' in recording_quality.free_signals(ctx('Any pain? No. Okay.', seconds=600))


def test_no_note_says_the_true_reason():
    shown = documentation.visible_result({'text': '', 'status': 'no_content', 'recording_unreliable': ['report_ai']})
    assert shown['ui_message'] == recording_quality.UNRELIABLE_NO_NOTE and shown['text'] == ''
    plain = documentation.visible_result({'text': '', 'status': 'no_content'})
    assert plain['ui_message'] == documentation.NO_CONTENT


def test_a_lost_negation_is_a_strong_finding():
    source = 'Any pain today? No. Any fever or chills? No. BP 118 over 72.'
    found = coverage_defects('Client reports pain. Denies fever and chills. BP 118/72.', ctx(source))
    assert any('check the negation' in item for item in strong_findings(found))
    assert negation_defects('Denies pain, fever or chills. BP 118/72.', source) == []


def test_a_pertinent_negative_nobody_assessed_is_flagged():
    found = negation_defects('Denies cough or shortness of breath.', 'Any pain? No.')
    assert any('cough' in f for f in found) and any('shortness of breath' in f for f in found)
    assert negation_defects('Pain 0/10.', 'No pain today.') == []
    assert negation_defects('Pain 4/10 with walking, none at rest.', 'No pain at rest, but pain with walking, 4 out of 10.') == []


def test_a_spoken_summary_is_marked_as_the_clinicians_own_account():
    from server import providers
    rules = providers.prompt('quick_draft')
    assert "clinician's own spoken summary" in rules and 'follow the summary' in rules
    from server.audio_parts import CompleteAudio
    import uuid
    job = uuid.uuid4()
    plain = CompleteAudio(job_id=job, count=1)
    assert 'summary' not in plain.model_dump(mode='json', exclude={'summary'})   # older apps keep the same request code
    assert CompleteAudio(job_id=job, count=1, summary=True).summary is True


def test_short_of_breath_in_any_wording_is_the_same_symptom():
    assert negation_defects('Increased shortness of breath climbing stairs.', 'More short of breath climbing stairs.') == []
    assert negation_defects('Breathless overnight.', 'Daughter says more breathless overnight.') == []


def test_a_lost_onset_or_follow_up_day_is_found():
    found = coverage_defects('Legs more swollen. Return visit Friday.', ctx('Legs more swollen since Tuesday. Return visit Friday.'))
    assert any('Tuesday' in item for item in found) and not any('Friday' in item for item in found)
