"""Documentation contract regressions: synthetic input only, no live provider calls."""
import asyncio
from copy import deepcopy
import json
import pytest
from fastapi import HTTPException
from server import clinical, documentation as doc, providers
from test_quick_workflows import quick_api, start, add_source
from test_clinical import clinical_api
from test_providers import gateway, completion, operation_rows

VISIT = '''Elderly female with blood-filled blister to right foot.
Started approximately 4 days ago.
Client does not recall trauma or known cause.
Reports severe soreness/pain.
Right leg warmer than left.
Wound cleansed with NS.
Betadine applied.
Mesorb and cling applied.'''

def context(text):
    return {'case_facts': [], 'untrusted_sources': [{'id':'source', 'kind':'typed','text':text}], 'rejected_claims_do_not_reinstate':[]}

def source(text, template='nursing'):
    return doc.source_report(context(text),template,clinical.fact_phrase)

@pytest.mark.parametrize('template', ['nursing','soap','sbar','handoff','physician_update','referral'])
def test_reference_visit_preserves_findings_without_invented_clinical_content(template):
    result=source(VISIT,template)
    assert result['status']=='ready'
    for line in VISIT.splitlines(): assert line in result['text']
    for invented in ['cellulitis','infection','No drainage','No fever','Continue','follow-up','not recorded','CLINICIAN REVIEW','Recorded account']:
        assert invented not in result['text']
    assert not doc.quality_defects(result['text'],context(VISIT))
    assert 'Plan\n' not in result['text'] and 'Recommendation\n' not in result['text']

@pytest.mark.parametrize('text,expected', [
    ('Client reports pain 6/10.','Subjective\nClient reports pain 6/10.'),
    ('Client denies pain.','Subjective\nClient denies pain.'),
])
def test_sparse_soap_omits_unsupported_sections(text,expected):
    assert source(text,'soap')['text']==expected

@pytest.mark.parametrize('text', ['Client denies pain. No drainage observed.', 'Unable to measure due to pain.', 'Client declined assessment.', 'Client thinks this began approximately 4 days ago.'])
def test_explicit_negatives_limits_and_uncertainty_are_preserved(text):
    assert source(text)['text']==text
    assert doc.quality_defects(text,context(text))==[]

@pytest.mark.parametrize('text', ['Unknown','N/A','Not assessed','Hello.','No measurements were recorded.'])
def test_empty_content_is_ui_only(text):
    result=source(text)
    assert result['text']=='' and result['ui_message']==doc.NO_CONTENT

@pytest.mark.parametrize('text', ['Wound cleansed with NS. Mesorb applied.','Dressing changed as planned.'])
def test_completed_care_is_not_a_future_plan(text):
    for template in ['soap','sbar','handoff']:
        report=source(text,template)['text']
        assert not any(heading in report for heading in ['Plan\n','Recommendation\n','Next steps\n'])

@pytest.mark.parametrize('text', [VISIT,'Refer to clinic.','No referral for non-healing wound.','Referral for pain declined.'])
def test_referral_requires_intended_request_and_reason(text):
    result=source(text,'referral_letter')
    assert result['text']=='' and result['ui_message']==doc.NO_REFERRAL

def test_referral_with_explicit_reason_and_old_physician_alias():
    text='Refer to wound clinic for non-healing right foot ulcer.'
    assert source(text,'referral_letter')['status']=='ready'
    assert source(VISIT,'referral')['text']==source(VISIT,'physician_update')['text']

@pytest.mark.parametrize('text', ['Subjective\nPain 6/10.\nObjective\nAssessment\nPlan', 'Draft — Clinician Review Required\nPain 6/10.', 'Recorded account states pain 6/10.', 'Assessment: N/A'])
def test_structure_defects_are_caught_before_display(text):
    assert doc.quality_defects(text)


