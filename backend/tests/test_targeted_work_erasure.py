from sqlalchemy import select
from server import work_privacy
from server.clinical_models import ClinicalDocument,Encounter
from server.transfer_models import ReportTransfer
from test_transfers import api,create,redeem

def test_deleting_one_work_preserves_unrelated_transfer(api):
    api.app.include_router(work_privacy.router,prefix='/api/v1')
    first,token1=create(api)
    with api.factory() as db:
        second=Encounter(workspace_id=api.encounter.workspace_id,patient_id=api.encounter.patient_id,title='Other synthetic work',created_by=api.user['id'])
        db.add(second);db.flush()
        doc=ClinicalDocument(workspace_id=second.workspace_id,encounter_id=second.id,text='Other exact synthetic report.',template='nursing',version=1,case_revision=1,fact_snapshot=[],request_hash='a'*64,content_hash='b'*64,created_by=api.user['id'])
        db.add(doc);db.commit()
        other_id=doc.id
    other,token2=create(api,[other_id])
    result=api.client.request('DELETE',f'/api/v1/work/{api.encounter.id}',json={'confirm':True},headers=api.headers)
    assert result.status_code==200,result.text
    assert redeem(api,token1,first['code']).status_code==410
    assert redeem(api,token2,other['code']).status_code==200
    with api.factory() as db:
        assert db.get(ClinicalDocument,api.document.id) is None
        assert db.get(ClinicalDocument,other_id) is not None
