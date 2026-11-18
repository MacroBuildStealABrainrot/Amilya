"""Regression for the observed output truncation; all provider traffic is synthetic."""
import asyncio
import json
import logging
import uuid

import pytest
from fastapi import HTTPException
from sqlalchemy import select
from server import billing, diagnostics, providers
from server.error_feedback import extraction_warning
from server.provider_models import ProviderResult
from test_providers import gateway, completion, extract, operation_rows


@pytest.mark.parametrize('kind,size,expected', [('typed',100,8000),('document',100,8000),
    ('typed',20000,None),('document',40000,16000)])
def test_extraction_has_room_for_structured_evidence_with_a_bounded_budget(gateway,kind,size,expected):
    gateway.config.model_extraction='openai/gpt-6-luna'
    gateway.http.responses=[completion()]
    extract(gateway,'x'*size,kind)
    payload=gateway.http.calls[0]['json']
    budget=payload['max_completion_tokens']
    assert 8000<=budget<=16000
    assert budget==(expected or len(payload['messages'][1]['content'])//2)
    assert payload['reasoning']=={'effort':'low','exclude':True}
    assert payload['provider']=={'zdr':True,'data_collection':'deny','require_parameters':True}


def test_response_larger_than_old_cap_finishes_and_validates(gateway):
    gateway.config.model_extraction='openai/gpt-6-luna'
    def synthetic_provider(request):
        budget=request['json']['max_completion_tokens']
        return completion(finish='stop' if budget>=2500 else 'length',
            usage={'prompt_tokens':2426,'completion_tokens':min(2500,budget),'cost':.003})
    gateway.http.responses=[synthetic_provider]
    result=extract(gateway)
    assert result['facts']==[] and result['usage']['output_tokens']==2500
    assert result['usage']['finish_reason']=='stop'


@pytest.mark.parametrize('task',['extract','document','image','quick_draft','quick_qa'])
def test_truncation_accepts_no_partial_result_and_never_repeats_same_budget(gateway,task):
    raw=completion(finish='length',usage={'prompt_tokens':2426,'completion_tokens':1800,'cost':.0028})
    raw['choices'][0]['message']['content']='{"facts":[{"name":"SYNTHETIC_PRIVATE_PARTIAL'
    gateway.http.responses=[raw]
    for _ in range(2):
        with pytest.raises(HTTPException) as caught:
            asyncio.run(providers.structured(task,'synthetic','openai/gpt-6-luna',
                max_tokens=1800,user=gateway.user,encounter_id='synthetic'))
        assert caught.value.status_code==502
        assert caught.value.headers['X-WCP-Error']=='OUTPUT_LIMIT'
        assert caught.value.headers['X-WCP-Retryable']=='false'
        assert 'OUTPUT_LIMIT' in extraction_warning(caught.value)
    assert len(gateway.http.calls)==1
    row=operation_rows(gateway)[0]
    assert row.actual_cost_micros==2800 and row.charged_micros==0
    assert row.usage['charge_waived'] and row.usage['failure_reason']=='output_limit'
    assert row.usage['max_output_tokens']==1800 and row.usage['finish_reason']=='length'
    assert 'SYNTHETIC_PRIVATE_PARTIAL' not in json.dumps(row.usage)
    with gateway.sessions() as db:
        assert db.scalar(select(ProviderResult)) is None


def test_photo_output_limit_is_not_misclassified_as_schema_recovery(gateway):
    from test_photo_recovery import assess
    gateway.http.responses=[completion(finish='length')]
    for _ in range(2):
        with pytest.raises(HTTPException) as caught:assess(gateway)
        assert caught.value.headers['X-WCP-Error']=='OUTPUT_LIMIT'
    assert len(gateway.http.calls)==1
    assert gateway.http.calls[0]['json']['max_tokens']==12000


@pytest.mark.parametrize('selected,reported', [
    ('openai/gpt-6-luna','openai/gpt-6-luna-20260922'),
    ('openai/gpt-6.1-sol','openai/gpt-6.1-sol-20260929'),
    ('openai/gpt-6-astra','openai/gpt-6-astra-20260903'),
    ('anthropic/claude-sonnet-5.5','anthropic/claude-sonnet-5.5-20260928'),
])
def test_reviewed_provider_snapshot_identity_is_preserved(gateway,selected,reported,monkeypatch,caplog):
    raw=completion();raw['model']=reported
    gateway.http.responses=[raw]
    monkeypatch.setattr(diagnostics,'persist',lambda action:None)
    caplog.set_level(logging.INFO,logger='woundcopilot.provider')
    request_id=str(uuid.uuid4());token=diagnostics.begin(request_id,request_id)
    try:
        result=asyncio.run(providers.structured('extract','synthetic',selected,
            user=gateway.user,encounter_id='synthetic'))
    finally:diagnostics.active.reset(token)
    assert result['usage']['model']==selected and result['usage']['reported_model']==reported
    assert operation_rows(gateway)[0].usage['model_identity']=='confirmed'
    assert operation_rows(gateway)[0].usage['reported_model']==reported
    records=[json.loads(r.message) for r in caplog.records if r.name=='woundcopilot.provider']
    assert records[-1]['selected_model']==selected and records[-1]['reported_model']==reported
    assert records[-1]['finish_reason']=='stop' and records[-1]['max_output_tokens']==1800


@pytest.mark.parametrize('reported',['openai/gpt-6.1-sol-20990101',
    'openai/gpt-6-astra-20260903','openai/gpt-6.1-sol-20260929:SYNTHETIC_SECRET'])
def test_unknown_or_different_snapshots_still_fail_closed(gateway,reported):
    raw=completion();raw['model']=reported;gateway.http.responses=[raw]
    with pytest.raises(HTTPException) as caught:
        asyncio.run(providers.structured('extract','synthetic','openai/gpt-6.1-sol',
            user=gateway.user,encounter_id='synthetic'))
    assert caught.value.headers['X-WCP-Error']=='REQUEST_INCOMPATIBLE'
    assert operation_rows(gateway)[0].usage['charge_waived']
    assert 'SYNTHETIC_SECRET' not in json.dumps(operation_rows(gateway)[0].usage)


@pytest.mark.parametrize('finish',['SYNTHETIC_SECRET',[],{'unexpected':'SYNTHETIC_SECRET'}])
def test_arbitrary_finish_metadata_is_never_stored(gateway,finish):
    gateway.http.responses=[completion(finish=finish)]
    with pytest.raises(HTTPException):extract(gateway)
    assert 'finish_reason' not in operation_rows(gateway)[0].usage
    assert 'finish_reason' not in billing._safe_usage({'finish_reason':finish})
