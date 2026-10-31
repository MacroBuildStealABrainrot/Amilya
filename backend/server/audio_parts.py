"""Request-sized transcription checkpoints. No raw audio persists on the server."""
import asyncio
from hashlib import sha256
from uuid import UUID
from fastapi import APIRouter, Depends, File, Form, UploadFile, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session
from server import clinical, providers
from server.clinical_models import Evidence, Encounter
from server.db import get_db, uid
from server.identity import User, current_user, require_csrf
from server.core import settings

router=APIRouter(tags=['audio checkpoints'])

@router.post('/encounters/{encounter_id}/audio-parts',dependencies=[Depends(require_csrf)])
async def upload_part(encounter_id:str,file:UploadFile=File(...),job_id:UUID=Form(...),index:int=Form(...),count:int=Form(...),
    consent:bool=Form(False),overlap_ms:int=Form(0),consent_method:str|None=Form(None),consent_confirmed_at:str|None=Form(None),consent_wording:str|None=Form(None),user:User=Depends(current_user),db:Session=Depends(get_db)):
    encounter=clinical.owned(db,Encounter,encounter_id,user)
    if not consent:raise HTTPException(403,'Recording consent is required.')
    if not 1<=count<=32 or not 0<=index<count:raise HTTPException(422,'Invalid audio segment.')
    # Pieces after the first may repeat the last few seconds of the piece before (sandbox 3-second overlap).
    if not 0<=overlap_ms<=5000 or (index==0 and overlap_ms):raise HTTPException(422,'Invalid audio overlap.')
    data=await file.read(4*1024*1024+1)
    if not data or len(data)>4*1024*1024:raise HTTPException(413,'Audio segment exceeds 4 MB.')
    mime=(file.content_type or '').split(';')[0]
    clinical.checked_media(data,mime,'audio')
    key=f'audio-part:{job_id}:{index}'
    digest=clinical.digest([sha256(data).hexdigest(),mime,count])
    previous=clinical.previous_request(db,Evidence,user,encounter_id,key,digest)
    if previous:return {'index':index,'status':'transcribed','id':previous.id}
    # ffprobe is a blocking subprocess: keep it off the event loop so other requests are not stalled.
    seconds=await asyncio.to_thread(providers.audio_duration,data)
    if seconds>130:raise HTTPException(422,'Audio segments must be at most 130 seconds.')
    expected=encounter.revision
    # A stable server-owned operation key prevents repeated taps/HTTP retries
    # from creating duplicate paid transcriptions.
    token=providers.request_operation.set(key+':'+digest)
    try:result=await providers.transcribe(data,mime,'segment.m4a',user=user,encounter_id=encounter_id)
    finally:providers.request_operation.reset(token)
    clinical.lock_case(db,encounter,expected)
    row=Evidence(id=uid(),workspace_id=user.workspace_id,encounter_id=encounter_id,kind='audio_part',text=result.get('text',''),
        created_by=user.id,request_key=key,request_hash=digest,warnings=result.get('warnings',[]),
        metadata_json={'job_id':str(job_id),'index':index,'count':count,'seconds':seconds,'consent_record':clinical.consent_record(consent_method,consent_confirmed_at,consent_wording),'segments':result.get('segments',[]),'raw_media_retained':False,
        **({'overlap_seconds':overlap_ms/1000} if overlap_ms else {}),
        **({'original_transcript':result['original_text']} if isinstance(result.get('original_text'),str) else {})})
    db.add(row);clinical.finish(db)
    return {'index':index,'status':'transcribed','id':row.id}

class CompleteAudio(BaseModel):
    job_id:UUID
    count:int=Field(ge=1,le=32)
    # A visit longer than about an hour is recorded in parts; each part is one job. Optional; older apps omit it.
    visit_part:int|None=Field(None,ge=1,le=8)
    visit_parts:int|None=Field(None,ge=2,le=8)
    visit_id:str|None=Field(None,min_length=8,max_length=64,pattern=r'^[A-Za-z0-9_-]+$')

