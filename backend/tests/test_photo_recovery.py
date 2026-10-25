"""Synthetic images, intercepted provider requests, no clinical data."""
import asyncio
import io
import pytest
import httpx
from PIL import Image
from fastapi import HTTPException
from server import providers
from test_providers import gateway, completion, operation_rows

def photo():
    out=io.BytesIO()
    Image.new('RGB',(40,30),(40,100,150)).save(out,format='PNG')
    return out.getvalue()

def assess(gateway):
    return asyncio.run(providers.analyze_media(photo(),'image/png','photo',user=gateway.user,encounter_id='synthetic-encounter'))

def successful():
    return completion({'facts': [], 'warnings': [], 'quality': {'status':'insufficient','reason':'no_wound'}})

def test_photo_502_retry_reuses_source_then_replays_success(gateway):
    gateway.http.responses=[(502,{'error':{'code':502}}),successful()]
    with pytest.raises(HTTPException):assess(gateway)
    result=assess(gateway)
    assert result['quality']['reason']=='no_wound'
    assert assess(gateway)==result
    assert len(gateway.http.calls)==2
    assert gateway.http.calls[0]['json']==gateway.http.calls[1]['json']
    assert len(operation_rows(gateway))==2

@pytest.mark.parametrize('status',[400,401,402,403,404])
def test_permanent_photo_failure_never_spends_on_same_retry(gateway,status):
    gateway.http.responses=[(status,{'error':{'code':status}})]
    for _ in range(2):
        with pytest.raises(HTTPException):assess(gateway)
    assert len(gateway.http.calls)==1

@pytest.mark.parametrize('failure',[httpx.ReadTimeout('synthetic'),(200,{'choices':[]}), (200,{'error':{'code':502}}), (503,{'error':{'code':503}})])
def test_photo_failure_can_resume_without_retake(gateway,failure):
    gateway.http.responses=[failure,successful()]
    if failure == (200,{'choices':[]}):
        assert assess(gateway)['quality']['retake_required']
    else:
        with pytest.raises(HTTPException):assess(gateway)
        assert assess(gateway)['quality']['retake_required']
    assert len(gateway.http.calls)==2

def test_photo_retries_bounded(gateway):
    gateway.http.responses=[(502,{'error':{}})]*4
    for _ in range(8):
        with pytest.raises(HTTPException):assess(gateway)
    assert len(gateway.http.calls)==4

def test_photo_retry_after_is_not_shortened(gateway):
    gateway.http.responses=[httpx.Response(429,headers={'Retry-After':'90'},json={'error':{'message':'capacity'}},request=httpx.Request('POST','https://provider.invalid'))]
    for _ in range(2):
        with pytest.raises(HTTPException):assess(gateway)
    assert len(gateway.http.calls)==1

def test_invalid_photo_never_calls_provider(gateway):
    with pytest.raises(HTTPException) as error:
        asyncio.run(providers.analyze_media(b'not-an-image','image/jpeg','photo',user=gateway.user,encounter_id='synthetic-encounter'))
    assert error.value.status_code==415
    assert not gateway.http.calls

def test_two_photo_retries_reserve_only_one_child(gateway):
    gateway.http.responses=[(502,{'error':{}})]
    with pytest.raises(HTTPException):assess(gateway)
    async def scenario():
        started=asyncio.Event()
        release=asyncio.Event()
        async def response(_):
            started.set()
            await release.wait()
            return successful()
        gateway.http.responses=[response]
        first=asyncio.create_task(providers.analyze_media(photo(),'image/png','photo',user=gateway.user,encounter_id='synthetic-encounter'))
        await started.wait()
        try:
            with pytest.raises(HTTPException) as error:
                await providers.analyze_media(photo(),'image/png','photo',user=gateway.user,encounter_id='synthetic-encounter')
            assert error.value.status_code==409
        finally:release.set()
        await first
    asyncio.run(scenario())
    assert len(gateway.http.calls)==2
    assert len(operation_rows(gateway))==2

def test_photo_capacity_retry_and_quota_classified_differently(gateway):
    gateway.http.responses=[(429,{'error':{'message':'quota exhausted'}})]
    with pytest.raises(HTTPException) as error:assess(gateway)
    assert error.value.headers['X-WCP-Error']=='PROVIDER_QUOTA'
    assert error.value.headers['X-WCP-Retryable']=='false'
    with pytest.raises(HTTPException):assess(gateway)
    assert len(gateway.http.calls)==1
