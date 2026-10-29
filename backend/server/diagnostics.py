"""Technical-only diagnostics. No bodies, URLs, exception strings or clinical identifiers.

Rows use independent short transactions so business rollbacks cannot erase failures.
Client observations are explicitly distinguished from server/provider observations.
"""
from __future__ import annotations
import asyncio, contextvars, json, logging, math, time, uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import JSON, DateTime, String, Float, select, delete, exists, cast, create_engine
from sqlalchemy.orm import Mapped, mapped_column,sessionmaker
from sqlalchemy.pool import NullPool
from .db import Base, utcnow, aware
from .core import settings

# Isolate bounded telemetry writes from the clinical connection pool and its longer
# lock waits. A telemetry outage must not impose a 30-second clinical DB timeout.
_options={'connect_args':{'check_same_thread':False,'timeout':.1},'poolclass':NullPool} if settings.database_url.startswith('sqlite') else {'pool_size':2,'max_overflow':0,'pool_timeout':.1,'connect_args':{'connect_timeout':1,'options':'-c statement_timeout=500 -c lock_timeout=100'}}
diagnostic_engine=create_engine(settings.database_url,pool_pre_ping=True,**_options)
SessionLocal=sessionmaker(diagnostic_engine,expire_on_commit=False,autoflush=False)

router=APIRouter(tags=['developer diagnostics'])
active=contextvars.ContextVar('diagnostic_context',default=None)
log=logging.getLogger('woundcopilot.diagnostics')
write_failures=0
_last_prune=0.0
provider_log=logging.getLogger('woundcopilot.provider')
TASKS={'transcription','audio_verification','nursing','soap','sbar','physician_update','referral_letter','handoff','custom','photo_assessment','ask_consultant','deep_review','clinical_review','document_save','recording','extract','document','quick_refine','improve_report','improve_assessment','external_flow','wear_control','unknown'}
ERRORS={'HTTP_ERROR':'Upstream HTTP rejection','TIMEOUT':'Operation timed out','CONNECTION_FAILED':'Provider connection failed','CANCELLED':'Operation cancelled','INVALID_RESPONSE':'Response validation failed','INTERNAL_ERROR':'Operation failed','REQUEST_REJECTED':'Request rejected'}

class DiagnosticRequest(Base):
    __tablename__='diagnostic_requests'
    id:Mapped[str]=mapped_column(String(36),primary_key=True)
    job_id:Mapped[str]=mapped_column(String(36),index=True)
    workspace_id:Mapped[str]=mapped_column(String(36),index=True)
    user_id:Mapped[str]=mapped_column(String(36),index=True)
    started_at:Mapped[datetime]=mapped_column(DateTime(timezone=True),default=utcnow,index=True)
    task:Mapped[str]=mapped_column(String(32),default='unknown',index=True)
    data:Mapped[dict]=mapped_column(JSON,default=dict)

class DiagnosticEvent(Base):
    __tablename__='diagnostic_events'
    id:Mapped[str]=mapped_column(String(36),primary_key=True)
    request_id:Mapped[str|None]=mapped_column(String(36),nullable=True,index=True)
    job_id:Mapped[str]=mapped_column(String(36),index=True)
    workspace_id:Mapped[str]=mapped_column(String(36),index=True)
    user_id:Mapped[str]=mapped_column(String(36),index=True)
    created_at:Mapped[datetime]=mapped_column(DateTime(timezone=True),default=utcnow,index=True)
    data:Mapped[dict]=mapped_column(JSON,default=dict)

def timestamp():return utcnow().isoformat()
def opaque(value):
    try:return str(uuid.UUID(str(value)))
    except (ValueError,TypeError,AttributeError):return str(uuid.uuid4())

def begin(request_id,job_id,enabled=True,client_request_id=None):
    try:client_request_id=str(uuid.UUID(str(client_request_id)))
    except (ValueError,TypeError):client_request_id=None
    return active.set({'id':request_id,'job_id':opaque(job_id or request_id),'client_request_id':client_request_id,'start':time.perf_counter_ns(),'started_at':timestamp(),'task':'unknown','user':None,'attempts':0} if enabled else None)

def persist(action):
    global write_failures
    try:
        with SessionLocal() as db:
            action(db);db.commit()
    except Exception:
        write_failures+=1
        log.warning('diagnostic_write_failed')

def prune():
    global _last_prune
    if time.monotonic()-_last_prune<3600:return
    _last_prune=time.monotonic()
    def remove(db):
        cutoff=utcnow()-timedelta(days=30)
        db.execute(delete(DiagnosticEvent).where(DiagnosticEvent.created_at<cutoff))
        db.execute(delete(DiagnosticRequest).where(DiagnosticRequest.started_at<cutoff))
    persist(remove)