@router.post('/encounters/{encounter_id}/audio-complete',dependencies=[Depends(require_csrf)])
def complete_audio(encounter_id:str,payload:CompleteAudio,user:User=Depends(current_user),db:Session=Depends(get_db)):
    encounter=clinical.owned(db,Encounter,encounter_id,user)
    key=f'audio-complete:{payload.job_id}';digest=clinical.digest(payload.model_dump(mode='json'))
    previous=clinical.previous_request(db,Evidence,user,encounter_id,key,digest)
    if previous:return clinical.source_response(db,encounter,previous)
    parts=list(db.scalars(select(Evidence).where(Evidence.encounter_id==encounter_id,Evidence.created_by==user.id,Evidence.kind=='audio_part')))
    parts=[part for part in parts if part.metadata_json.get('job_id')==str(payload.job_id)]
    parts.sort(key=lambda p:p.metadata_json['index'])
    if [p.metadata_json['index'] for p in parts]!=list(range(payload.count)) or any(p.metadata_json['count']!=payload.count for p in parts):
        raise HTTPException(409,'A recording segment still needs transcription. Retry that segment first.')
    if len({payload.visit_part is None,payload.visit_parts is None,payload.visit_id is None})!=1 or (payload.visit_part and payload.visit_part>payload.visit_parts):
        raise HTTPException(422,'Invalid visit part.')
    overlaps=[float(p.metadata_json.get('overlap_seconds') or 0) for p in parts]
    duration=sum(p.metadata_json['seconds'] for p in parts)-sum(overlaps[1:])
    if duration>settings.max_audio_seconds+2:raise HTTPException(422,'Recording exceeds the duration limit.')
    from server.audio_overlap import merge_parts
    text,join_notes,joins=merge_parts([{'text':p.text or '','seconds':p.metadata_json['seconds'],'overlap_seconds':overlaps[i] if i else 0,
        'segments':p.metadata_json.get('segments',[])} for i,p in enumerate(parts)])
    if len(text)>120000:raise HTTPException(413,'Transcript exceeds the supported text limit.')
    segments=[];offset=0
    for i,part in enumerate(parts):
        if i:offset-=overlaps[i]   # this piece starts a few seconds before the previous one ended
        for segment in part.metadata_json.get('segments',[]):
            item=dict(segment)
            for field in ('start','end'):
                value=item.get(field)
                if isinstance(value,(float,int)) and not isinstance(value,bool):item[field]=value+offset
            segments.append(item)
        offset+=part.metadata_json['seconds']
    # The unmerged, unshortened words of every piece are always kept with the assembled transcript.
    original='\n'.join(p.metadata_json.get('original_transcript') or p.text or '' for p in parts)
    part_warnings=[w for p in parts for w in (p.warnings or []) if isinstance(w,str) and not w.startswith('Review negation, units, laterality')]
    clinical.lock_case(db,encounter,encounter.revision)
    row=Evidence(id=uid(),workspace_id=user.workspace_id,encounter_id=encounter_id,kind='audio',text=text,created_by=user.id,
        request_key=key,request_hash=digest,metadata_json={'raw_media_retained':False,'duration_seconds':duration,'segments':segments,'job_id':str(payload.job_id),'segment_count':len(parts),
            **({'original_transcript':original} if original!=text else {}),
            **({'overlap_joins':joins} if joins else {}),
            **({'visit_part':payload.visit_part,'visit_parts':payload.visit_parts,'visit_id':payload.visit_id} if payload.visit_part else {})},
        warnings=list(dict.fromkeys(['Review the transcript against the recording, including words at segment boundaries, numbers, negation and laterality.']+part_warnings+join_notes)))
    db.add(row);db.flush()
    candidates,_=clinical.extract_explicit(text,'transcript');clinical.add_facts(db,row,candidates,user)
    clinical.timeline(db,user,encounter_id,'audio.assembled',row.id,{'segments':len(parts)})
    clinical.finish(db)
    return clinical.source_response(db,encounter,row)
