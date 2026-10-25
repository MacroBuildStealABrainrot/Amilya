from __future__ import annotations

import hashlib,json
from datetime import datetime
from fastapi import HTTPException
from sqlalchemy import DateTime,ForeignKey,Integer,JSON,String,UniqueConstraint,select,update
from sqlalchemy.orm import Mapped,mapped_column
from server.db import Base,uid,utcnow,aware
from server.identity import Workspace


class AuditEvent(Base):
    __tablename__='audit_events'
    __table_args__=(UniqueConstraint('workspace_id','sequence'),)
    id:Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    workspace_id:Mapped[str]=mapped_column(ForeignKey('workspaces.id'),index=True)
    user_id:Mapped[str]=mapped_column(ForeignKey('users.id'))
    sequence:Mapped[int]=mapped_column(Integer)
    action:Mapped[str]=mapped_column(String(100))
    resource_id:Mapped[str]=mapped_column(String(100),index=True)
    detail:Mapped[dict]=mapped_column(JSON,default=dict)
    previous_hash:Mapped[str]=mapped_column(String(64))
    event_hash:Mapped[str]=mapped_column(String(64))
    hash_version:Mapped[int]=mapped_column(Integer,default=2)
    created_at:Mapped[datetime]=mapped_column(DateTime(timezone=True),default=utcnow)


def write_audit(db,user,action,resource_id,detail=None):
    # Flush caller's business mutation first, then serialize each workspace's chain head.
    db.flush()
    workspace=db.scalar(select(Workspace).where(Workspace.id==user.workspace_id).with_for_update().execution_options(populate_existing=True))
    if not workspace:raise HTTPException(404,'Workspace not found')
    seq=workspace.audit_sequence+1;previous=workspace.audit_head
    # Detail must be metadata, never raw clinical content or secrets.
    data=detail or {}
    forbidden={'text','transcript','password','api_key','token','secret','note','image','audio'}
    if any(str(k).lower() in forbidden for k in data):raise ValueError('Clinical content/secrets cannot be stored in audit metadata')
    record={'id':uid(),'workspace_id':user.workspace_id,'user_id':user.id,'sequence':seq,'action':action,'resource_id':str(resource_id),'detail':data,'previous_hash':previous,'hash_version':2,'created_at':utcnow()}
    canonical={**record,'created_at':record['created_at'].isoformat(timespec='microseconds')}
    event_hash=hashlib.sha256(json.dumps(canonical,sort_keys=True,separators=(',',':'),default=str).encode()).hexdigest()
    result=db.execute(update(Workspace).where(Workspace.id==workspace.id,Workspace.audit_sequence==seq-1).values(audit_sequence=seq,audit_head=event_hash))
    if result.rowcount!=1:raise HTTPException(409,'Concurrent audit update. Retry this operation.')
    event=AuditEvent(**record,event_hash=event_hash);db.add(event);db.flush();return event


def verify_chain(db,workspace_id):
    previous='0'*64;sequence=0
    for event in db.scalars(select(AuditEvent).where(AuditEvent.workspace_id==workspace_id).order_by(AuditEvent.sequence)):
        record={k:getattr(event,k) for k in ('workspace_id','user_id','sequence','action','resource_id','detail','previous_hash')}
        if event.hash_version==2:record.update({'id':event.id,'hash_version':2,'created_at':aware(event.created_at).isoformat(timespec='microseconds')})
        calculated=hashlib.sha256(json.dumps(record,sort_keys=True,separators=(',',':'),default=str).encode()).hexdigest()
        if event.sequence!=sequence+1 or event.previous_hash!=previous or calculated!=event.event_hash:return {'valid':False,'event_id':event.id}
        sequence=event.sequence;previous=event.event_hash
    workspace=db.get(Workspace,workspace_id)
    return {'valid':bool(workspace and workspace.audit_sequence==sequence and workspace.audit_head==previous),'events':sequence}