def bind(user):
    c=active.get()
    if not c or c['user'] is not None:return
    c['user']=(user.workspace_id,user.id)
    prune()
    persist(lambda db:db.add(DiagnosticRequest(id=c['id'],job_id=c['job_id'],workspace_id=user.workspace_id,user_id=user.id,task=c['task'],data={'status':'running','started_at':c['started_at'],'client_request_id':c['client_request_id']})))

def task(value):
    c=active.get()
    if c and value in TASKS:c['task']=value

def event(data,event_id=None):
    c=active.get()
    if not c or not c['user']:return
    if data.get('status')=='failed':c['failure_stage']=data.get('stage')
    # Callers construct a closed technical schema. Never accept external dicts here.
    persist(lambda db:db.add(DiagnosticEvent(id=event_id or str(uuid.uuid4()),request_id=c['id'],job_id=c['job_id'],workspace_id=c['user'][0],user_id=c['user'][1],data=data)))

def finish(status):
    c=active.get()
    if not c or not c['user']:return
    def save(db):
        row=db.get(DiagnosticRequest,c['id'])
        if row:
            row.task=c['task'];row.data={**row.data,**c.get('links',{}),'finished_at':timestamp(),'duration_ms':(time.perf_counter_ns()-c['start'])/1e6,'status':'success' if status<400 else 'failed','http_status':status,'failure_stage':c.get('failure_stage','request_validation_or_backend') if status>=400 else None,'error_code':None if status<400 else 'REQUEST_REJECTED' if status<500 else 'INTERNAL_ERROR'}
    persist(save)

def saved_document(document_id,generation_id):
    c=active.get()
    if not c or not c['user']:return
    c['links']={'document_id':str(document_id)}
    try:
        generation_id=str(uuid.UUID(str(generation_id)))
        with SessionLocal() as db:
            origin=db.get(DiagnosticRequest,generation_id)
            if origin and origin.user_id==c['user'][1] and origin.workspace_id==c['user'][0] and origin.job_id==c['job_id']:
                c['links']['generation_request_id']=origin.id
    except Exception:pass  # Provenance is unknown, never guessed from task/configuration.

def safe_number(value):
    return value if type(value) in (int,float) and math.isfinite(value) and 0<=value<1e12 else None

def cloud_event(data):
    """Closed metadata only; remains available if diagnostic DB writes fail."""
    from .providers import MODEL_PRICES, SPEECH_MODEL_IDS, TASK_FILES, photo_error_headers, known_model_id, FINISH_REASONS
    c=active.get()
    if not c:return
    try:request_id=str(uuid.UUID(str(c['id'])))
    except (ValueError,TypeError):return
    stage=data.get('stage')
    if stage not in set(TASK_FILES) | {'transcription','audio_verification'}:return
    record={'event':'ai_provider','request_id':request_id,'stage':stage,
            'status':data.get('status') if data.get('status') in {'success','failed','cancelled'} else 'unknown'}
    for key in ('selected_model','reported_model'):
        value=data.get(key)
        record[key]=value if known_model_id(value) and (key=='reported_model' or value in MODEL_PRICES or value in SPEECH_MODEL_IDS) else None
    value=data.get('provider')
    record['provider']=value if value in {'openrouter','cloudflare','groq','deepgram'} else None
    for key in ('http_status','duration_ms','retry_count','max_output_tokens'):
        record[key]=safe_number(data.get(key))
    finish=data.get('finish_reason')
    if isinstance(finish,str) and finish in FINISH_REASONS:record['finish_reason']=finish
    code=data.get('error_code')
    record['error_code']=code if code in ERRORS else None
    if 'failure_reason' in data:
        record['error_category']=photo_error_headers(data)['X-WCP-Error']
    provider_log.info(json.dumps(record,sort_keys=True,separators=(',',':')))

@contextmanager
def measure(stage):
    start=time.perf_counter_ns();started_at=timestamp();status='success'
    try:yield
    except BaseException:
        status='failed';raise
    finally:event({'source':'server','stage':stage,'status':status,'started_at':started_at,'finished_at':timestamp(),'duration_ms':(time.perf_counter_ns()-start)/1e6})

