"""Blind Haiku report comparison: fixed fictional inputs, intercepted provider calls only."""
import base64
import importlib.util
import re
from pathlib import Path
import sys

from server import providers
from test_providers import gateway

SCRIPTS = Path(__file__).parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('render_report_comparison', SCRIPTS / 'render_report_comparison.py')
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)
START = (SCRIPTS / 'render_test_start.py').read_text(encoding='utf-8')


def test_haiku_is_an_approved_exact_model():
    assert comparison.CANDIDATE in providers.MODEL_PRICES
    assert providers.model_identity_matches(comparison.CANDIDATE, 'anthropic/claude-haiku-5.5-20261007')
    assert not providers.model_identity_matches(comparison.CANDIDATE, 'anthropic/claude-haiku-5.5-29991231')


def test_cases_are_fictional_and_fit_the_log_filter():
    assert len(comparison.CASES) == 4
    assert all('Fictional training visit, no real patient' in text for _, text in comparison.CASES)


def test_output_lines_pass_the_startup_filter(gateway, capsys, monkeypatch):
    async def fake(context, template, length, *, user, encounter_id):
        model = providers.settings.model_documentation
        return {'text': 'Report by ' + model, 'qa': {'passed': True}}
    monkeypatch.setattr(providers, 'quick_documentation', fake)
    original = providers.settings.model_documentation
    assert comparison.compare(gateway.user)
    assert providers.settings.model_documentation == original
    lines = capsys.readouterr().out.splitlines()
    report = re.compile(r'AMILYA_REPORT_COMPARE case=[1-4] variant=[AB] status=(?:PASS|FAIL) qa=(?:PASS|FAIL) latency_ms=\d{1,9} text_b64=[A-Za-z0-9+/=]{0,8200}')
    key = re.compile(r'AMILYA_REPORT_COMPARE_KEY case=[1-4] A=(?:current|haiku) B=(?:current|haiku)')
    assert len(lines) == 12 and all(report.fullmatch(x) or key.fullmatch(x) for x in lines)
    keys = {int(m.group(1)): (m.group(2), m.group(3)) for m in
            (re.fullmatch(r'AMILYA_REPORT_COMPARE_KEY case=(\d) A=(\w+) B=(\w+)', x) for x in lines) if m}
    for line in lines:
        m = re.fullmatch(r'AMILYA_REPORT_COMPARE case=(\d) variant=([AB]) .* text_b64=(.*)', line)
        if m:
            who = keys[int(m.group(1))][0 if m.group(2) == 'A' else 1]
            expected = comparison.CANDIDATE if who == 'haiku' else comparison.CURRENT
            assert base64.b64decode(m.group(3)).decode() == 'Report by ' + expected
    assert "report-haiku-v9" in START and 'AMILYA_REPORT_COMPARE_KEY' in START
