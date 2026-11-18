from server import providers


def test_photo_schema_does_not_ask_for_dates():
    schema = providers.result_schema('image')
    for variant in schema['properties']['facts']['items']['anyOf']:
        assert 'observed_on' not in variant['properties']['value']['properties']


def test_photo_date_from_model_is_dropped():
    value = {'text': 'Synthetic visible redness', 'observed_on': '2026-05-11'}
    result = providers.validate_result({'facts': [{'name': 'periwound', 'value': value, 'quote': ''}], 'warnings': [],
                                        'quality': {'status': 'sufficient', 'reason': 'none'}}, 'image')
    assert result['facts'][0]['value'] == {'text': 'Synthetic visible redness'}
    assert value['observed_on'] == '2026-05-11'
