import asyncio
import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from server import providers
from server.schemas import validated_value
from test_providers import gateway, completion, extract, operation_rows

@pytest.mark.parametrize('key',['colour','PatientJohnSmith','private-key-value'])
def test_no_arbitrary_keys_or_values(key):
    with pytest.raises(ValidationError) as caught:
        validated_value('periwound',{'text':'Synthetic confidential',key:'Secret value'})
    assert providers.validation_category(caught.value)=='VALUE:extra_forbidden:x'

def test_known_schema_field():
    with pytest.raises(ValidationError) as caught: validated_value('periwound',{'present':'yes'})
    assert providers.validation_category(caught.value)=='VALUE:bool_type:present'

@pytest.mark.parametrize('exc,expected',[(ValueError('Incomplete output'),'OUTPUT_CUT_OFF'),(ValueError('private text'),'VALUE_OTHER'),(KeyError('private text'),'MISSING_KEY')])
def test_no_exception_text(exc,expected):
    assert providers.validation_category(exc)==expected

def test_metered_failure(gateway,caplog):
    gateway.http.responses=[completion(finish='length')]
    with pytest.raises(HTTPException) as caught: extract(gateway)
    assert caught.value.headers['X-WCP-Validation']=='OUTPUT_CUT_OFF'
    assert 'provider_validation_failed category=OUTPUT_CUT_OFF' in caplog.text
    usage=operation_rows(gateway)[0].usage
    assert usage['failure_reason']=='invalid_response'
    assert usage['charge_waived'] is True

@pytest.mark.parametrize('task',['quick_draft','quick_refine','quick_qa','quick_repair','improve_report'])
def test_report_floor(gateway,monkeypatch,task):
    async def structured(task,content,model,**kwargs): return kwargs['max_tokens']
    monkeypatch.setattr(providers,'structured',structured)
    assert asyncio.run(providers.routed(task,'Synthetic',max_tokens=1000))>=8000
