import pytest
from pydantic import ValidationError
from server import providers

@pytest.mark.parametrize('date',['unknown','2026-10-27',None,{'text':'Synthetic'},42])
def test_photo_date_excluded_without_mutating_source(date):
    value={'text':'Synthetic visible redness','observed_on':date}
    result=providers.validate_result({'facts':[{'name':'periwound','value':value,'quote':''}], 'warnings':[], 'quality':{'status':'sufficient','reason':'none'}},'image')
    assert result['facts'][0]['value']=={'text':'Synthetic visible redness'}
    assert len(result['warnings'])==1
    assert 'observed_on' in value

def test_photo_schema_does_not_ask_for_dates():
    schema=providers.result_schema('image')
    for variant in schema['properties']['facts']['items']['anyOf']:
        assert 'observed_on' not in variant['properties']['value']['properties']

def test_extraction_date_validation_unchanged():
    with pytest.raises(ValidationError):
        providers.validate_result({'facts':[{'name':'periwound','value':{'text':'Synthetic','observed_on':'unknown'},'quote':'Synthetic'}],'warnings':[]},'extract','Synthetic')

def test_other_extra_photo_keys_still_rejected():
    with pytest.raises(ValidationError):
        providers.validate_result({'facts':[{'name':'periwound','value':{'text':'Synthetic','observed_on':'unknown','invented':'Synthetic'},'quote':''}],'warnings':[],'quality':{'status':'sufficient','reason':'none'}},'image')
