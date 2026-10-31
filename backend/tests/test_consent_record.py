"""How the clinician confirmed the client's consent to record is stored with each recording.

Only a tap counts (phone dialog or the watch's Record visit button). Anything else, including older apps that send
only consent=true, is stored honestly as 'not_recorded', never as confirmed.
"""
import inspect

import pytest

from server import audio_parts, clinical


def test_a_phone_tap_is_stored_with_time_and_wording():
    record = clinical.consent_record('phone_tap', '2026-10-31T01:02:03.456Z', 'tap-client-agreed-v1')
    assert record['method'] == 'phone_tap' and record['confirmed_by'] == 'signed_in_clinician'
    assert record['confirmed_at_device'] == '2026-10-31T01:02:03.456Z' and record['wording'] == 'tap-client-agreed-v1'


def test_the_record_says_it_is_the_clinicians_confirmation_not_the_clients_signature():
    meaning = clinical.consent_record('phone_tap', None, None)['meaning']
    assert 'clinician' in meaning and "not the client's own signature" in meaning


def test_a_watch_tap_is_stored():
    record = clinical.consent_record('watch_tap', None, None)
    assert record['method'] == 'watch_tap' and record['confirmed_by'] == 'signed_in_clinician'
    assert 'confirmed_at_device' not in record


@pytest.mark.parametrize('method', [None, '', 'voice', 'spoken_yes', 'yes', 'PHONE_TAP', 'phone_tap '])
def test_anything_but_a_tap_is_not_recorded_as_consent(method):
    record = clinical.consent_record(method, '2026-10-31T01:02:03Z', 'tap-client-agreed-v1')
    assert record['method'] == 'not_recorded' and 'not evidence of consent' in record['meaning']
    assert 'confirmed_by' not in record


def test_odd_time_or_wording_values_are_dropped():
    for record in (clinical.consent_record('phone_tap', 'yesterday<script>', 'Some Wording!'),
                   clinical.consent_record('phone_tap', '1' * 41, None)):
        assert 'confirmed_at_device' not in record and 'wording' not in record and record['method'] == 'phone_tap'


def test_both_recording_endpoints_accept_and_store_the_consent_details():
    for function in (clinical.upload_evidence, audio_parts.upload_part):
        parameters = inspect.signature(function).parameters
        for name in ('consent_method', 'consent_confirmed_at', 'consent_wording'):
            assert name in parameters, (function.__name__, name)
    assert "'consent_record'" in inspect.getsource(clinical.upload_evidence)
    assert "'consent_record'" in inspect.getsource(audio_parts.upload_part)