async def post(client,url,*,provider,model,stage,retry=0,fallback=False,original_provider=None,original_model=None,effort=None,**kwargs):
    """Called at the transport boundary with the exact outgoing selected model."""
    start=time.perf_counter_ns()
    data={'source':'provider','stage':stage,'selected_model':model,'provider':provider,'started_at':timestamp(),'retry_count':retry,'fallback_used':fallback,'original_provider':original_provider or provider,'original_model':original_model or model,'status':'running','input_tokens':None,'output_tokens':None,'cost_usd':None,'cost_basis':'unknown'}
    if effort in {'low','medium','high'}:data['effort_level']=effort
    from .documentation_layers import VERSION,selection
    if stage in {'quick_draft','quick_qa','quick_repair','quick_refine','improve_report'}:
        data.update(prompt_layers_version=VERSION,clinician_role=selection.get()[0],report_format=selection.get()[1])
    event_id=str(uuid.uuid4());event(data,event_id)
    try:
        response=await client.post(url,**kwargs)
        data['http_status']=response.status_code
        data['status']='success' if response.status_code<400 else 'failed'
        try:raw=response.json()
        except Exception:raw={}
        if isinstance(raw,dict):
            usage=raw.get('usage')
            if isinstance(usage,dict):
                data['input_tokens']=safe_number(usage.get('prompt_tokens',usage.get('input_tokens')))
                data['output_tokens']=safe_number(usage.get('completion_tokens',usage.get('output_tokens')))
                data['cost_usd']=safe_number(usage.get('cost'))
                if data['cost_usd'] is not None:data['cost_basis']='provider_reported'
            # Provider metadata is untrusted too: never copy arbitrary returned strings.
            from .providers import known_model_id
            reported=raw.get('model')
            data['reported_model']=reported if known_model_id(reported) else None
            data['reported_model_unrecognized']=reported is not None and data['reported_model'] is None
            if 'error' in raw or raw.get('success') is False:
                data['status']='failed'
                code=raw.get('error',{});code=code.get('code') if isinstance(code,dict) else None
                if type(code)==int and 400<=code<=599:data['http_status']=code
        if data['status']=='failed':data['error_code']='HTTP_ERROR'
        return response
    except BaseException as exc:
        import httpx
        data['status']='cancelled' if isinstance(exc,asyncio.CancelledError) else 'failed'
        data['error_code']='CANCELLED' if isinstance(exc,asyncio.CancelledError) else 'TIMEOUT' if isinstance(exc,(httpx.TimeoutException,TimeoutError)) else 'CONNECTION_FAILED' if isinstance(exc,httpx.HTTPError) else 'INTERNAL_ERROR'
        raise
    finally:
        data.update(finished_at=timestamp(),duration_ms=(time.perf_counter_ns()-start)/1e6)
        if data['status'] in {'failed','cancelled'} and active.get():active.get()['failure_stage']=stage
        if data.get('error_code'):data['error_message']=ERRORS[data['error_code']]
        def save(db):
            row=db.get(DiagnosticEvent,event_id)
            if row:row.data=data
        if active.get() and active.get()['user']:persist(save)
        cloud_event(data)

def outcome(stage,model,provider,success,usage,normalized,billed):
    cloud_event({'stage':stage,'selected_model':model,'reported_model':usage.get('reported_model'),
        'provider':provider,'status':'success' if success else 'failed',
        **({} if success else {'failure_reason':usage.get('failure_reason')}),
        'http_status':usage.get('upstream_status'), 'max_output_tokens':usage.get('max_output_tokens'),
        'finish_reason':usage.get('finish_reason')})
    if stage == 'image':
        from .providers import photo_error_headers
        event({'source':'server','stage':'photo_result','selected_model':model,'provider':provider,
            'status':'success' if success else 'failed','finished_at':timestamp(),
            'error_code':None if success else photo_error_headers(usage)['X-WCP-Error'],
            'http_status':safe_number(usage.get('upstream_status'))})
    event({'source':'server','stage':stage+'_result','selected_model':model,'provider':provider,'status':'success' if success else 'failed','finished_at':timestamp(),'error_code':None if success else 'PROVIDER_OR_VALIDATION_FAILED','input_tokens':safe_number(usage.get('input_tokens')),'output_tokens':safe_number(usage.get('output_tokens')),'normalized_cost_usd':normalized/1e6 if normalized is not None else None,'cost_basis':'versioned_billing_normalization','price_registry_version':usage.get('price_registry_version')})

def faithfulness(passed,count,phase):
    event({'source':'server','stage':'source_faithfulness_'+phase,'status':'success' if passed else 'failed','finished_at':timestamp(),'defect_count':count,'error_code':None if passed else 'SOURCE_FAITHFULNESS_FAILED'})

