"""Clinician-assigned photo grouping; never infer that two photos show one wound."""
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select, delete, text
from sqlalchemy.orm import Session
from server import clinical
from server.clinical_models import Encounter, Evidence, ClinicalFact, FactRevision, ClinicalReview
from server.db import get_db
from server.identity import User, current_user, require_csrf
from server.provider_models import ProviderResult
from server.billing_models import UsageOperation

router=APIRouter(tags=['photo collection'])
class PhotoItem(BaseModel):
    model_config=ConfigDict(extra='forbid')
    id:UUID
    label:str=Field(default='',max_length=120)
    group:str=Field(min_length=1,max_length=80)

class PhotoLayout(BaseModel):
    model_config=ConfigDict(extra='forbid')
    expected_revision:int=Field(ge=1)
    photos:list[PhotoItem]=Field(max_length=20)

@router.put('/encounters/{encounter_id}/photo-layout',dependencies=[Depends(require_csrf)])
def update_layout(encounter_id:str,payload:PhotoLayout,user:User=Depends(current_user),db:Session=Depends(get_db)):
    encounter=clinical.owned(db,Encounter,encounter_id,user)
    ids=[str(p.id) for p in payload.photos]
    existing=list(db.scalars(select(Evidence.id).where(Evidence.encounter_id==encounter_id,Evidence.kind=='photo')))
    if len(set(ids))!=len(ids) or set(ids)!=set(existing):raise HTTPException(409,'The photo collection changed. Refresh it before arranging photos.')
    clinical.lock_case(db,encounter,payload.expected_revision,draft_data={**encounter.draft_data,'photo_layout':[p.model_dump(mode='json') for p in payload.photos]})
    clinical.timeline(db,user,encounter_id,'photos.arranged',encounter_id,{'count':len(ids)})
    clinical.finish(db)
    return {'case_revision':encounter.revision}

@router.delete('/encounters/{encounter_id}/photos/{photo_id}',dependencies=[Depends(require_csrf)])
def remove_photo(encounter_id:str,photo_id:str,user:User=Depends(current_user),db:Session=Depends(get_db)):
    encounter=clinical.owned(db,Encounter,encounter_id,user)
    photo=clinical.owned(db,Evidence,photo_id,user)
    if photo.encounter_id!=encounter_id or photo.created_by!=user.id or photo.kind!='photo':raise HTTPException(404,'Photo not found.')
    clinical.lock_case(db,encounter,encounter.revision,draft_data={**encounter.draft_data,'photo_layout':[p for p in encounter.draft_data.get('photo_layout',[]) if p.get('id')!=photo_id]})
    if db.scalar(select(UsageOperation.id).where(UsageOperation.encounter_id==encounter_id,UsageOperation.status=='reserved').limit(1)):
        raise HTTPException(409,'This work is still processing. Wait before removing its photo.')
    if db.get_bind().dialect.name=='postgresql':
        db.execute(text('SELECT public.wcp_erase_work(:id,:workspace,:actor,:photo)'),{'id':encounter_id,'workspace':user.workspace_id,'actor':user.id,'photo':photo_id})
        clinical.timeline(db,user,encounter_id,'photo.erased',photo_id);clinical.finish(db)
        return {'status':'deleted','case_revision':encounter.revision}
    facts=select(ClinicalFact.id).where(ClinicalFact.evidence_id==photo_id)
    db.execute(delete(FactRevision).where(FactRevision.fact_id.in_(facts)))
    db.execute(delete(ClinicalFact).where(ClinicalFact.evidence_id==photo_id))
    db.execute(delete(ClinicalReview).where(ClinicalReview.encounter_id==encounter_id))
    operations=select(UsageOperation.operation_id).where(UsageOperation.encounter_id==encounter_id,UsageOperation.workspace_id==user.workspace_id)
    db.execute(delete(ProviderResult).where(ProviderResult.workspace_id==user.workspace_id,ProviderResult.operation_id.in_(operations)))
    db.execute(delete(Evidence).where(Evidence.id==photo_id))
    clinical.timeline(db,user,encounter_id,'photo.erased',photo_id)
    clinical.finish(db)
    return {'status':'deleted','case_revision':encounter.revision,'saved_reports':'Review previously saved or exported wording separately.'}
