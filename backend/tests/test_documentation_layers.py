import asyncio,json
from pathlib import Path
from types import SimpleNamespace
import pytest
from server import documentation_layers as layers,providers,documentation
from test_providers import gateway,completion

FIXTURES=json.loads((Path(__file__).parent/'fixtures/documentation_layers_synthetic.json').read_text())

@pytest.mark.parametrize('role',sorted(layers.ROLES))
@pytest.mark.parametrize('report',sorted(layers.TYPES))
def test_role_and_format_are_independent_closed_layers(role,report):
    token=layers.select(SimpleNamespace(profile={'profession':role}),report)
    try:
        prompt=providers.prompt('quick_draft')
        assert f'role={role}; format={report}' in prompt
        assert 'core_documentation_v3' in prompt
        assert ('community_nursing_v1' in prompt)==(role in {'NURSE','OTHER_CLINICIAN'})
    finally:layers.selection.reset(token)

def test_untrusted_profession_is_not_system_instruction():
    token=layers.select(SimpleNamespace(profile={'profession':'SYNTHETIC_USER_INSTRUCTION_SENTINEL'}),'nursing')
    try:
        assert 'SYNTHETIC_USER_INSTRUCTION_SENTINEL' not in providers.prompt('quick_draft')
        assert 'role=OTHER_CLINICIAN' in providers.prompt('quick_draft')
    finally:layers.selection.reset(token)

@pytest.mark.parametrize('case',FIXTURES,ids=lambda c:c['id'])
def test_synthetic_format_pipeline_preserves_original_source_and_exact_accepted_text(gateway,case):
    # Contract test with explicit provider double; NOT evidence of live clinical accuracy.
    gateway.user.profile={'profession':case['role']}
    expected=case['expected'];status=case.get('expected_status','ready')
    gateway.http.responses=[completion({'text':expected,'status':status,'ui_alerts':[]}),completion({'passed':True,'discrepancies':[]})]
    context={'case_facts':[],'untrusted_sources':[{'kind':'audio','text':case['source']}],'rejected_claims_do_not_reinstate':[]}
    result=asyncio.run(providers.quick_documentation(context,case['format'],'concise',user=gateway.user,encounter_id='synthetic',custom_template={'name':'Synthetic layout','instructions':'Findings then Follow-up'} if case['format']=='custom' else None))
    assert result['text']==expected
    for request in gateway.http.calls:
        messages=request['json']['messages']
        assert case['source'] not in messages[0]['content']
        source=json.loads(messages[1]['content'])
        assert source['untrusted_sources']==context['untrusted_sources']
        assert f"role={case['role']}; format={case['format']}" in messages[0]['content']
    for forbidden in case.get('forbidden',[]):assert forbidden.lower() not in result['text'].lower()

def test_cancelled_client_and_correction_rules_present_for_qa_and_generation():
    for stage in ('quick_draft','quick_qa','quick_repair'):
        text=providers.prompt(stage)
        assert 'Latest explicit clinician correction wins' in text
        assert 'never merge their facts' in text
        assert 'Never independently add diagnosis' not in text or 'role=NURSE' in text

def test_consultation_prompt_does_not_receive_documentation_role_or_format():
    assert 'Documentation layers:' not in providers.prompt('consult')
