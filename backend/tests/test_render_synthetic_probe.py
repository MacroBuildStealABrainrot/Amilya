"""No network calls: verify the private probe's boundaries and failure reporting."""
import asyncio
import importlib.util
import io
from pathlib import Path
import sys
from types import SimpleNamespace

from fastapi import HTTPException
from PIL import Image
import pytest
from test_providers import gateway, completion
from test_identity import client

SCRIPTS = Path(__file__).parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('render_synthetic_probe', SCRIPTS / 'render_synthetic_probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def environment():
    return dict(RENDER='true', AMILYA_RENDER_TEST='true',
        RENDER_EXTERNAL_HOSTNAME='amilya-repair-test.onrender.com',
        AMILYA_RENDER_DB_HOST='dpg-synthetic-a',
        DATABASE_URL='postgresql://test:synthetic-password@dpg-synthetic-a/amilya_repair_test_db',
        APP_SECRET='synthetic-storage-secret-' * 3, PORT='10000',
        OPENROUTER_API_KEY='synthetic-not-a-real-key',
        AMILYA_RENDER_SYNTHETIC_PROBE=probe.VERSION)


@pytest.mark.parametrize('changes', [
    {'RENDER': 'false'}, {'AMILYA_RENDER_SYNTHETIC_PROBE': ''},
    {'AMILYA_RENDER_SYNTHETIC_PROBE': 'other'}, {'OPENROUTER_API_KEY': ''},
    {'OPENROUTER_BASE_URL': 'https://untrusted.example'},
    {'CLINICAL_DATA_ENABLED': 'true'}, {'PROVIDER_EXECUTION_ENABLED': 'true'},
    {'DATABASE_URL': 'postgresql://test:secret@10.30.0.10/woundcopilot'},
])
def test_unsafe_context_cannot_claim_or_call_provider(monkeypatch, capsys, changes):
    monkeypatch.setattr(probe.os, 'environ', environment() | changes)
    def forbidden(*args):
        pytest.fail('Unsafe probe reached durable claim')
    monkeypatch.setattr(probe, 'claim_once', forbidden)
    assert probe.main() == 1
    assert capsys.readouterr().out == 'AMILYA_SYNTHETIC_PROBE status=BLOCKED\n'


def test_existing_claim_stops_before_provider_import_or_execution(monkeypatch, capsys):
    monkeypatch.setattr(probe.os, 'environ', environment())
    monkeypatch.setattr(probe, 'claim_once', lambda env: False)
    assert probe.main() == 0
    assert capsys.readouterr().out == 'AMILYA_SYNTHETIC_PROBE status=ALREADY_CLAIMED\n'


@pytest.mark.parametrize('version', [probe.HTTP_VERSION, probe.AUDIO_HTTP_VERSION, probe.PHOTO_HTTP_VERSION])
def test_each_http_probe_has_a_separate_once_only_claim(monkeypatch, capsys, version):
    from types import SimpleNamespace
    import render_owner_activation
    monkeypatch.setattr(probe.os, 'environ', environment() | {'AMILYA_RENDER_SYNTHETIC_PROBE':version})
    monkeypatch.setattr(probe, 'time', SimpleNamespace(time=lambda:render_owner_activation.APPROVAL_END - 3600))
    monkeypatch.setattr(render_owner_activation, 'check_key', lambda *args: None)
    monkeypatch.setattr(probe, 'claim_once', lambda env: False)
    assert probe.main() == 0
    assert capsys.readouterr().out == 'AMILYA_SYNTHETIC_PROBE status=ALREADY_CLAIMED\n'


def test_image_is_only_fixed_geometric_shapes_with_no_metadata():
    with Image.open(io.BytesIO(probe.synthetic_image())) as image:
        assert image.size == (384, 384)
        assert image.getpixel((0, 0)) == (255, 255, 255)
        assert image.getpixel((64, 64)) == (0, 0, 255)
        assert not image.getexif()
        assert not image.info


@pytest.mark.parametrize('exc, expected', [
    (ValueError('PRIVATE clinical text and key'), 'CHECK_FAILED'),
    (HTTPException(502, 'PRIVATE', headers={'X-WCP-Provider-Error': 'MrBell_warfarin_5mg'}), 'HTTP_502'),
    (HTTPException(502, 'PRIVATE', headers={'X-WCP-Provider-Error': 'invalid_image'}), 'INVALID_IMAGE'),
    (HTTPException(502, 'PRIVATE', headers={'X-WCP-Error': 'INVALID_RESPONSE'}), 'INVALID_RESPONSE'),
    (HTTPException(502, 'PRIVATE', headers={'X-WCP-Error': 'MrBell_warfarin_5mg'}), 'HTTP_502'),
])
def test_errors_emit_only_fixed_codes(exc, expected):
    assert probe.error_code(exc) == expected


def test_workflows_use_fixed_sources_and_check_photo_and_report(monkeypatch, capsys):
    from server import providers
    calls = []
    async def photo(data, mime, kind, **kwargs):
        assert data == probe.synthetic_image()
        calls.append('photo')
        return {'facts': [], 'quality': {'retake_required': True}}
    async def extract(text, kind, **kwargs):
        assert text == probe.TEXT
        calls.append('extract')
        return {'facts': [{'name': 'narrative', 'value': {'text': 'Fictional'}, 'quote': 'Fictional'}]}
    async def report(context, template, length, **kwargs):
        assert context['untrusted_sources'][0]['text'] == probe.TEXT
        calls.append('report')
        return {'text': 'Fictional training report.', 'qa': {'passed': True}}
    monkeypatch.setattr(providers, 'analyze_media', photo)
    monkeypatch.setattr(providers, 'extract_text', extract)
    monkeypatch.setattr(providers, 'quick_documentation', report)
    assert asyncio.run(probe.check_workflows(SimpleNamespace()))
    assert calls == list(probe.STAGES)
    assert capsys.readouterr().out == ''.join(
        'AMILYA_SYNTHETIC_PROBE stage=' + stage + ' status=PASS\n' for stage in probe.STAGES)


def test_photo_fabrication_and_unverified_report_cannot_pass(monkeypatch, capsys):
    from server import providers
    async def photo(*args, **kwargs):
        return {'facts': [{'name': 'wound_tissue'}], 'quality': {'retake_required': False}}
    async def extract(*args, **kwargs):
        raise HTTPException(502, 'SECRET', headers={'X-WCP-Provider-Error': 'SECRET'})
    async def report(*args, **kwargs):
        return {'text': 'Some text', 'qa': {'passed': False}}
    monkeypatch.setattr(providers, 'analyze_media', photo)
    monkeypatch.setattr(providers, 'extract_text', extract)
    monkeypatch.setattr(providers, 'quick_documentation', report)
    assert not asyncio.run(probe.check_workflows(SimpleNamespace()))
    output = capsys.readouterr().out
    assert 'SECRET' not in output
    assert output.count('status=FAIL') == 3


def test_probe_uses_actual_gateway_validation_and_ledger(gateway, capsys):
    gateway.http.responses = [
        completion({'facts': [], 'warnings': [], 'quality': {'status': 'insufficient', 'reason': 'no_wound'}}),
        completion({'facts': [{'name': 'treatment_performed',
            'value': {'text': 'Cleansed with normal saline'}, 'quote': 'cleansed the wound with normal saline'}], 'warnings': []}),
        completion({'status': 'ready', 'text': 'Fictional training visit: old dressing removed, wound cleansed with normal saline, and foam dressing applied. No pain reported during the dressing change.', 'ui_alerts': []}),
        completion({'passed': True, 'discrepancies': []}),
    ]
    assert asyncio.run(probe.check_workflows(gateway.user))
    assert len(gateway.http.calls) == 4
    for call in gateway.http.calls:
        assert call['json']['provider'] == {'zdr': True, 'data_collection': 'deny', 'require_parameters': True}
    assert capsys.readouterr().out.count('status=PASS') == 3


def test_extraction_only_check_does_not_repeat_photo_or_report(gateway, capsys):
    gateway.config.model_extraction = 'openai/gpt-6.1-sol'
    gateway.http.responses = [completion({'facts': [{'name': 'treatment_performed',
        'value': {'text': 'Cleansed with normal saline'}, 'quote': 'cleansed the wound with normal saline'}], 'warnings': []})]
    assert asyncio.run(probe.check_workflows(gateway.user, ('extract',)))
    assert len(gateway.http.calls) == 1
    assert gateway.http.calls[0]['json']['model'] == 'openai/gpt-6.1-sol'
    assert capsys.readouterr().out == 'AMILYA_SYNTHETIC_PROBE stage=extract status=PASS\n'


def test_http_probe_uses_native_auth_and_normal_upload_and_report_routes(client, monkeypatch, capsys):
    from server import db as database, providers, identity
    from server.core import settings
    _, factory = client
    monkeypatch.setattr(database, 'SessionLocal', factory)
    calls = []
    async def photo(data, mime, kind, **kwargs):
        assert data == probe.synthetic_image() and kind == 'photo'
        assert kwargs['user'].is_platform_admin is False
        calls.append('photo')
        return {'facts': [], 'warnings': [], 'quality': {'status': 'insufficient',
            'reason': 'no_wound', 'retake_required': True, 'message': 'Retake the photo.'}}
    async def extract(text, kind, **kwargs):
        assert text == probe.TEXT
        calls.append('extract')
        return {'facts': [{'name': 'treatment_performed', 'value': {'text': 'Cleansed with normal saline'},
            'quote': 'cleansed the wound with normal saline'}], 'warnings': []}
    async def report(context, template, preferred_length, **kwargs):
        assert context['untrusted_sources'][0]['text'] == probe.TEXT
        calls.append('report')
        return {'text': 'Fictional training report.', 'qa': {'passed': True}}
    monkeypatch.setattr(providers, 'analyze_media', photo)
    monkeypatch.setattr(providers, 'extract_text', extract)
    monkeypatch.setattr(providers, 'quick_documentation', report)
    assert probe.check_http_workflows()
    assert calls == list(probe.STAGES)
    assert capsys.readouterr().out.count('status=PASS') == 3


def test_audio_http_probe_checks_recording_to_report_through_real_routes(client, gateway, monkeypatch, capsys):
    from server import db as database, providers
    _, factory = client
    monkeypatch.setattr(database, 'SessionLocal', factory)
    monkeypatch.setattr(providers, 'SessionLocal', factory)
    gateway.config.openrouter_speech_enabled = True
    gateway.http.responses = [
        {'text':'Synthetic software test only. The dressing was changed. Pain score is two out of ten.',
         'segments':[{'start':0,'end':5.0,'text':'The dressing was changed. Pain score is two out of ten.'}],
         'usage':{'cost':.00003984}},
        completion({'facts':[{'name':'treatment_performed','value':{'text':'Dressing changed'},
            'quote':'The dressing was changed.'}], 'warnings':[]}),
        completion({'status':'ready','text':'The dressing was changed. Pain score was two out of ten.','ui_alerts':[]}),
        completion({'passed':True,'discrepancies':[]}),
    ]
    assert probe.check_http_workflows(audio=True)
    assert gateway.http.calls[0]['url'].endswith('/audio/transcriptions')
    assert len(gateway.http.calls) == 4
    assert capsys.readouterr().out.count('status=PASS') == 2
