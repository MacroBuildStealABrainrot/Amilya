from __future__ import annotations

import asyncio,logging,sys,time,uuid
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI,Request
from fastapi.responses import FileResponse,JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.middleware.trustedhost import TrustedHostMiddleware
from sqlalchemy import text
from server.core import settings
from server.db import Base,engine,SessionLocal
from server import identity,audit,providers,transfer_models
from server.performance import job_id as performance_job
from server import diagnostics

logger=logging.getLogger('woundcopilot')
# Cloud Run ingests one JSON object per line as structured logs. This dedicated
# logger receives only diagnostics.cloud_event's allowlisted technical metadata.
if not diagnostics.provider_log.handlers:
    diagnostics.provider_log.addHandler(logging.StreamHandler(sys.stdout))
diagnostics.provider_log.setLevel(logging.INFO)


@asynccontextmanager
async def lifespan(app):
    settings.validate_runtime()
    if settings.auto_create_schema:
        Base.metadata.create_all(engine)
    yield


app=FastAPI(title='WoundCoPilot',version='6.0.0',lifespan=lifespan,docs_url='/docs' if settings.environment!='production' else None,redoc_url=None)
app.add_middleware(TrustedHostMiddleware,allowed_hosts=[h.strip() for h in settings.trusted_hosts.split(',')])


@app.middleware('http')
async def boundary(request:Request,call_next):
    request_id=str(uuid.uuid4());request.state.request_id=request_id;start=time.perf_counter()
    performance_token=performance_job.set(request_id)
    diagnostic_token=diagnostics.begin(request_id,request.headers.get('X-Diagnostic-Job'),request.url.path.startswith(('/api/v1/encounters/','/api/v1/quick/')),request.headers.get('X-Diagnostic-Client-Request'))
    # Reject large bodies before the multipart parser allocates storage. Streaming
    # uploads are also capped while being read in the clinical route.
    length=request.headers.get('content-length')
    try: oversize=length is not None and int(length)>30*1024*1024
    except ValueError:oversize=True
    operation_key=request.headers.get('idempotency-key')
    invalid_key=operation_key is not None and (not operation_key.strip() or len(operation_key)>128)
    operation_token=providers.request_operation.set(operation_key if not invalid_key and operation_key is not None else request_id)
    try:
        if invalid_key:response=JSONResponse({'detail':'Idempotency-Key must contain 1 to 128 characters'},status_code=422)
        elif oversize:response=JSONResponse({'detail':'Upload exceeds the 30 MB request limit.'},status_code=413)
        else:response=await call_next(request)
    except asyncio.CancelledError:
        diagnostics.finish(499);diagnostics.active.reset(diagnostic_token)
        raise
    except Exception:
        logger.error('request_failed request_id=%s method=%s',request_id,request.method)
        response=JSONResponse({'detail':'The request could not be completed. Refresh the case to check its current state.','request_id':request_id},status_code=500)
    finally:providers.request_operation.reset(operation_token);performance_job.reset(performance_token)
    diagnostics.finish(response.status_code)
    diagnostics.active.reset(diagnostic_token)
    response.headers.update({
        'X-Request-ID':request_id,'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer',
        'X-Frame-Options':'DENY','Permissions-Policy':'camera=(self), microphone=(self), geolocation=(), payment=()',
        'Cross-Origin-Opener-Policy':'same-origin','Cross-Origin-Resource-Policy':'same-origin',
        'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self' https://checkout.stripe.com https://billing.stripe.com",
    })
    if request.url.path.startswith('/api/'):
        response.headers['Cache-Control']='no-store'
        response.headers['Vary']='Cookie'
    if settings.secure_cookies:response.headers['Strict-Transport-Security']='max-age=31536000; includeSubDomains'
    # Generic telemetry contains route templates/status/latency, never bodies or query strings.
    route=getattr(request.scope.get('route'),'path','unmatched')
    logger.info('request id=%s support=WC-%06d route=%s status=%s ms=%d',request_id,int(request_id.replace('-','')[:8],16)%1000000,route,response.status_code,(time.perf_counter()-start)*1000)
    return response


@app.get('/api/v1/health')
def health():
    try:
        with engine.connect() as connection:connection.execute(text('SELECT 1'))
    except Exception:return JSONResponse({'status':'unavailable'},status_code=503)
    return {'status':'ok','version':'6.0.0','clinical_data_enabled':settings.clinical_data_enabled}


