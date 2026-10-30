"""Explicit work retention and erasure, separate from immutable chart editing."""
from datetime import timedelta
import json
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict
from sqlalchemy import select, delete, update, text
from sqlalchemy.orm import Session
from server.db import get_db, utcnow, aware
from server.identity import current_user, require_csrf, User
from server.clinical_models import Encounter, Evidence, ClinicalFact, FactRevision, ClinicalReview, ClinicalDocument, DocumentSignature, TimelineEvent
from server.provider_models import ProviderResult
from server.billing_models import UsageOperation
from server.transfer_models import ReportTransfer
from server.audit import write_audit

router=APIRouter(tags=['work privacy'])
RECOVERY_DAYS=7

class EraseRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    confirm: bool

def owned_work(db,identifier,user):
    row=db.scalar(select(Encounter).where(Encounter.id==identifier,Encounter.workspace_id==user.workspace_id,Encounter.created_by==user.id).with_for_update())
    if not row: raise HTTPException(404,'Work not found.')
    return row

def saved(db,row):
    return row.draft_data.get('retention')=='saved' or bool(db.scalar(select(ClinicalDocument.id).where(ClinicalDocument.encounter_id==row.id).limit(1)))


def affected_transfers(db,row):
    identifiers=set(db.scalars(select(ClinicalDocument.id).where(ClinicalDocument.encounter_id==row.id)))
    if not identifiers:return []
    from server.transfers import cipher
    from cryptography.fernet import InvalidToken
    crypt=cipher();affected=[]
    for transfer in db.scalars(select(ReportTransfer).where(ReportTransfer.workspace_id==row.workspace_id,ReportTransfer.created_by==row.created_by,ReportTransfer.ciphertext.is_not(None))):
        try:
            snapshot=json.loads(crypt.decrypt(transfer.ciphertext))
            if any(doc.get('id') in identifiers for doc in snapshot.get('documents',[])):affected.append(transfer.id)
        except (InvalidToken,ValueError,TypeError):
            # An unreadable capability cannot be redeemed safely either.
            affected.append(transfer.id)
    return affected

def erase_work(db,row):
    """SQL erasure is only reachable through explicit privacy deletion/expiry.
    Ordinary immutable-record protections remain in force elsewhere.
    Billing and audit keep metadata, never the clinical payload.
    """
    identifier=row.id
    if db.scalar(select(UsageOperation.id).where(UsageOperation.encounter_id==identifier,UsageOperation.status=='reserved').limit(1)):
        raise HTTPException(409,'This work is still processing. Wait for it to finish before deleting.')
    transfers=affected_transfers(db,row)
    # A waiting office-computer copy of this visit goes with it.
    from server.desk_models import DeskNote
    db.execute(delete(DeskNote).where(DeskNote.encounter_id==identifier,DeskNote.created_by==row.created_by))
    if db.get_bind().dialect.name=='postgresql':
        db.execute(text("SELECT set_config('wcp.erasure_transfers',:ids,true)"),{'ids':','.join(transfers)})
        db.execute(text('SELECT public.wcp_erase_work(:id,:workspace,:actor,NULL)'),{'id':identifier,'workspace':row.workspace_id,'actor':row.created_by})
        return
    # Transfers are encrypted snapshots without a searchable document FK. Revoke
    # this author's outstanding transfers so no deleted report remains redeemable.
    if transfers:db.execute(update(ReportTransfer).where(ReportTransfer.id.in_(transfers)).values(ciphertext=None,pin_hash=None,revoked_at=utcnow()))
    operations=select(UsageOperation.operation_id).where(UsageOperation.encounter_id==identifier,UsageOperation.workspace_id==row.workspace_id)
    db.execute(delete(ProviderResult).where(ProviderResult.workspace_id==row.workspace_id,ProviderResult.operation_id.in_(operations)))
    documents=select(ClinicalDocument.id).where(ClinicalDocument.encounter_id==identifier)
    db.execute(delete(DocumentSignature).where(DocumentSignature.document_id.in_(documents)))
    # Remove later amendments before their parents, satisfying the self-reference.
    ids=list(db.scalars(select(ClinicalDocument.id).where(ClinicalDocument.encounter_id==identifier).order_by(ClinicalDocument.version.desc())))
    for doc_id in ids:db.execute(delete(ClinicalDocument).where(ClinicalDocument.id==doc_id))
    db.execute(delete(FactRevision).where(FactRevision.fact_id.in_(select(ClinicalFact.id).where(ClinicalFact.encounter_id==identifier))))
    for model in (ClinicalFact,ClinicalReview,Evidence,TimelineEvent):
        db.execute(delete(model).where(model.encounter_id==identifier))
    db.execute(delete(Encounter).where(Encounter.id==identifier))