def test_original_visit_retained_across_formats_and_optional_tools(quick_api,monkeypatch):
    case=start(quick_api);add_source(quick_api,case,VISIT);id=case['encounter']['id']
    before=quick_api.client.get(f'/api/v1/encounters/{id}').json();rev=before['encounter']['revision'];seen=[]
    async def generate(context,template,*args,**kwargs):
        seen.append(deepcopy(context))
        return {'text':VISIT,'status':'ready','ui_alerts':[]}
    monkeypatch.setattr(providers,'quick_documentation',generate)
    for path in ['quick-report','draft']:
        for template in doc.DOCUMENT_TYPES:
            response=quick_api.client.post(f'/api/v1/encounters/{id}/{path}',json={'template':template,'use_ai':True})
            assert response.status_code==200,response.text
    assert all(item==seen[0] for item in seen)
    async def routed(task,content,**kwargs):
        assert task=='improve_assessment'
        return {'answer':'Clarify the duration of pain.', 'considerations':['Has the pain changed since onset?']}
    monkeypatch.setattr(providers,'routed',routed)
    response=quick_api.client.post(f'/api/v1/encounters/{id}/improve-assessment',json={'expected_revision':rev})
    assert response.status_code==200 and 'text' not in response.json()
    after=quick_api.client.get(f'/api/v1/encounters/{id}').json()
    for key in ['facts','evidence','documents']:assert before[key]==after[key]
    assert after['encounter']['revision']==rev
    assert quick_api.client.post(f'/api/v1/encounters/{id}/improve-assessment',json={'expected_revision':rev+1}).status_code==409
    assert quick_api.client.post(f'/api/v1/encounters/{id}/improve-assessment',json={'expected_revision':rev},headers={'X-Test-User':'u2'}).status_code==404


def test_improve_report_exact_source_context_and_bounded_equivalence_repair(gateway):
    existing='Client reports pain 6/10.'
    gateway.http.responses=[completion({'text':existing+' Betadine applied.','status':'ready','ui_alerts':[]}),completion({'passed':False,'discrepancies':['Added treatment was absent from the original report.']}),completion({'text':'Pain reported as 6/10.','status':'ready','ui_alerts':[]}),completion({'passed':True,'discrepancies':[]})]
    result=asyncio.run(providers.checked_quick_text(context(VISIT),'improve_report',{'template':'nursing','untrusted_existing_report':existing},user=gateway.user,encounter_id='improve-synthetic'))
    assert result['text']=='Pain reported as 6/10.'
    assert len(gateway.http.calls)==4
    for call in gateway.http.calls:
        data=json.loads(call['json']['messages'][1]['content'])
        assert data['untrusted_existing_report']==existing
        assert data['untrusted_sources']==context(VISIT)['untrusted_sources']
        assert data['document_task']=='improve_report'
    assert len(operation_rows(gateway))==4


def test_layout_cleanup_precedes_semantic_qa_and_preserves_clinical_negatives(gateway):
    bad='Draft — Clinician Review Required\nNo wound measurements were recorded. No fever recorded. Reassessment planned tomorrow.'
    gateway.http.responses=[completion({'text':bad}),completion({'passed':True,'discrepancies':[]})]
    result=asyncio.run(providers.quick_documentation(context('No fever recorded. No wound measurements were recorded. Reassessment planned tomorrow.'),'nursing','concise',user=gateway.user,encounter_id='style-synthetic'))
    assert result['text']=='No fever recorded. Reassessment planned tomorrow.'
    request=json.loads(gateway.http.calls[1]['json']['messages'][1]['content'])
    assert request['proposed_draft']==result['text']
    assert len(gateway.http.calls)==2


def test_erroneous_missing_information_repair_is_cleaned_before_recheck(gateway):
    gateway.http.responses=[completion({'text':'Reassessment planned tomorrow.'}),completion({'passed':False,'discrepancies':['Include that no wound measurements were recorded.']}),completion({'text':'No wound measurements were recorded. Reassessment planned tomorrow.'}),completion({'passed':True,'discrepancies':[]})]
    result=asyncio.run(providers.quick_documentation(context('No wound measurements in this recording. Reassessment planned tomorrow.'),'nursing','concise',user=gateway.user,encounter_id='missing-synthetic'))
    assert result['text']=='Reassessment planned tomorrow.'
    assert json.loads(gateway.http.calls[3]['json']['messages'][1]['content'])['proposed_draft']==result['text']
    assert len(gateway.http.calls)==4



