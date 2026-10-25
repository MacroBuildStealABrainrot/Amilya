import asyncio
import logging
import pytest
from server import performance


@pytest.mark.parametrize('fails',[False,True])
def test_stage_metrics_never_include_inputs_or_exception_content(caplog,fails):
    sentinel='PRIVATE-SYNTHETIC-CONTENT-NOT-FOR-LOGS'
    @performance.timed('ai_stage')
    async def task(stage,body):
        if fails:raise RuntimeError(sentinel)
        return {'text':sentinel}
    token=performance.job_id.set(sentinel)
    try:
        with caplog.at_level(logging.INFO,logger='woundcopilot.performance'):
            if fails:
                with pytest.raises(RuntimeError):asyncio.run(task('quick_draft',sentinel))
            else:assert asyncio.run(task('quick_draft',sentinel))=={'text':sentinel}
    finally:performance.job_id.reset(token)
    assert sentinel not in caplog.text
    message=next(record.message for record in caplog.records if record.name=='woundcopilot.performance')
    assert 'stage=quick_draft' in message
    assert ('status=failed' if fails else 'status=ok') in message
    assert 'duration_ms=' in message


def test_unknown_task_name_is_not_logged(caplog):
    @performance.timed('ai_stage')
    async def task(name):return True
    with caplog.at_level(logging.INFO,logger='woundcopilot.performance'):
        asyncio.run(task('SENSITIVE-UNEXPECTED-TASK'))
    assert 'SENSITIVE-UNEXPECTED-TASK' not in caplog.text
    assert 'stage=ai_stage' in caplog.text


def test_cancelled_stage_is_not_reported_successful(caplog):
    @performance.timed('transcription')
    async def task():raise asyncio.CancelledError()
    with caplog.at_level(logging.INFO,logger='woundcopilot.performance'):
        with pytest.raises(asyncio.CancelledError):asyncio.run(task())
    assert 'status=cancelled' in caplog.text
