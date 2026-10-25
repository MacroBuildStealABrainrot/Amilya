"""Synthetic-only diagnostics: no live transport and no clinical production database."""
import asyncio,json,uuid
from datetime import timedelta
import httpx,pytest
from fastapi import FastAPI,HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import select
from server import diagnostics as d,providers
from server.db import utcnow
from server.identity import current_user,require_csrf
from test_providers import gateway,completion

@pytest.fixture
def trace(gateway,monkeypatch):
    monkeypatch.setattr(d,'SessionLocal',gateway.sessions)
    monkeypatch.setattr(d,'check_rate',lambda *a,**k:None)
    token=d.begin(str(uuid.uuid4()),str(uuid.uuid4()))
    d.bind(gateway.user)
    yield gateway
    d.active.reset(token)

def events(g):
    with g.sessions() as db:return [r.data for r in db.scalars(select(d.DiagnosticEvent).order_by(d.DiagnosticEvent.created_at))]

def test_photo_metadata_failure_and_retry_do_not_copy_image_or_error_body(trace):
    import base64
    from test_photo_recovery import photo,assess,successful
    sentinel='SYNTHETIC_SECRET_NOT_FOR_DIAGNOSTICS'
    trace.http.responses=[(502,{'error':{'message':sentinel}}),successful()]
    with pytest.raises(HTTPException):assess(trace)
    assess(trace)
    rows=events(trace)
    serialized=json.dumps(rows)
    assert sentinel not in serialized
    assert base64.b64encode(photo()).decode() not in serialized
    assert 'data:image/' not in serialized and 'synthetic-openrouter-key' not in serialized
    inputs=[r for r in rows if r['stage']=='image_input']
    assert len(inputs)==2 and inputs[0]['width']==40 and inputs[0]['height']==30
    attempts=[r for r in rows if r['stage']=='photo_attempt']
    assert [r['retry_count'] for r in attempts]==[0,1]
    assert all(r['selected_model']==trace.config.model_vision for r in attempts)
    assert any(r['stage']=='photo_result' and r['error_code']=='PROVIDER_UNAVAILABLE' for r in rows)

def test_exact_outgoing_router_model_not_task_configuration(trace,monkeypatch):
    selected='openai/gpt-6-astra'
    monkeypatch.setattr(providers,'select_route',lambda *a:{'model':selected,'effort_level':'high'})
    trace.http.responses=[completion()]
    asyncio.run(providers.routed('extract','Entirely synthetic clinical input',user=trace.user,encounter_id='synthetic'))
    d.finish(200)
    attempt=[e for e in events(trace) if e['source']=='provider'][0]
    assert attempt['selected_model']==trace.http.calls[0]['json']['model']==selected
    assert selected!=trace.config.model_extraction
    assert attempt['input_tokens']==1000 and attempt['output_tokens']==200
    assert attempt['cost_usd']==.001 and attempt['duration_ms']>=0
    assert attempt['started_at']<=attempt['finished_at']

def test_rate_limit_has_two_individually_timed_attempts(trace):
    trace.http.responses=[httpx.Response(429,headers={'Retry-After':'1'},json={'error':'synthetic'},request=httpx.Request('POST','https://provider.invalid')),completion()]
    asyncio.run(providers.structured('extract','synthetic','meta/muse-spark-1.3',user=trace.user,encounter_id='synthetic'))
    attempts=[e for e in events(trace) if e['source']=='provider']
    assert [e['retry_count'] for e in attempts]==[0,1]
    assert [e['status'] for e in attempts]==['failed','success']
    assert attempts[0]['cost_usd'] is None
    assert not any(e['fallback_used'] for e in attempts)

def test_speech_fallback_records_original_and_fallback_even_when_both_fail(trace,monkeypatch):
    trace.config.cloudflare_api_token='synthetic';trace.config.cloudflare_account_id='synthetic';trace.config.groq_api_key='synthetic'
    monkeypatch.setattr(providers,'audio_duration',lambda *a:30)
    trace.http.responses=[(500,{}),(500,{})]
    with pytest.raises(HTTPException):asyncio.run(providers.transcribe(b'synthetic','audio/mp4','synthetic.m4a',user=trace.user,encounter_id='synthetic'))
    d.finish(502)
    attempts=[e for e in events(trace) if e['source']=='provider']
    assert len(attempts)==2
    assert attempts[0]['selected_model']=='@cf/openai/whisper-large-v3-turbo'
    assert attempts[1]['selected_model']=='whisper-large-v3-turbo'
    assert attempts[1]['fallback_used'] and attempts[1]['original_provider']=='cloudflare'
    assert all(e['status']=='failed' for e in attempts)
    with trace.sessions() as db:assert db.scalars(select(d.DiagnosticRequest)).one().data['status']=='failed'

