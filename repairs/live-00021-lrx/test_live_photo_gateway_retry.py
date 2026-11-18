import asyncio
import json
import httpx
import pytest
from fastapi import HTTPException
from server import providers
from test_providers import gateway, completion, operation_rows


def image(gateway):
    return asyncio.run(providers.structured('image', [{'type':'text','text':'synthetic'}],
        'openai/gpt-5.6-luna', user=gateway.user, encounter_id='synthetic-encounter'))


@pytest.mark.parametrize('status', [502,503,504])
def test_image_gateway_failure_recovers_once(gateway,status):
    gateway.http.responses=[(status,{'error':{'message':'temporary gateway failure'}}),
        completion({'quality':{'status':'sufficient','reason':'none'},'facts':[], 'warnings':[]})]
    image(gateway)
    assert len(gateway.http.calls)==2 and len(gateway.retry_sleeps)==1
    assert gateway.http.calls[0]['json']==gateway.http.calls[1]['json']
    assert gateway.http.calls[1]['json']['provider']=={'zdr':True,'data_collection':'deny','require_parameters':True}
    assert operation_rows(gateway)[0].usage['attempts'][0]['error_code']==str(status)


def test_repeated_gateway_failure_stops_and_waives(gateway):
    gateway.http.responses=[(502,{'error':{'message':'SECRET'}})]*2
    with pytest.raises(HTTPException):image(gateway)
    assert len(gateway.http.calls)==2
    row=operation_rows(gateway)[0]
    assert row.charged_micros==0 and row.usage['charge_waived']
    assert len(row.usage['attempts'])==2
    assert 'SECRET' not in json.dumps(row.usage)


@pytest.mark.parametrize('extra', [{'usage':{}},{'choices':[]},{'id':'generation-id'}])
def test_generation_evidence_is_never_retried(gateway,extra):
    gateway.http.responses=[(502,{'error':{'message':'temporary'},**extra})]
    with pytest.raises(HTTPException):image(gateway)
    assert len(gateway.http.calls)==1


@pytest.mark.parametrize('status,body',[(200,{'error':{'code':502}}),
    (402,{'error':{'message':'credits exhausted'}}),
    (502,{'error':{'message':'insufficient quota'}}),(503,{'unexpected':'body'})])
def test_ambiguous_or_permanent_failure_not_retried(gateway,status,body):
    gateway.http.responses=[(status,body)]
    with pytest.raises(HTTPException):image(gateway)
    assert len(gateway.http.calls)==1


@pytest.mark.parametrize('hint',['30','invalid'])
def test_long_or_invalid_retry_hint_not_shortened(gateway,hint):
    gateway.http.responses=[httpx.Response(503,json={'error':{'message':'temporary'}},
        headers={'Retry-After':hint},request=httpx.Request('POST','https://provider.invalid'))]
    with pytest.raises(HTTPException):image(gateway)
    assert len(gateway.http.calls)==1


def test_image_total_wait_is_bounded(gateway):
    gateway.config.provider_timeout_seconds=.01
    async def slow(call):
        await asyncio.sleep(.1)
        return completion()
    gateway.http.responses=[slow]
    with pytest.raises(HTTPException) as exc:image(gateway)
    assert exc.value.status_code==504 and len(gateway.http.calls)==1