ClientStage=Literal['photo_source','photo_request','photo_retained','photo_state','recording_captured','recording_stopped','local_save','upload_pending','upload','transcription_request','transcript_assembly','generation_request','report_state','report_display','report_save','retry','recording_retained','recording_unavailable','cleanup','audio_preparation','processing_total','external_started','external_returned','external_cancelled','external_saved','external_failed','app_background','app_foreground','lock_suppressed_trusted_flow','lock_required_device','lock_required_background','lock_required_timeout','watch_start_requested','watch_start_confirmed','watch_stop_requested','watch_stop_confirmed','watch_cancel_requested','watch_open_phone','watch_status_sync','watch_command_failed','watch_connected','watch_disconnected']
class ClientEvent(BaseModel):
    model_config=ConfigDict(extra='forbid')
    id:uuid.UUID
    job_id:uuid.UUID
    client_request_id:uuid.UUID|None=None
    flow_type:Literal['NONE','PHOTO_PICKER','CAMERA','DOCUMENT_PICKER','AUDIO_PICKER','PERMISSION','BIOMETRIC','WEAR_OS']|None=None
    wear_error:Literal['NONE','PHONE_UNAVAILABLE','PHONE_LOCKED','SESSION_REQUIRED','MIC_PERMISSION_REQUIRED','CONSENT_REQUIRED','OPEN_PHONE_REQUIRED','RECORDING_ALREADY_ACTIVE','NO_ACTIVE_RECORDING','BUSY','STALE_COMMAND','PROCESSING_FAILED','AUDIO_SAVE_FAILED','PROTOCOL_INCOMPATIBLE','CONNECTION_LOST','MULTIPLE_PHONES','UNAVAILABLE']|None=None
    stage:ClientStage
    started_at:datetime
    finished_at:datetime
    duration_ms:float=Field(ge=0,le=7*86400000,allow_inf_nan=False)
    status:Literal['success','failed','pending','cancelled']
    photo_exists:bool|None=None
    image_bytes:int|None=Field(default=None,ge=0,le=25*1024*1024)
    recording_exists:bool|None=None
    storage:Literal['app_private','encrypted_verified','unknown']='unknown'
    retry_count:int=Field(default=0,ge=0,le=1000)

class ClientBatch(BaseModel):
    model_config=ConfigDict(extra='forbid')
    events:list[ClientEvent]=Field(max_length=100,min_length=1)

from .identity import current_user,require_admin,require_csrf,User,check_rate

@router.post('/diagnostics/events',dependencies=[Depends(require_csrf)])
def upload_events(payload:ClientBatch,user:User=Depends(current_user)):
    check_rate('diagnostics:'+user.id,limit=120)
    with SessionLocal() as db:
        if db.bind.dialect.name=='postgresql':
            from sqlalchemy.dialects.postgresql import insert
        else:
            from sqlalchemy.dialects.sqlite import insert
        for item in payload.events:
            if item.started_at.tzinfo is None or item.finished_at.tzinfo is None:raise HTTPException(422,'Timezone required')
            if item.finished_at<item.started_at or abs((utcnow()-item.finished_at).total_seconds())>7*86400:raise HTTPException(422,'Invalid diagnostic event time')
            existing=db.get(DiagnosticEvent,str(item.id))
            if existing:
                if existing.user_id!=user.id:raise HTTPException(409,'Diagnostic identity conflict')
                continue
            data=item.model_dump(mode='json',exclude={'id','job_id'})
            db.execute(insert(DiagnosticEvent).values(id=str(item.id),request_id=None,job_id=str(item.job_id),workspace_id=user.workspace_id,user_id=user.id,data={**data,'source':'device_reported',**({'origin':'WEAR_OS'} if item.flow_type=='WEAR_OS' else {})}).on_conflict_do_nothing(index_elements=['id']))
            admitted=db.get(DiagnosticEvent,str(item.id))
            if admitted.user_id!=user.id:raise HTTPException(409,'Diagnostic identity conflict')
            offline_id=str(uuid.uuid5(uuid.NAMESPACE_URL,'diagnostic-device:'+user.id+':'+str(item.job_id)))
            db.execute(insert(DiagnosticRequest).values(id=offline_id,job_id=str(item.job_id),workspace_id=user.workspace_id,user_id=user.id,task='photo_assessment' if item.stage.startswith('photo_') else 'wear_control' if item.flow_type=='WEAR_OS' else 'external_flow' if item.flow_type else 'recording',data={}).on_conflict_do_nothing(index_elements=['id']))
            row=db.scalar(select(DiagnosticRequest).where(DiagnosticRequest.id==offline_id).with_for_update())
            previous_time=row.data.get('finished_at')
            if previous_time is None or datetime.fromisoformat(previous_time)<=item.finished_at:
                row.data={'source':'device_reported','status':item.status,'started_at':item.started_at.isoformat(),'finished_at':item.finished_at.isoformat(),'latest_stage':item.stage,'recording_exists':item.recording_exists,'storage':item.storage}
            db.flush()
        db.commit()
    return {'accepted':len(payload.events)}

