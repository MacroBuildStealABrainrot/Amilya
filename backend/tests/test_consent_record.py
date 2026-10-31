"""How the clinician confirmed the client's consent to record is stored with each recording.

Only a tap counts (phone dialog or the watch's Record visit button). Anything else, including older apps that send
only consent=true, is stored honestly as 'not_recorded', never as confirmed.
"""
import inspect

import pytest

from server import audio_parts, clinical


def test_a_phone_tap_is_stored_with_time_and_wording():
    assert clinical.consent_record('phone_tap', '2026-10-31T01:02:03.456Z', 'tap-client-agreed-v1') == {
        'method': 'phone_tap', 'confirmed_at_device': '2026-10-31T01:02:03.456Z', 'wording': 'tap-client-agreed-v1'}


def test_a_watch_tap_is_stored():
    assert clinical.consent_record('watch_tap', None, None) == {'method': 'watch_tap'}


@pytest.mark.parametrize('method', [None, '', 'voice', 'spoken_yes', 'yes', 'PHONE_TAP', 'phone_tap '])
def test_anything_but_a_tap_is_not_recorded_as_consent(method):
    assert clinical.consent_record(method, '2026-10-31T01:02:03Z', 'tap-client-agreed-v1') == {'method': 'not_recorded'}


def test_odd_time_or_wording_values_are_dropped():
    assert clinical.consent_record('phone_tap', 'yesterday<script>', 'Some Wording!') == {'method': 'phone_tap'}
    assert clinical.consent_record('phone_tap', '1' * 41, None) == {'method': 'phone_tap'}


def test_both_recording_endpoints_accept_and_store_the_consent_details():
    for function in (clinical.upload_evidence, audio_parts.upload_part):
        parameters = inspect.signature(function).parameters
        for name in ('consent_method', 'consent_confirmed_at', 'consent_wording'):
            assert name in parameters, (function.__name__, name)
    assert "'consent_record'" in inspect.getsource(clinical.upload_evidence)
    assert "'consent_record'" in inspect.getsource(audio_parts.upload_part)