def expire_work(db,user,limit=100):
    rows=list(db.scalars(select(Encounter).where(Encounter.workspace_id==user.workspace_id,Encounter.created_by==user.id,Encounter.updated_at<utcnow()-timedelta(days=RECOVERY_DAYS)).limit(limit)))
    count=0
    for row in rows:
        # Legacy records are not silently discarded: they predate explicit consent.
        if row.draft_data.get('retention')=='temporary' and not saved(db,row):
            erase_work(db,row);count+=1
    return count

@router.get('/work')
def list_work(user:User=Depends(current_user),db:Session=Depends(get_db)):
    from server.clinical import encounter_out
    expire_work(db,user);db.commit()
    result=[]
    for row in db.scalars(select(Encounter).where(Encounter.workspace_id==user.workspace_id,Encounter.created_by==user.id).order_by(Encounter.updated_at.desc()).limit(500)):
        state='saved' if saved(db,row) else 'temporary' if row.draft_data.get('retention')=='temporary' else 'legacy'
        result.append({**encounter_out(row),'storage_state':state})
    return result

@router.post('/work/{identifier}/save',dependencies=[Depends(require_csrf)])
def save_work(identifier:str,user:User=Depends(current_user),db:Session=Depends(get_db)):
    row=owned_work(db,identifier,user)
    row.draft_data={**row.draft_data,'retention':'saved'}
    write_audit(db,user,'work.save',identifier);db.commit()
    return {'status':'saved'}

@router.post('/work/{identifier}/close',dependencies=[Depends(require_csrf)])
def close_work(identifier:str,user:User=Depends(current_user),db:Session=Depends(get_db)):
    row=owned_work(db,identifier,user)
    if saved(db,row):return {'status':'saved'}
    if row.draft_data.get('retention')!='temporary':return {'status':'legacy_review_required'}
    erase_work(db,row);write_audit(db,user,'work.temporary_erased',identifier);db.commit()
    return {'status':'deleted'}

@router.delete('/work/{identifier}',dependencies=[Depends(require_csrf)])
def delete_work(identifier:str,payload:EraseRequest,user:User=Depends(current_user),db:Session=Depends(get_db)):
    if not payload.confirm:raise HTTPException(422,'Confirm deletion.')
    row=owned_work(db,identifier,user);erase_work(db,row)
    write_audit(db,user,'work.erased',identifier);db.commit()
    return {'status':'deleted','backup_erasure':'not_guaranteed','external_copies':'outside_app_control'}

@router.post('/work/delete-all-saved',dependencies=[Depends(require_csrf)])
def delete_saved(payload:EraseRequest,user:User=Depends(current_user),db:Session=Depends(get_db)):
    if not payload.confirm:raise HTTPException(422,'Confirm deletion.')
    rows=list(db.scalars(select(Encounter).where(Encounter.workspace_id==user.workspace_id,Encounter.created_by==user.id).with_for_update()))
    identifiers=[]
    for row in rows:
        if saved(db,row):identifiers.append(row.id);erase_work(db,row)
    write_audit(db,user,'work.saved_erased',user.id,{'count':len(identifiers)});db.commit()
    return {'status':'deleted','ids':identifiers,'backup_erasure':'not_guaranteed'}
