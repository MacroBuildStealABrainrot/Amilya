"""Synthetic regression against the fingerprint-verified deployed provider file."""
import asyncio
import pytest
from fastapi import HTTPException
from server import providers
from test_providers import gateway, completion, extract, operation_rows


def test_extraction_that_exceeds_old_cap_completes(gateway):
    def response(call):
        budget=call['json']['max_completion_tokens']
        return completion(finish='stop' if budget>=2500 else 'length',
            usage={'prompt_tokens':2426,'completion_tokens':min(budget,2500),'cost':.003})
    gateway.http.responses=[response]
    result=extract(gateway)
    assert result['facts']==[] and result['usage']['output_tokens']==2500
    payload=gateway.http.calls[0]['json']
    assert payload['max_completion_tokens']==8000
    assert payload['stream'] is False
    assert payload['reasoning']=={'effort':'low','exclude':True}
    assert payload['provider']=={'zdr':True,'data_collection':'deny','require_parameters':True}


@pytest.mark.parametrize('task,size,budget',[('extract',20000,10000),('document',40000,16000),('image',2,8000)])
def test_structured_evidence_budget_is_bounded(gateway,monkeypatch,task,size,budget):
    async def structured(task,content,model,**kwargs):return kwargs['max_tokens']
    monkeypatch.setattr(providers,'structured',structured)
    content='x'*size if task!='image' else [{'type':'text','text':'synthetic'},{'type':'image_url','image_url':{'url':'synthetic'}}]
    assert asyncio.run(providers.routed(task,content))==budget


def test_still_truncated_response_remains_rejected_without_customer_charge(gateway):
    gateway.http.responses=[completion(finish='length')]
    with pytest.raises(HTTPException) as exc:extract(gateway)
    assert exc.value.status_code==502
    row=operation_rows(gateway)[0]
    assert row.charged_micros==0 and row.usage['charge_waived']