@app.get('/api/v1/config')
def public_config():
    owner_enrollment = False
    if not settings.allow_signup:
        from .render_enrollment import configured, is_open
        if configured():
            with SessionLocal() as db:
                owner_enrollment = is_open(db)
    def capability(enabled,reason):return {'enabled':bool(enabled),'reason':'' if enabled else reason}
    ai=bool(settings.provider_execution_enabled and settings.openrouter_api_key)
    from . import render_owner_test
    if render_owner_test.required():
        ai = ai and render_owner_test.available()
    speech=bool(settings.provider_execution_enabled and (settings.cloudflare_account_id and settings.cloudflare_api_token or settings.groq_api_key or settings.deepgram_enabled and settings.deepgram_api_key))
    paused='Live assistance is configured but paused while the owner completes provider activation. Manual documentation remains available.'
    return {'version':'6.0.0','legal_version':settings.legal_version,'allow_signup':bool(settings.allow_signup or owner_enrollment),
        'signup_scope':'public' if settings.allow_signup else 'owner_test' if owner_enrollment else 'closed',
        'clinical_data_enabled':settings.clinical_data_enabled,'privacy_review_required':not settings.clinical_data_enabled,
        'synthetic_test': {'enabled': render_owner_test.available(), 'expires_at': settings.render_owner_test_until,
            'patient_data_allowed': False, 'provider_cap_usd': 1} if render_owner_test.required() else None,
        'capabilities':{'ai':capability(ai,paused if settings.openrouter_api_key and not settings.provider_execution_enabled else 'Clinical assistance is not configured. Manual documentation remains available.'),
            'photo':capability(ai,paused if settings.openrouter_api_key and not settings.provider_execution_enabled else 'Image interpretation is not configured.'),'document':capability(ai,paused if settings.openrouter_api_key and not settings.provider_execution_enabled else 'Document extraction is not configured.'),
            'audio':capability(speech,paused if not settings.provider_execution_enabled else 'Transcription is not configured. You can type or paste findings.'),
            'audio_medical':capability(settings.provider_execution_enabled and settings.deepgram_enabled and settings.deepgram_api_key,
                paused if not settings.provider_execution_enabled else 'Optional medical transcription is not enabled. The default excerpt verifier and manual review remain available.'),
            'billing':capability(settings.billing_enabled and settings.stripe_secret_key,'Checkout is not configured.')}}


app.include_router(identity.router,prefix='/api/v1')
app.include_router(diagnostics.router,prefix='/api/v1')
from server import clinical,billing,contact,audio_verification,quick_workflows,transfers,work_privacy,audio_parts,photo_collection
for module in (clinical,billing,contact,providers,audio_verification,quick_workflows,transfers,work_privacy,audio_parts,photo_collection):app.include_router(module.router,prefix='/api/v1')
app.include_router(transfers.receiver_router)

FRONTEND=Path(settings.frontend_path).resolve()
if (FRONTEND/'assets').exists():app.mount('/assets',StaticFiles(directory=FRONTEND/'assets'),name='assets')


@app.api_route('/api/{path:path}',methods=['POST','PUT','PATCH','DELETE'],include_in_schema=False)
def unknown_api_write(path:str):
    # Without this, a write to a route this server does not have reached the GET-only page route below and
    # returned a confusing HTTP 405. It usually means the app is newer than the deployed server.
    return JSONResponse({'detail':'This server does not support this action yet. The app may be newer than the server.'},status_code=404,headers={'X-WCP-Error':'ROUTE_NOT_DEPLOYED'})


@app.get('/{path:path}',include_in_schema=False)
def frontend(path:str):
    if path.startswith('api/'):
        return JSONResponse({'detail':'Unknown API route.'},status_code=404)
    asset=(FRONTEND/path).resolve()
    if asset.is_relative_to(FRONTEND) and asset.is_file():
        return FileResponse(asset,headers={'Cache-Control':'no-cache' if path.endswith(('sw.js','index.html')) else 'public, max-age=3600'})
    if not (FRONTEND/'index.html').exists():return JSONResponse({'service':'WoundCoPilot','frontend':'Build the frontend to open the app.'},status_code=503)
    return FileResponse(FRONTEND/'index.html',headers={'Cache-Control':'no-cache'})


