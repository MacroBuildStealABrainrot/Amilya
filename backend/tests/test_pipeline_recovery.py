"""Fictional sources and synthetic pixels only; every provider call intercepted."""
import asyncio
import io
import json
import pytest
from fastapi import HTTPException
from PIL import Image
from server import providers
from test_clinical import clinical_api
from test_quick_workflows import quick_api, start, add_source
from test_providers import gateway, completion

@pytest.mark.parametrize('status',[402,429,502,503,504])
def test_failed_ai_report_recovers_from_original_not_rejected_draft(quick_api,monkeypatch,status):
    case=start(quick_api)
    text='Synthetic client reports pain 2 out of 10. Dressing changed. Medication name unclear.'
    add_source(quick_api,case,text)
    async def fail(*a,**k):raise HTTPException(status,'SYNTHETIC_PRIVATE_PROVIDER_TEXT')
    monkeypatch.setattr(providers,'quick_documentation',fail)
    response=quick_api.client.post(f"/api/v1/encounters/{case['encounter']['id']}/quick-report",json={'use_ai':True})
    assert response.status_code==200,response.text
    data=response.json()
    assert data['status']=='ready' and 'Dressing changed' in data['text']
    assert data['recovery']['mode']=='source_only' and data['qa']['passed'] is False
    assert data['review_required'] is True and data['ui_alerts']
    assert 'SYNTHETIC_PRIVATE_PROVIDER_TEXT' not in json.dumps(data)
    assert 'antibiotic administered' not in data['text']
    saved=quick_api.client.get(f"/api/v1/encounters/{case['encounter']['id']}").json()
    assert saved['evidence'][0]['text']==text and not saved['documents']

def test_authorization_failure_never_becomes_a_successful_draft(quick_api,monkeypatch):
    case=start(quick_api);add_source(quick_api,case)
    async def fail(*a,**k):raise HTTPException(401,'Sign in')
    monkeypatch.setattr(providers,'quick_documentation',fail)
    response=quick_api.client.post(f"/api/v1/encounters/{case['encounter']['id']}/quick-report",json={})
    assert response.status_code==401

def test_partial_photo_accepts_only_independently_valid_candidates():
    result=providers.validate_result({'quality':{'status':'sufficient','reason':'none'},'warnings':[],
        'facts':[{'name':'wound_edges','value':{'text':'Irregular visible edge'},'quote':''},
                 {'name':'periwound','value':{'observation':'unsupported key'},'quote':''},
                 {'name':'pain','value':{'present':True},'quote':''}]},'image')
    assert len(result['facts'])==1 and result['facts'][0]['name']=='wound_edges'
    assert result['quality']['retake_required'] is False
    assert 'unsupported key' not in json.dumps(result)
    assert result['warnings']

def png():
    output=io.BytesIO();Image.new('RGB',(32,32),'blue').save(output,format='PNG');return output.getvalue()

def assess(gateway):
    return asyncio.run(providers.analyze_media(png(),'image/png','photo',user=gateway.user,encounter_id='synthetic-photo-recovery'))

def test_invalid_photo_envelope_recovers_once_then_replays_without_more_calls(gateway):
    gateway.http.responses=[completion({'facts':[],'warnings':[]}),completion({'quality':{'status':'sufficient','reason':'none'},'facts':[{'name':'periwound','value':{'text':'Visible redness'},'quote':''}],'warnings':[]})]
    result=assess(gateway)
    assert result['facts'][0]['name']=='periwound' and len(gateway.http.calls)==2
    assert assess(gateway)['facts']==result['facts'] and len(gateway.http.calls)==2

def test_photo_recovery_is_bounded_and_never_fabricates_an_assessment(gateway):
    gateway.http.responses=[completion({'facts':[],'warnings':[]}),completion({'facts':[],'warnings':[]})]
    with pytest.raises(HTTPException) as error:assess(gateway)
    assert error.value.headers['X-WCP-Error']=='INVALID_RESPONSE'
    assert len(gateway.http.calls)==2

def test_provider_credit_error_does_not_start_schema_repair(gateway):
    gateway.http.responses=[(402,{'error':{'code':402}})]
    with pytest.raises(HTTPException):assess(gateway)
    assert len(gateway.http.calls)==1


def test_recovery_never_restores_rejected_findings(quick_api,monkeypatch):
    case=start(quick_api);source=add_source(quick_api,case)
    fact=source['facts'][0]
    assert quick_api.client.post(f"/api/v1/facts/{fact['id']}/review",json={'expected_revision':1,'action':'reject'}).status_code==200
    async def fail(*a,**k):raise HTTPException(502,'Synthetic QA rejection')
    monkeypatch.setattr(providers,'quick_documentation',fail)
    response=quick_api.client.post(f"/api/v1/encounters/{case['encounter']['id']}/quick-report",json={})
    assert response.status_code==200
    assert response.json()['text']=='' and response.json()['status']=='no_content'
    assert response.json()['fact_snapshot']==[]


def test_concurrent_source_edit_blocks_recovered_outdated_draft(quick_api,monkeypatch):
    from server.clinical_models import Encounter
    case=start(quick_api);add_source(quick_api,case)
    async def fail(*a,**k):
        with quick_api.sessions() as db:
            row=db.get(Encounter,case['encounter']['id']);row.revision+=1;db.commit()
        raise HTTPException(502,'Synthetic QA rejection')
    monkeypatch.setattr(providers,'quick_documentation',fail)
    response=quick_api.client.post(f"/api/v1/encounters/{case['encounter']['id']}/quick-report",json={})
    assert response.status_code==409 and 'Patient reports pain' not in response.text


def test_failure_categories_survive_metadata_sanitization():
    from server.billing import _safe_usage
    for category in ['invalid_response','provider_timeout','provider_connection','response_processing']:
        result=_safe_usage({'failure_reason':category,'transcript':'SYNTHETIC_PRIVATE_TEXT','api_key':'SYNTHETIC_SECRET'})
        assert result=={'failure_reason':category}
