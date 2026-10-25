"""Monotonic stage measurements with a strict content-free event schema."""
import asyncio,contextvars,functools,hashlib,logging,time
job_id=contextvars.ContextVar('performance_job',default='')
logger=logging.getLogger('woundcopilot.performance')
def timed(stage):
    assert stage in {'ai_stage','transcription','report_pipeline'}
    def decorate(function):
        @functools.wraps(function)
        async def measured(*args,**kwargs):
            started=time.perf_counter_ns();status='ok'
            label=stage
            if stage=='ai_stage' and args and args[0] in {'quick_draft','quick_qa','quick_repair','quick_refine','improve_report'}:label=args[0]
            try:return await function(*args,**kwargs)
            except asyncio.CancelledError:
                status='cancelled';raise
            except Exception as error:
                code=getattr(error,'status_code',None)
                status='timeout' if code==504 else 'failed'
                raise
            finally:
                opaque=hashlib.sha256(job_id.get().encode()).hexdigest()[:20]
                logger.info('stage=%s duration_ms=%.3f status=%s job=%s',label,(time.perf_counter_ns()-started)/1_000_000,status,opaque)
        return measured
    return decorate
