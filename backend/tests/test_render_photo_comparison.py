"""Synthetic benchmark tests: all provider responses are intercepted."""
import importlib.util
from pathlib import Path
import sys
import time
from types import SimpleNamespace

import pytest
from test_identity import client
from test_providers import gateway, completion

SCRIPTS = Path(__file__).parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('render_photo_comparison', SCRIPTS/'render_photo_comparison.py')
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


def shape_result():
    return {'facts':[], 'warnings':[], 'quality':{'status':'insufficient','reason':'no_wound'}}


def wound_result():
    return {'facts':[{'name':name,'value':{'text':text},'quote':''} for name,text in (
        ('wound_tissue','Moist granulation tissue with adherent slough.'),
        ('wound_edges','Edges well defined.'),('periwound','Periwound mildly erythematous.'))],
        'warnings':[], 'quality':{'status':'sufficient','reason':'none'}}


def test_profile_changes_only_image_effort_and_always_restores(gateway):
    from server import providers
    original = providers.configured_route
    gateway.config.model_vision=comparison.MODEL
    non_photo = original('extract','{}')
    with comparison.reasoning_profile('medium'):
        route = providers.configured_route('image','{}')
        assert route['model']==comparison.MODEL and route['effort_level']=='medium'
        assert providers.configured_route('extract','{}')==non_photo
    assert providers.configured_route is original
    with pytest.raises(RuntimeError):
        with comparison.reasoning_profile('high'):
            raise RuntimeError('synthetic')
    assert providers.configured_route is original


def test_unreviewed_profile_or_model_cannot_run(gateway):
    from server import providers
    with pytest.raises(ValueError):
        with comparison.reasoning_profile('low'):
            pass
    gateway.config.model_vision='anthropic/claude-sonnet-5.5'
    with comparison.reasoning_profile('medium'):
        with pytest.raises(ValueError):
            providers.configured_route('image','{}')


def test_cost_is_actual_or_unknown_and_never_an_estimate():
    def row(cost,usage=None):
        return SimpleNamespace(actual_cost_micros=cost,normalized_micros=999999,
            usage=usage or {'input_tokens':100,'output_tokens':20,'cached_tokens':10})
    assert comparison.safe_metrics([row(100),row(30)],.125)=={
        'cost_micros':130,'latency_ms':125,'input_tokens':200,'output_tokens':40,'cached_tokens':20}
    assert comparison.safe_metrics([row(None)],1)['cost_micros']=='UNKNOWN'
    assert comparison.safe_metrics([row(True)],1)['cost_micros']=='UNKNOWN'
    assert comparison.safe_metrics([],float('inf'))['cost_micros']=='UNKNOWN'


def test_output_contains_only_fixed_labels_and_numeric_metrics():
    line=comparison.metric_line('high','wound',True,3,{
        'cost_micros':'SECRET clinical text','latency_ms':10,'input_tokens':100,
        'output_tokens':20,'cached_tokens':0})
    assert 'SECRET' not in line and 'cost_micros=UNKNOWN' in line
    with pytest.raises(ValueError):
        comparison.metric_line('patient_name','wound',True,3,{})


def test_backup_model_cannot_be_reported_as_a_same_model_comparison():
    row=SimpleNamespace(model=comparison.MODEL,provider='openrouter')
    assert comparison.primary_only([row])
    assert not comparison.primary_only([])
    assert not comparison.primary_only([row,SimpleNamespace(model='anthropic/claude-sonnet-5.5',provider='openrouter')])


def test_gate_rejects_missing_coverage_percentages_and_confirmation():
    body={'facts':[{'name':n,'value':{'text':'Synthetic observation'},'confirmation_state':'unconfirmed'}
                   for n in comparison.CORE_FIELDS],
          'photo_quality':{'status':'sufficient','retake_required':False}}
    assert comparison.quality_gate('wound',201,body)==(True,3)
    body['facts'][0]['value']={'text':'80% granulation'}
    assert comparison.quality_gate('wound',201,body)[0] is False
    body['facts']=body['facts'][1:]
    assert comparison.quality_gate('wound',201,body)[0] is False
    body['facts'][0]['confirmation_state']='confirmed'
    assert comparison.quality_gate('wound',201,body)[0] is False


def test_comparison_uses_normal_http_and_identical_images_without_public_change(client,gateway,monkeypatch,capsys):
    from server import db as database,providers
    import render_owner_activation
    _,factory=client
    monkeypatch.setattr(database,'SessionLocal',factory)
    monkeypatch.setattr(providers,'SessionLocal',factory)
    monkeypatch.setattr(render_owner_activation,'APPROVAL_END',int(time.time())+3600)
    gateway.config.model_vision=comparison.MODEL
    gateway.http.responses=[completion(shape_result()),completion(wound_result()),
                            completion(shape_result()),completion(wound_result())]
    original=providers.configured_route
    assert comparison.compare()
    assert providers.configured_route is original
    calls=[c['json'] for c in gateway.http.calls]
    assert [p['reasoning']['effort'] for p in calls]==['high','high','medium','medium']
    assert all(p['model']==comparison.MODEL and p['provider']['zdr'] is True and
               p['provider']['data_collection']=='deny' for p in calls)
    assert calls[0]['messages']==calls[2]['messages']
    assert calls[1]['messages']==calls[3]['messages']
    lines=capsys.readouterr().out.splitlines()
    assert len(lines)==4 and all('status=PASS' in line and 'cost_micros=1000' in line for line in lines)
    assert all('Synthetic observation' not in line and 'granulation' not in line for line in lines)


@pytest.mark.parametrize('activated', [False, True])
def test_comparison_cannot_start_after_original_deadline(client,gateway,monkeypatch,activated):
    from server import db as database,providers
    import render_owner_activation
    _,factory=client
    monkeypatch.setattr(database,'SessionLocal',factory)
    monkeypatch.setattr(providers,'SessionLocal',factory)
    monkeypatch.delenv('AMILYA_RENDER_OWNER_TEST_UNTIL',raising=False)
    monkeypatch.setattr(render_owner_activation,'APPROVAL_END',int(time.time())+(3600 if activated else -1))
    if activated:
        monkeypatch.setenv('AMILYA_RENDER_OWNER_TEST_UNTIL',str(int(time.time())-1))
    with pytest.raises(ValueError,match='Test window too short'):
        comparison.compare()
    assert not gateway.http.calls
