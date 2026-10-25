import json

from fastapi import HTTPException
import pytest

from server.media_review import parse_photo_review


VALID = {'algorithm_version': 'capture-quality-v1', 'focus_variance': 250.5, 'mean_luminance': 120,
         'clipped_fraction': .04, 'obstruction': 'partial', 'limitation_note': 'Synthetic dressing overlap',
         'rotation_degrees': 90, 'crop_percent': 10}


def test_photo_review_is_explicitly_client_reported_nonclinical_metadata():
    result = parse_photo_review(json.dumps(VALID), 'photo')
    assert result['client_reported'] is True and result['clinical_assessment'] is False
    assert result['obstruction'] == 'partial' and result['rotation_degrees'] == 90
    assert parse_photo_review(None, 'audio') is None


@pytest.mark.parametrize('change', [
    {'focus_variance': -1}, {'focus_variance': '250'}, {'mean_luminance': 256},
    {'clipped_fraction': 1.1}, {'obstruction': 'clinically_safe'}, {'limitation_note': 'x'*501},
    {'rotation_degrees': True}, {'rotation_degrees': 45}, {'crop_percent': 31},
    {'diagnosis': 'infected'}, {'client_reported': False},
])
def test_invalid_capture_metadata_is_rejected(change):
    with pytest.raises(HTTPException) as error:
        parse_photo_review(json.dumps({**VALID, **change}), 'photo')
    assert error.value.status_code == 422


def test_photo_review_cannot_attach_to_audio_or_unbounded_payload():
    with pytest.raises(HTTPException) as error:
        parse_photo_review(json.dumps(VALID), 'audio')
    assert error.value.status_code == 422
    with pytest.raises(HTTPException) as error:
        parse_photo_review('x'*4001, 'photo')
    assert error.value.status_code == 413


def test_manual_capture_review_preserves_missing_metrics_without_fabrication():
    manual = {'algorithm_version': 'manual-review-v1', 'obstruction': 'uncertain', 'limitation_note': 'Synthetic manual review',
              'rotation_degrees': 0, 'crop_percent': 0}
    result = parse_photo_review(json.dumps(manual), 'photo')
    assert result['focus_variance'] is None and result['mean_luminance'] is None and result['clipped_fraction'] is None
    with pytest.raises(HTTPException):
        parse_photo_review(json.dumps({**manual, 'focus_variance': 0}), 'photo')
    with pytest.raises(HTTPException):
        parse_photo_review(json.dumps({**manual, 'algorithm_version': 'capture-quality-v1'}), 'photo')
