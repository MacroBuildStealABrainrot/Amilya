from io import BytesIO
from PIL import Image
from sqlalchemy import select,func
from server import photo_collection,providers,quick_workflows
from server.clinical_models import Evidence,ClinicalFact
from test_clinical import clinical_api
from test_quick_workflows import quick_api,start

def test_ten_photos_group_reorder_remove_without_replacing_others(quick_api,monkeypatch):
    api=quick_api;api.client.app.include_router(photo_collection.router,prefix='/api/v1');id=start(api,'photo')['encounter']['id']
    async def analyze(*args,**kwargs):return {'facts':[{'name':'wound_edges','value':{'text':'Visible edge'},'quote':''}],'warnings':[]}
    monkeypatch.setattr(providers,'analyze_media',analyze)
    stream=BytesIO();Image.new('RGB',(32,32),(100,20,20)).save(stream,format='PNG')
    ids=[]
    for index in range(10):
        result=api.client.post(f'/api/v1/encounters/{id}/upload',files={'file':(f'photo-{index}.png',stream.getvalue(),'image/png')},data={'kind':'photo'},headers={'Idempotency-Key':f'image-{index}'})
        assert result.status_code==201,result.text
        ids.append(result.json()['evidence']['id'])
    assert len(set(ids))==10
    case=api.client.get(f'/api/v1/encounters/{id}').json()
    layout=[{'id':image,'label':'Overview' if index%2==0 else 'Close-up','group':'Right heel' if index<5 else 'Left ankle'}for index,image in enumerate(ids)]
    result=api.client.put(f'/api/v1/encounters/{id}/photo-layout',json={'expected_revision':case['encounter']['revision'],'photos':list(reversed(layout))})
    assert result.status_code==200,result.text
    changed=api.client.get(f'/api/v1/encounters/{id}').json()
    assert changed['encounter']['draft_data']['photo_layout']==list(reversed(layout))
    denied=api.client.delete(f'/api/v1/encounters/{id}/photos/{ids[0]}',headers={'X-Test-User':'u2'})
    assert denied.status_code==404
    assert api.client.delete(f'/api/v1/encounters/{id}/photos/{ids[0]}').status_code==200
    with api.sessions() as db:
        assert db.get(Evidence,ids[0]) is None
        assert db.scalar(select(func.count()).select_from(ClinicalFact).where(ClinicalFact.evidence_id==ids[0]))==0
        assert db.scalar(select(func.count()).select_from(Evidence).where(Evidence.encounter_id==id))==9
