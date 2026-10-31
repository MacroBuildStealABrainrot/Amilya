"""Synthetic transport regressions: no live keys, provider calls or clinical data."""
import asyncio
import json
import logging
import uuid

import pytest
from fastapi import HTTPException
from server import diagnostics as d, providers
from test_providers import gateway, completion, operation_rows


@pytest.mark.parametrize('task,setting,effort', [
    ('extract','model_extraction','low'), ('document','model_extraction','low'),
    ('quick_draft','model_documentation','medium'), ('quick_qa','model_documentation','medium'),
    ('review','model_review','medium'), ('consult','model_review','medium'),
    ('image','model_vision','high'), ('challenge','model_challenger','high'),
    ('escalation','model_frontier','high'), ('reconcile','model_frontier','high'),
])
def test_configured_models_are_used_without_changing_effort(gateway, task, setting, effort):
    setattr(gateway.config, setting, 'anthropic/claude-sonnet-5.5')
    route=providers.configured_route(task,'{}')
    assert route['model']=='anthropic/claude-sonnet-5.5'
    assert route['effort_level']==effort


def test_photo_wire_request_uses_verified_vision_route_and_safe_parameters(gateway):
    from test_photo_recovery import assess, successful
    gateway.config.model_vision='openai/gpt-6.1-sol'
    gateway.http.responses=[successful()]
    assess(gateway)
    payload=gateway.http.calls[0]['json']
    assert payload['model']=='openai/gpt-6.1-sol'
    assert payload['reasoning']['effort']=='high'
    assert payload['provider']=={'zdr':True,'data_collection':'deny','require_parameters':True}
    assert 'temperature' not in payload and 'models' not in payload
    assert payload['messages'][1]['content'][1]['type']=='image_url'


def test_deep_review_always_gets_independent_source_only_challenge(gateway):
    gateway.http.responses=[completion({'considerations':[label],'warnings':[]})
        for label in ('FIRST_MODEL_CONCLUSION','Independent opinion','Reconciled')]
    result=asyncio.run(providers.clinical_review([], 'deep',user=gateway.user,encounter_id='synthetic'))
    calls=[x['json'] for x in gateway.http.calls]
    assert [x['response_format']['json_schema']['name'] for x in calls]==[
        'clinical_review','clinical_challenge','clinical_reconcile']
    assert calls[1]['model']=='anthropic/claude-sonnet-5.5'
    assert calls[0]['model'].split('/')[0]!=calls[1]['model'].split('/')[0]
    assert 'FIRST_MODEL_CONCLUSION' not in calls[1]['messages'][1]['content']
    assert result['routing']['independent_challenge'] is True
    assert result['routing']['escalated'] is False


@pytest.mark.parametrize('model',['openai/gpt-6-astra','openai/gpt-6.1-sol','unknown/model'])
def test_invalid_challenger_configuration_fails_before_any_paid_request(gateway, model):
    gateway.config.model_challenger=model
    with pytest.raises(HTTPException) as exc:
        asyncio.run(providers.clinical_review([], 'deep',user=gateway.user,encounter_id='synthetic'))
    assert exc.value.status_code==503
    assert gateway.http.calls==[] and operation_rows(gateway)==[]


@pytest.mark.parametrize('reported',[None,'openai/gpt-6.1-sol'])
def test_selected_and_reported_model_are_distinguished(gateway, reported):
    value=completion()
    if reported is not None:value['model']=reported
    gateway.http.responses=[value]
    result=asyncio.run(providers.structured('extract','synthetic','openai/gpt-6.1-sol',
        user=gateway.user,encounter_id='synthetic'))
    assert result['usage']['model']=='openai/gpt-6.1-sol'
    assert result['usage']['reported_model']==reported
    assert result['usage']['model_identity']==('confirmed' if reported else 'unreported')


@pytest.mark.parametrize('reported',['openai/gpt-6-astra','SYNTHETIC_SECRET_NOT_A_MODEL',{'unexpected':'data'}])
def test_mismatched_model_is_rejected_without_charging_or_storing_untrusted_text(gateway, reported):
    value=completion(); value['model']=reported
    gateway.http.responses=[value]
    with pytest.raises(HTTPException) as exc:
        asyncio.run(providers.structured('extract','synthetic','openai/gpt-6.1-sol',
            user=gateway.user,encounter_id='synthetic'))
    assert exc.value.status_code==502 and exc.value.headers['X-WCP-Retryable']=='false'
    row=operation_rows(gateway)[0]
    assert row.usage['charge_waived'] and row.usage['model_identity']=='mismatch'
    assert 'SYNTHETIC_SECRET' not in json.dumps(row.usage)


@pytest.mark.parametrize('task',['image','review','challenge'])
def test_all_ai_stages_have_an_absolute_deadline_and_release_the_hold(gateway, task):
    gateway.config.provider_timeout_seconds=.01
    async def slow(_):
        await asyncio.sleep(1)
        return completion()
    gateway.http.responses=[slow]
    with pytest.raises(HTTPException) as exc:
        asyncio.run(providers.structured(task,'synthetic','openai/gpt-6.1-sol',
            user=gateway.user,encounter_id='synthetic'))
    assert exc.value.status_code==504
    assert operation_rows(gateway)[0].usage['charge_waived'] is True


@pytest.mark.parametrize('status,category',[(400,'REQUEST_INCOMPATIBLE'),(401,'PROVIDER_AUTH'),
    (402,'PROVIDER_QUOTA'),(404,'MODEL_UNAVAILABLE'),(502,'PROVIDER_UNAVAILABLE')])
def test_cloud_logs_correlate_failures_when_database_telemetry_is_unavailable(gateway,monkeypatch,caplog,status,category):
    request_id=str(uuid.uuid4())
    token=d.begin(request_id,request_id)
    monkeypatch.setattr(d,'persist',lambda action:None)
    caplog.set_level(logging.INFO,logger='woundcopilot.provider')
    gateway.http.responses=[(status,{'error':{'message':'SYNTHETIC_SECRET'},'model':'SYNTHETIC_SECRET'})]
    try:
        with pytest.raises(HTTPException):
            asyncio.run(providers.structured('image','SYNTHETIC_IMAGE_CONTENT','openai/gpt-6.1-sol',
                user=gateway.user,encounter_id='SYNTHETIC_PATIENT_ID'))
    finally:d.active.reset(token)
    records=[json.loads(r.message) for r in caplog.records if r.name=='woundcopilot.provider']
    assert records and all(r['request_id']==request_id for r in records)
    assert records[-1]['error_category']==category
    assert any(r['http_status']==status for r in records)
    serialized=json.dumps(records)
    assert not any(secret in serialized for secret in ['SYNTHETIC_SECRET','SYNTHETIC_IMAGE_CONTENT',
        'SYNTHETIC_PATIENT_ID','synthetic-openrouter-key'])
