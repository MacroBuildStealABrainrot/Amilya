"""Synthetic wire envelopes; no external calls or clinical fixtures."""
import asyncio
import importlib.util
import json
from pathlib import Path

import httpx
import pytest
from fastapi import HTTPException
from server import providers
from test_providers import gateway, completion, operation_rows


@pytest.fixture(params=['source', 'live_patch'])
def target(request, gateway, monkeypatch):
    module = providers
    if request.param == 'live_patch':
        path = Path(__file__).resolve().parents[2] / 'repairs/live-00021-lrx/providers.py'
        spec = importlib.util.spec_from_file_location('server.live_repair_providers', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        monkeypatch.setattr(module, 'PROMPTS', providers.PROMPTS)
        monkeypatch.setattr(module, 'settings', gateway.config)
        monkeypatch.setattr(module, 'SessionLocal', gateway.sessions)
        monkeypatch.setattr(module, 'check_rate', lambda *a, **k: None)
    return module


def image(module, gateway):
    return asyncio.run(module.structured('image', [{'type':'text','text':'Synthetic'}],
        next(iter(module.MODEL_PRICES)), user=gateway.user, encounter_id='synthetic'))


@pytest.mark.parametrize('nested', [False, True])
@pytest.mark.parametrize('category,status', [
    ('provider_overloaded',503), ('rate_limit_exceeded',429),
    ('content_policy_violation',403), ('refusal',403), ('invalid_image',400),
    ('max_tokens_exceeded',400), ('authentication',401), ('payment_required',402),
    ('timeout',504), ('server',500), ('provider_unavailable',502),
])
def test_typed_errors_preserved_and_never_accepted(target,gateway,caplog,nested,category,status):
    info={'code':502,'message':'PRIVATE SOURCE','metadata':{
        'error_type':category,'provider_code':'PRIVATE CODE','raw':'PRIVATE BODY'}}
    raw={'choices':[{'finish_reason':'error','message':{'content':'PRIVATE OUTPUT'},'error':info}]} if nested else {'error':info}
    gateway.http.responses=[raw]
    with pytest.raises(HTTPException) as caught: image(target,gateway)
    error=caught.value
    assert error.headers['X-WCP-Provider-Error']==category
    assert error.headers['X-WCP-Upstream-Status']=='200'
    assert error.headers['X-WCP-Retryable']=='false'
    assert f'transport=200 status={status} category={category}' in caplog.text
    assert len(gateway.http.calls)==1
    row=operation_rows(gateway)[0]
    assert row.usage['charge_waived'] is True and row.charged_micros==0
    assert row.actual_cost_micros is None
    assert 'PRIVATE' not in str(error.detail)+json.dumps(error.headers)+json.dumps(row.usage)+caplog.text


@pytest.mark.parametrize('category',['PatientJohnSmith',{'private':'value'},['private'],None])
def test_unknown_error_types_are_masked(target,gateway,caplog,category):
    gateway.http.responses=[{'error':{'code':502,'metadata':{'error_type':category}}}]
    with pytest.raises(HTTPException) as caught: image(target,gateway)
    assert caught.value.headers['X-WCP-Provider-Error']=='unknown'
    assert 'PatientJohnSmith' not in caplog.text


@pytest.mark.parametrize('code',[True,[],{},'9'*5000,None])
def test_malformed_codes_stay_bounded(target,code):
    response=httpx.Response(200,request=httpx.Request('POST','https://provider.invalid'))
    normalized=target.provider_error_response(response,{'error':{'code':code}})
    assert normalized.status_code==502


def test_null_error_does_not_reject_valid_completion(target,gateway):
    raw=completion({'facts':[],'warnings':[],'quality':{'status':'insufficient','reason':'no_wound'}})
    raw['error']=None
    gateway.http.responses=[raw]
    result=image(target,gateway)
    assert result['quality']['retake_required'] is True
    assert len(gateway.http.calls)==1


def test_malformed_error_still_fails(target,gateway):
    gateway.http.responses=[{'error':'PRIVATE BODY'}]
    with pytest.raises(HTTPException) as caught: image(target,gateway)
    assert caught.value.headers['X-WCP-Provider-Error']=='unknown'


def test_partial_output_with_error_is_not_clinical_success(target,gateway):
    raw=completion({'facts':[],'warnings':[],'quality':{'status':'sufficient','reason':'none'}})
    raw['choices'][0]['error']={'metadata':{'error_type':'provider_unavailable'}}
    gateway.http.responses=[raw]
    with pytest.raises(HTTPException): image(target,gateway)
    assert operation_rows(gateway)[0].charged_micros==0