def test_untrusted_error_body_and_reported_model_cannot_leak(trace):
    sentinel='SYNTHETIC_SECRET_AND_CLINICAL_SENTINEL'
    trace.http.responses=[(400,{'error':{'message':sentinel},'model':sentinel})]
    with pytest.raises(HTTPException):asyncio.run(providers.structured('extract',sentinel,'meta/muse-spark-1.3',user=trace.user,encounter_id='synthetic'))
    assert sentinel not in json.dumps(events(trace))
    assert next(e for e in events(trace) if e['source']=='provider')['error_message']=='Upstream HTTP rejection'

def test_cancellation_retains_attempt_record(trace):
    def cancelled(_):raise asyncio.CancelledError()
    trace.http.responses=[cancelled]
    with pytest.raises(asyncio.CancelledError):asyncio.run(providers.structured('extract','synthetic','meta/muse-spark-1.3',user=trace.user,encounter_id='synthetic'))
    attempt=next(e for e in events(trace) if e['source']=='provider')
    assert attempt['status']=='cancelled' and attempt['error_code']=='CANCELLED'

def test_diagnostic_storage_failure_does_not_replace_provider_result(trace,monkeypatch):
    before=d.write_failures
    def broken():raise RuntimeError('Synthetic storage failure')
    monkeypatch.setattr(d,'SessionLocal',broken)
    trace.http.responses=[completion()]
    result=asyncio.run(providers.structured('extract','synthetic','meta/muse-spark-1.3',user=trace.user,encounter_id='synthetic'))
    assert result['facts']==[] and d.write_failures>before

def test_admin_authorization_and_strict_client_metadata(trace):
    app=FastAPI();app.include_router(d.router)
    app.dependency_overrides[current_user]=lambda:trace.user
    app.dependency_overrides[require_csrf]=lambda:trace.user
    client=TestClient(app)
    assert client.get('/admin/diagnostics').status_code==403
    trace.user.is_platform_admin=True
    assert client.get('/admin/diagnostics').status_code==200
    now=utcnow().isoformat();job=str(uuid.uuid4())
    item={'id':str(uuid.uuid4()),'job_id':job,'stage':'local_save','started_at':now,'finished_at':now,'duration_ms':20,'status':'success','recording_exists':True,'storage':'app_private'}
    for forbidden in ('transcript','prompt','api_key','patient_name','error_message'):
        assert client.post('/diagnostics/events',json={'events':[{**item,forbidden:'synthetic'}]}).status_code==422
    assert client.post('/diagnostics/events',json={'events':[item]}).status_code==200
    assert client.post('/diagnostics/events',json={'events':[item]}).status_code==200
    with trace.sessions() as db:
        rows=db.scalars(select(d.DiagnosticEvent).where(d.DiagnosticEvent.job_id==job)).all()
        assert len(rows)==1 and rows[0].data['source']=='device_reported'
    trace.user.is_platform_admin=False
    assert client.get('/admin/diagnostics/'+d.active.get()['id']).status_code==403

def test_retention_removes_old_metadata_without_touching_clinical_state(trace):
    with trace.sessions() as db:
        row=db.scalars(select(d.DiagnosticRequest)).one();row.started_at=utcnow()-timedelta(days=31);db.commit()
    trace.user.is_platform_admin=True
    assert d.listing(trace.user,None,100,0,slow_ms=None)['items']==[]

def test_concurrent_device_replay_and_out_of_order_state(trace):
    from concurrent.futures import ThreadPoolExecutor
    now=utcnow();job=uuid.uuid4();client_id=uuid.uuid4()
    def item(stage,when,exists):
        return d.ClientEvent(id=uuid.uuid4(),job_id=job,client_request_id=client_id,stage=stage,started_at=when,finished_at=when,duration_ms=0,status='success',recording_exists=exists,storage='app_private')
    newest=item('cleanup',now,False)
    batch=d.ClientBatch(events=[newest])
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda _:d.upload_events(batch,trace.user),range(2)))
    assert results==[{'accepted':1},{'accepted':1}]
    d.upload_events(d.ClientBatch(events=[item('local_save',now-timedelta(seconds=20),True)]),trace.user)
    with trace.sessions() as db:
        records=db.scalars(select(d.DiagnosticEvent).where(d.DiagnosticEvent.job_id==str(job))).all()
        assert len(records)==2
        assert all(r.data['client_request_id']==str(client_id) for r in records)
        row=db.scalars(select(d.DiagnosticRequest).where(d.DiagnosticRequest.job_id==str(job))).one()
        assert row.data['latest_stage']=='cleanup' and row.data['recording_exists'] is False

def test_external_flow_metadata_is_closed_and_has_no_image_or_auth_content():
    now=utcnow();safe={'id':uuid.uuid4(),'job_id':uuid.uuid4(),'stage':'lock_suppressed_trusted_flow','started_at':now,'finished_at':now,'duration_ms':0,'status':'success','flow_type':'PHOTO_PICKER'}
    assert d.ClientEvent(**safe).flow_type=='PHOTO_PICKER'
    for field in ['image','path','pin','biometric','patient_name']:
        with pytest.raises(ValueError):d.ClientEvent(**safe,**{field:'SYNTHETIC_FORBIDDEN'})
    with pytest.raises(ValueError):d.ClientEvent(**{**safe,'flow_type':'SYNTHETIC_UNTRUSTED_TEXT'})

