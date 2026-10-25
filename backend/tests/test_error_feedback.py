import asyncio
import pytest
from fastapi import HTTPException
from server import providers
from server.error_feedback import extraction_warning, preflight_error
from test_providers import gateway

@pytest.mark.parametrize('status,code', [(400,'REQUEST_INCOMPATIBLE'),(401,'PROVIDER_AUTH'),(402,'PROVIDER_QUOTA'),(403,'PROVIDER_AUTH'),(404,'MODEL_UNAVAILABLE'),(502,'PROVIDER_UNAVAILABLE')])
def test_extraction_preserves_specific_failure_and_replay(gateway,status,code):
    gateway.http.responses=[(status, {'error': {'message':'SYNTHETIC_PRIVATE_SOURCE sk-secret-never-display'}})]
    for _ in range(2):
        with pytest.raises(HTTPException) as caught:
            asyncio.run(providers.structured('extract','synthetic source',gateway.config.model_extraction,user=gateway.user,encounter_id='synthetic-error'))
        error=caught.value
        assert error.headers['X-WCP-Error']==code
        assert error.headers['X-WCP-Upstream-Status']==str(status)
        warning=extraction_warning(error)
        assert code in warning and f'provider HTTP {status}' in warning
        assert 'SYNTHETIC_PRIVATE_SOURCE' not in warning and 'sk-secret' not in warning
    assert len(gateway.http.calls)==1

@pytest.mark.parametrize('detail,code',[
    ('Live AI is paused until the owner activates provider execution','AI_PAUSED'),
    ('AI is not configured; manual evidence and documentation are available','AI_NOT_CONFIGURED'),
    ('This provider is awaiting clinical-data privacy approval','PROVIDER_APPROVAL_REQUIRED'),
    ('Zero-retention routing must be enabled','PRIVACY_CONFIGURATION_REQUIRED'),
])
def test_preflight_explains_configuration(detail,code):
    error=preflight_error(503,detail)
    assert error.headers['X-WCP-Error']==code
    assert error.headers['X-WCP-Retryable']=='false'
    assert code in extraction_warning(error)

def test_unknown_failure_does_not_invent_cause_or_expose_body():
    result=extraction_warning(HTTPException(503,'SYNTHETIC_PRIVATE_SOURCE sk-secret'))
    assert 'EXTRACTION_UNCLASSIFIED' in result
    assert 'SYNTHETIC_PRIVATE_SOURCE' not in result and 'sk-secret' not in result

def test_app_credits_distinct_from_provider_credits():
    assert 'APP_AI_ACCESS_REQUIRED' in extraction_warning(HTTPException(402,'untrusted'))
    assert 'PROVIDER_QUOTA' in extraction_warning(HTTPException(503,'untrusted',headers={'X-WCP-Error':'PROVIDER_QUOTA'}))

def test_http200_error_envelope_does_not_invent_http_status(gateway):
    gateway.http.responses=[(200,{'error':{'code':402,'message':'synthetic private'}})]
    with pytest.raises(HTTPException) as caught:
        asyncio.run(providers.structured('extract','synthetic source',gateway.config.model_extraction,user=gateway.user,encounter_id='synthetic-envelope'))
    assert caught.value.headers['X-WCP-Error']=='PROVIDER_QUOTA'
    assert caught.value.headers['X-WCP-Upstream-Status']=='200'
    assert 'provider HTTP 200' in extraction_warning(caught.value)
    with pytest.raises(HTTPException) as replay:
        asyncio.run(providers.structured('extract','synthetic source',gateway.config.model_extraction,user=gateway.user,encounter_id='synthetic-envelope'))
    assert replay.value.headers['X-WCP-Upstream-Status']=='200'
    assert replay.value.headers['X-WCP-Error']=='PROVIDER_QUOTA'
    assert len(gateway.http.calls)==1
