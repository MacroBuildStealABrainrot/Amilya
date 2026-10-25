import pytest
from fastapi import HTTPException
from server import clinical
from test_clinical import clinical_api, create_case

@pytest.mark.parametrize('status,headers,expected',[
    (503,{'X-WCP-Error':'PROVIDER_AUTH','X-WCP-Upstream-Status':'401'},'PROVIDER_AUTH'),
    (504,{'X-WCP-Error':'PROVIDER_TIMEOUT'},'PROVIDER_TIMEOUT'),
    (402,{},'APP_AI_ACCESS_REQUIRED'),
])
def test_warning_and_source_survive_error_and_replay(clinical_api,monkeypatch,status,headers,expected):
    calls=[]
    async def fail(*a,**k):
        calls.append(1)
        raise HTTPException(status,'SYNTHETIC_PRIVATE_FAILURE',headers=headers)
    monkeypatch.setattr(clinical.providers,'extract_text',fail)
    case=create_case(clinical_api)
    path=f'/api/v1/encounters/{case["id"]}/evidence'
    data={'kind':'typed','text':'Synthetic client denies fever.'}
    response=clinical_api.client.post(path,json=data,headers={'Idempotency-Key':'synthetic-specific-error'})
    assert response.status_code==201,response.text
    result=response.json()
    assert result['evidence']['text']==data['text']
    assert expected in ' '.join(result['warnings'])
    assert 'SYNTHETIC_PRIVATE_FAILURE' not in ' '.join(result['warnings'])
    replay=clinical_api.client.post(path,json=data,headers={'Idempotency-Key':'synthetic-specific-error'})
    assert replay.status_code==201
    assert replay.json()['evidence']['id']==result['evidence']['id']
    assert expected in ' '.join(replay.json()['warnings'])
    assert len(calls)==1
