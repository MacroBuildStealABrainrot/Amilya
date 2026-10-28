"""Photo interpretation must never estimate tissue percentages for the clinician."""
import asyncio
import io

from PIL import Image
import pytest

from server import providers
from server.schemas import validated_value
from tests.test_providers import completion, gateway


def photo_result(value, name='wound_tissue'):
    return {'facts': [{'name': name, 'value': value, 'quote': ''}],
            'warnings': [], 'quality': {'status': 'sufficient', 'reason': 'none'}}


def test_photo_schema_has_no_numeric_tissue_fields_but_extraction_keeps_them():
    def tissue_schema(task):
        return next(v['properties']['value'] for v in
                    providers.result_schema(task)['properties']['facts']['items']['anyOf']
                    if 'wound_tissue' in v['properties']['name']['enum'])
    image = tissue_schema('image')
    assert set(image['properties']) == {'text'}
    assert image['required'] == ['text'] and image['additionalProperties'] is False
    assert 'granulation' in tissue_schema('extract')['properties']


@pytest.mark.parametrize('description', [
    'Wound bed 80% red granulation and 20% slough.',
    'Approximately eighty percent granulation.',
    'Eighty per cent red tissue.',
    'Eighty per-cent granulation.',
    'Granulation ８０％.',
    'Granulation ٨٠٪.',
])
@pytest.mark.parametrize('name', ['wound_tissue', 'wound_edges', 'periwound', 'exudate'])
def test_photo_percentage_text_cannot_be_accepted(description, name):
    with pytest.raises(ValueError):
        providers.validate_result(photo_result({'text': description}, name), 'image')


@pytest.mark.parametrize('field', ['granulation', 'slough', 'eschar', 'epithelial'])
def test_generated_numeric_tissue_estimate_cannot_be_accepted(field):
    with pytest.raises(ValueError):
        providers.validate_result(photo_result({'text': 'Moist red tissue.', field: 80.0}), 'image')


def test_qualitative_photo_preserves_supported_words_without_filling_numbers():
    result = providers.validate_result(photo_result({'text': 'Moist red granulation tissue with adherent yellow slough.'}), 'image')
    assert result['facts'][0]['value'] == {'text': 'Moist red granulation tissue with adherent yellow slough.'}
    assert not result['warnings']
    assert validated_value('wound_tissue', {'text': 'Clinician entered', 'granulation': 80.0, 'slough': 20.0}) == {
        'text': 'Clinician entered', 'granulation': 80.0, 'slough': 20.0}


def test_invalid_percentage_finding_does_not_discard_independent_observation():
    data = photo_result({'text': '80% granulation.'})
    data['facts'].append({'name': 'periwound', 'value': {'text': 'Periwound dry.'}, 'quote': ''})
    result = providers.validate_result(data, 'image')
    assert [f['name'] for f in result['facts']] == ['periwound']
    assert result['facts'][0]['value'] == {'text': 'Periwound dry.'}
    assert len(result['warnings']) == 1


def test_photo_percentage_failure_uses_existing_bounded_recovery(gateway):
    photo = io.BytesIO()
    Image.new('RGB', (32, 24), 'pink').save(photo, format='JPEG')
    gateway.http.responses = [
        completion(photo_result({'text': '80% granulation.'})),
        completion(photo_result({'text': 'Moist red granulation tissue.'})),
    ]
    result = asyncio.run(providers.analyze_media(photo.getvalue(), 'image/jpeg', 'photo',
        user=gateway.user, encounter_id='qualitative-photo'))
    assert result['facts'][0]['value'] == {'text': 'Moist red granulation tissue.'}
    assert len(gateway.http.calls) == 2
    assert all('never estimate percentages' in str(c['json']['messages']) for c in gateway.http.calls)


def test_photo_percentage_recovery_cannot_accept_another_estimate(gateway):
    from fastapi import HTTPException
    photo = io.BytesIO()
    Image.new('RGB', (32, 24), 'pink').save(photo, format='JPEG')
    gateway.http.responses = [completion(photo_result({'text': '80% granulation.'})) for _ in range(2)]
    with pytest.raises(HTTPException) as caught:
        asyncio.run(providers.analyze_media(photo.getvalue(), 'image/jpeg', 'photo',
            user=gateway.user, encounter_id='qualitative-photo-rejected'))
    assert caught.value.headers['X-WCP-Error'] == 'INVALID_RESPONSE'
    assert len(gateway.http.calls) == 2