def test_no_content_provider_result_is_checked_and_never_stored_as_report(gateway):
    gateway.http.responses=[completion({'text':'','status':'no_content','ui_alerts':[]}),completion({'passed':True,'discrepancies':[]})]
    result=asyncio.run(providers.quick_documentation(context('Hello.'),'soap','concise',user=gateway.user,encounter_id='empty-synthetic'))
    assert doc.visible_result(result)['ui_message']==doc.NO_CONTENT
    assert result['text']=='' and result['qa']['passed']


def test_ui_alerts_stay_outside_saved_document_and_copy_contract(quick_api,monkeypatch):
    case=start(quick_api);add_source(quick_api,case,'Pain 6/10.');id=case['encounter']['id']
    async def generate(*args,**kwargs):return {'text':'Pain 6/10.','status':'ready','ui_alerts':[{'field':'speaker','message':'Confirm who reported the pain.'}]}
    monkeypatch.setattr(providers,'quick_documentation',generate)
    result=quick_api.client.post(f'/api/v1/encounters/{id}/quick-report',json={}).json()
    saved=quick_api.client.post(f'/api/v1/encounters/{id}/documents',json={'text':result['text'],'template':'nursing','expected_revision':result['case_revision']})
    assert saved.status_code==201,saved.text
    assert saved.json()['text']=='Pain 6/10.'
    assert result['ui_alerts'][0]['message'] not in saved.json()['text']


def test_unaccepted_audio_verification_cannot_enter_document_sources(quick_api,monkeypatch):
    from uuid import uuid4
    from server.clinical_models import Encounter,Evidence
    case=start(quick_api);add_source(quick_api,case,'Client denies pain.');id=case['encounter']['id'];proposal_id=str(uuid4())
    with quick_api.sessions() as db:
        visit=db.get(Encounter,id)
        db.add(Evidence(id=proposal_id,workspace_id=visit.workspace_id,encounter_id=id,kind='audio_verification',text='UNACCEPTED CANDIDATE: pain 9/10.',warnings=['Unaccepted verification candidate.'],created_by=visit.created_by,request_hash='synthetic-verification-fixture'))
        db.commit()
    seen=[]
    async def generate(context,*args,**kwargs):
        seen.append(context)
        return {'text':'Client denies pain.','status':'ready','ui_alerts':[]}
    monkeypatch.setattr(providers,'quick_documentation',generate)
    report=quick_api.client.post(f'/api/v1/encounters/{id}/quick-report',json={})
    assert report.status_code==200,report.text
    assert proposal_id not in report.json()['source_ids']
    assert 'UNACCEPTED CANDIDATE' not in json.dumps(seen)
    assert any(e['id']==proposal_id for e in quick_api.client.get(f'/api/v1/encounters/{id}').json()['evidence'])


@pytest.mark.parametrize('future', ['', 'Review tomorrow.'])
def test_soap_completed_care_moved_without_changing_words(future):
    text='Subjective\nClient reports pain.\n\nObjective\nRight foot blister.\n\nPlan\nWound cleansed with NS. Mesorb applied.'+(' '+future if future else '')
    clean=doc.clean_generated_layout({'text':text,'status':'ready','ui_alerts':[]},{'template':'soap'})['text']
    objective=clean.split('Objective:\n',1)[1].split('\n\nPlan',1)[0]
    assert all(sentence in objective for sentence in ['Right foot blister.','Wound cleansed with NS.','Mesorb applied.'])
    if future:assert 'Plan:\nReview tomorrow.' in clean
    else:assert 'Plan' not in clean
    assert doc.place_completed_care_in_objective(clean)==clean