@router.get('/admin/diagnostics')
def listing(user:User=Depends(require_admin),task_filter:str|None=Query(None,alias='task'),limit:int=Query(100,ge=1,le=200),offset:int=Query(0,ge=0,le=100000),model:str|None=None,provider:str|None=None,status:Literal['success','failed','running','pending']|None=None,fallback:bool|None=None,since:datetime|None=None,slow_ms:float|None=Query(None,ge=0),sort:Literal['date','duration']='date'):
    with SessionLocal() as db:
        cutoff=utcnow()-timedelta(days=30)
        db.execute(delete(DiagnosticEvent).where(DiagnosticEvent.created_at<cutoff));db.execute(delete(DiagnosticRequest).where(DiagnosticRequest.started_at<cutoff));db.commit()
        query=select(DiagnosticRequest).order_by(DiagnosticRequest.started_at.desc()).offset(offset).limit(limit)
        if task_filter:
            if task_filter not in TASKS:raise HTTPException(422,'Unknown task')
            query=query.where(DiagnosticRequest.task==task_filter)
        if status:query=query.where(DiagnosticRequest.data['status'].as_string()==status)
        if since:query=query.where(DiagnosticRequest.started_at>=since)
        if slow_ms is not None:query=query.where(cast(DiagnosticRequest.data['duration_ms'].as_string(),Float)>=slow_ms)
        for key,value in [('selected_model',model),('provider',provider)]:
            if value:query=query.where(exists(select(DiagnosticEvent.id).where(DiagnosticEvent.request_id==DiagnosticRequest.id,DiagnosticEvent.data[key].as_string()==value)))
        if fallback is not None:query=query.where(exists(select(DiagnosticEvent.id).where(DiagnosticEvent.request_id==DiagnosticRequest.id,DiagnosticEvent.data['fallback_used'].as_boolean()==True))==fallback)
        if sort=='duration':query=query.order_by(None).order_by(cast(DiagnosticRequest.data['duration_ms'].as_string(),Float).desc(),DiagnosticRequest.started_at.desc())
        rows=db.scalars(query).all()
        items=[]
        for r in rows:
            events=db.scalars(select(DiagnosticEvent).where(DiagnosticEvent.request_id==r.id)).all()
            attempts=[e.data for e in events if e.data.get('source') in {'provider','replay'} and e.data.get('selected_model') and e.data.get('provider')]
            items.append({'request_id':r.id,'job_id':r.job_id,'task':r.task,**r.data,'models':sorted({e['selected_model'] for e in attempts}),'providers':sorted({e['provider'] for e in attempts}),'fallback_used':any(e.get('fallback_used') for e in attempts),'retry_count':sum(e.get('retry_count',0)>0 for e in attempts)})
        return {'items':items,'offset':offset,'has_more':len(rows)==limit,'write_failures_since_process_start':write_failures,'retention_days':30}

@router.get('/admin/diagnostics/{request_id}')
def detail(request_id:uuid.UUID,user:User=Depends(require_admin)):
    with SessionLocal() as db:
        row=db.get(DiagnosticRequest,str(request_id))
        if row is None:raise HTTPException(404,'Diagnostic request not found')
        events=db.scalars(select(DiagnosticEvent).where(DiagnosticEvent.job_id==row.job_id,DiagnosticEvent.user_id==row.user_id,DiagnosticEvent.workspace_id==row.workspace_id).order_by(DiagnosticEvent.created_at)).all()
        data=[{'event_id':e.id,'request_id':e.request_id,**e.data} for e in events]
        origin=row.data.get('generation_request_id',row.id)
        generation=[e for e in data if e['request_id']==origin and e.get('source') in {'provider','replay'} and e.get('stage') in {'quick_draft','quick_refine','quick_repair','improve_report','draft'}]
        return {'request_id':row.id,'job_id':row.job_id,'task':row.task,**row.data,'last_generation_model':generation[-1].get('selected_model') if generation else None,'generation_request_id':origin if generation else None,'client_retry_count':sum(e.get('stage')=='retry' for e in data),'provider_retry_count':sum(e.get('source')=='provider' and e.get('retry_count',0)>0 for e in data),'events':data}