def test_replay_keeps_actual_original_model_without_new_provider_call(trace):
    trace.http.responses=[completion()]
    kwargs=dict(user=trace.user,encounter_id='synthetic')
    first=asyncio.run(providers.structured('extract','synthetic','meta/muse-spark-1.3',**kwargs))
    second=asyncio.run(providers.structured('extract','synthetic','meta/muse-spark-1.3',**kwargs))
    assert len(trace.http.calls)==1 and first==second
    replay=events(trace)[-1]
    assert replay['source']=='replay' and replay['selected_model']=='meta/muse-spark-1.3'
    assert replay['new_provider_request'] is False

def test_saved_report_links_only_to_same_actor_job(trace):
    origin=d.active.get()['id'];job=d.active.get()['job_id']
    trace.http.responses=[completion({'text':'Synthetic nursing note.','status':'ready','ui_alerts':[]})]
    asyncio.run(providers.structured('quick_draft','synthetic','openai/gpt-6.1-sol',user=trace.user,encounter_id='synthetic'))
    d.finish(200)
    token=d.begin(str(uuid.uuid4()),job)
    try:
        d.bind(trace.user);d.task('document_save');d.saved_document(str(uuid.uuid4()),origin);d.finish(201)
        trace.user.is_platform_admin=True
        result=d.detail(uuid.UUID(d.active.get()['id']),trace.user)
        assert result['generation_request_id']==origin and result['last_generation_model']=='openai/gpt-6.1-sol'
        d.active.get()['job_id']=str(uuid.uuid4())
        d.saved_document(str(uuid.uuid4()),origin)
        assert 'generation_request_id' not in d.active.get()['links']
    finally:d.active.reset(token)

def test_filter_by_model_failure_fallback_and_duration(trace):
    trace.http.responses=[(500,{})]
    with pytest.raises(HTTPException):asyncio.run(providers.structured('extract','synthetic','meta/muse-spark-1.3',user=trace.user,encounter_id='synthetic'))
    d.finish(502);trace.user.is_platform_admin=True
    app=FastAPI();app.include_router(d.router);app.dependency_overrides[current_user]=lambda:trace.user
    c=TestClient(app)
    assert len(c.get('/admin/diagnostics?model=meta%2Fmuse-spark-1.3&status=failed&slow_ms=0&sort=duration').json()['items'])==1
    assert c.get('/admin/diagnostics?model=nonexistent').json()['items']==[]
    assert c.get('/admin/diagnostics?fallback=true').json()['items']==[]

def test_http_boundary_keeps_failed_request_without_a_clinical_report(trace):
    from server.main import boundary
    app=FastAPI();app.middleware('http')(boundary)
    @app.post('/api/v1/encounters/synthetic/quick-report')
    async def fail():
        d.bind(trace.user);d.task('nursing')
        raise RuntimeError('SYNTHETIC_BODY_MUST_NOT_ENTER_DIAGNOSTICS')
    c=TestClient(app)
    job=str(uuid.uuid4())
    response=c.post('/api/v1/encounters/synthetic/quick-report',headers={'X-Diagnostic-Job':job})
    assert response.status_code==500
    with trace.sessions() as db:
        row=db.get(d.DiagnosticRequest,response.headers['X-Request-ID'])
        assert row.job_id==job and row.task=='nursing' and row.data['status']=='failed'
        assert 'SYNTHETIC_BODY' not in json.dumps(row.data)


def test_wear_events_use_existing_admin_diagnostics_and_closed_metadata(trace):
    now=utcnow();job=uuid.uuid4();command=uuid.uuid4()
    item=d.ClientEvent(id=uuid.uuid4(),job_id=job,client_request_id=command,flow_type='WEAR_OS',wear_error='PHONE_LOCKED',stage='watch_command_failed',started_at=now,finished_at=now,duration_ms=0,status='failed')
    assert d.upload_events(d.ClientBatch(events=[item]),trace.user)=={'accepted':1}
    with trace.sessions() as db:
        event=db.get(d.DiagnosticEvent,str(item.id))
        assert event.data['origin']=='WEAR_OS'
        assert event.data['source']=='device_reported'
        assert event.data['client_request_id']==str(command)
        row=db.scalar(select(d.DiagnosticRequest).where(d.DiagnosticRequest.job_id==str(job)))
        assert row.task=='wear_control' and row.data['status']=='failed'
    for field in ['transcript','report','audio','patient_name','phone_number','pin','auth_token']:
        with pytest.raises(ValueError):d.ClientEvent(**{**item.model_dump(),field:'SYNTHETIC_FORBIDDEN'})
    with pytest.raises(ValueError):d.ClientEvent(**{**item.model_dump(),'wear_error':'SYNTHETIC_FREE_TEXT'})
