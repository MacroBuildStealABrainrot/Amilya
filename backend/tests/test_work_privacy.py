from datetime import timedelta
from sqlalchemy import select, func
from server import work_privacy, photo_collection
from server.clinical_models import Encounter,Evidence,ClinicalFact,FactRevision,ClinicalDocument
from server.db import utcnow
from test_clinical import clinical_api
from test_quick_workflows import quick_api,start,add_source

def mount(api):
    api.client.app.include_router(work_privacy.router,prefix='/api/v1')
    api.client.app.include_router(photo_collection.router,prefix='/api/v1')

def test_unsaved_work_is_temporary_and_close_erases_records(quick_api):
    api=quick_api;mount(api);case=start(api);id=case['encounter']['id'];add_source(api,case)
    assert api.client.get('/api/v1/work').json()[0]['storage_state']=='temporary'
    assert api.client.post(f'/api/v1/work/{id}/close').json()['status']=='deleted'
    with api.sessions() as db:
        for model in (Encounter,Evidence,ClinicalFact,FactRevision):assert db.scalar(select(func.count()).select_from(model))==0

def test_save_is_explicit_and_close_does_not_delete(quick_api):
    api=quick_api;mount(api);case=start(api);id=case['encounter']['id']
    assert api.client.post(f'/api/v1/work/{id}/save').status_code==200
    assert api.client.get('/api/v1/work').json()[0]['storage_state']=='saved'
    assert api.client.post(f'/api/v1/work/{id}/close').json()['status']=='saved'
    denied=api.client.request('DELETE',f'/api/v1/work/{id}',json={'confirm':True},headers={'X-Test-User':'u2'})
    assert denied.status_code==404
    assert api.client.request('DELETE',f'/api/v1/work/{id}',json={'confirm':False}).status_code==422
    assert api.client.request('DELETE',f'/api/v1/work/{id}',json={'confirm':True}).status_code==200

def test_only_unsaved_new_policy_work_expires(quick_api):
    api=quick_api;mount(api)
    ids=[start(api)['encounter']['id'] for _ in range(3)]
    with api.sessions() as db:
        for index,id in enumerate(ids):
            row=db.get(Encounter,id);row.updated_at=utcnow()-timedelta(days=8)
            if index==1:row.draft_data={'retention':'saved'}
            if index==2:row.draft_data={}
        db.commit()
    rows=api.client.get('/api/v1/work').json()
    assert {r['id'] for r in rows}==set(ids[1:])
    assert {r['storage_state'] for r in rows}=={'saved','legacy'}

def test_delete_all_saved_does_not_delete_recovery(quick_api):
    api=quick_api;mount(api);one=start(api)['encounter']['id'];two=start(api)['encounter']['id']
    api.client.post(f'/api/v1/work/{one}/save')
    result=api.client.post('/api/v1/work/delete-all-saved',json={'confirm':True})
    assert result.status_code==200 and result.json()['ids']==[one]
    assert [r['id'] for r in api.client.get('/api/v1/work').json()]==[two]
