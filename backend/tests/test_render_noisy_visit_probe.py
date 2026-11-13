"""Noisy-visit reproduction: one fixed made-up visit, intercepted provider calls only, lines fit the startup filter."""
import base64
import importlib.util
import json
import re
from pathlib import Path
import sys

from server import providers, recording_quality
from test_providers import gateway

SCRIPTS = Path(__file__).parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('render_noisy_visit_probe', SCRIPTS / 'render_noisy_visit_probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
START = (SCRIPTS / 'render_test_start.py').read_text(encoding='utf-8')
FILTER = (r'AMILYA_NOISY_VISIT run=[1-9] variant=(?:old|new) stage=(?:quick_draft|quick_qa|quick_repair|final) status=(?:OK|PASS|FAIL) '
          r'code=[A-Z0-9_]{1,40} ms=\d{1,9} effort=(?:low|medium|high|none) out_tokens=\d{1,9} data_b64=[A-Za-z0-9+/=]{0,20000}')


def test_the_visit_is_made_up_and_shaped_like_a_noisy_recording():
    old, new = probe.visit_context('old'), probe.visit_context('new')
    assert probe.FOREIGN in old['untrusted_sources'][0]['text'] and probe.FOREIGN not in new['untrusted_sources'][0]['text']
    # Stored before the fix the recording counts as unreliable; stored after it, it does not.
    assert recording_quality.free_signals(old) == ['speech_service_warning']
    assert recording_quality.free_signals(new) == []
    assert 'Walter' in new['untrusted_sources'][0]['text'] and new['untrusted_sources'][0]['text'].count('Thank you.') > 30


def test_output_lines_pass_the_startup_filter(gateway, capsys, monkeypatch):
    async def fake_routed(task, content, model=None, **kwargs):
        if task == 'quick_qa':
            return {'passed': False, 'discrepancies': ['Lost: client denies pain']}
        return {'text': 'Coccyx wound. Large thick drainage.', 'status': 'ready', 'ui_alerts': [], 'effort_level': 'medium',
                'usage': {'output_tokens': 41}}

    async def fake_report(context, template, length, *, user, encounter_id):
        draft = await providers.routed('quick_draft', '{}', user=user, encounter_id=encounter_id)
        await providers.routed('quick_qa', '{}', user=user, encounter_id=encounter_id)
        return {**draft, 'qa': {'passed': True}}
    monkeypatch.setattr(providers, 'routed', fake_routed)
    monkeypatch.setattr(providers, 'quick_documentation', fake_report)
    assert probe.reproduce(gateway.user, variants=('old', 'new'))
    assert providers.routed is fake_routed   # the tracer is always removed again
    lines = capsys.readouterr().out.splitlines()
    assert len(lines) == 6 and all(re.fullmatch(FILTER, x) for x in lines)
    shown = json.loads(base64.b64decode(lines[1].rsplit('data_b64=', 1)[1]))
    assert shown == {'passed': False, 'discrepancies': ['Lost: client denies pain']}
    assert 'out_tokens=41' in lines[0] and 'stage=final status=PASS' in lines[2]
    assert FILTER in START and "'noisy-visit-v11'" in START


def test_a_failed_step_is_reported_with_a_fixed_code(gateway, capsys, monkeypatch):
    from fastapi import HTTPException

    async def failing(task, content, model=None, **kwargs):
        raise HTTPException(502, 'x', headers={'X-WCP-Error': 'OUTPUT_LIMIT'})

    async def fake_report(context, template, length, *, user, encounter_id):
        return await providers.routed('quick_draft', '{}', user=user, encounter_id=encounter_id)
    monkeypatch.setattr(providers, 'routed', failing)
    monkeypatch.setattr(providers, 'quick_documentation', fake_report)
    assert not probe.reproduce(gateway.user, variants=('new',))
    lines = capsys.readouterr().out.splitlines()
    assert len(lines) == 2 and all(re.fullmatch(FILTER, x) and 'status=FAIL code=OUTPUT_LIMIT' in x for x in lines)
