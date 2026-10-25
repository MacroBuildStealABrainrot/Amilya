"""Adversarial provider/schema/routing cases. No live inference."""
import json
import pytest
from server import providers, billing
from server.kosmo import select_route
from server import identity
from fastapi import HTTPException
from starlette.requests import Request

@pytest.mark.parametrize('task,payload', [
    ('assistant', {'answer':' \n\t ', 'considerations':[]}),
    ('draft', {'text':' \n\t '}),
    ('review', {'considerations':[], 'warnings':'Critical warning'}),
    ('review', {'considerations':[], 'warnings':[{'injected':'object'}]}),
])
def test_malformed_model_output_is_rejected(task,payload):
    with pytest.raises(ValueError): providers.validate_result(payload,task)

@pytest.mark.parametrize('payload', [
    '{"passed":false,"passed":true,"discrepancies":[]}',
    '{"text":"first","text":"overwritten"}',
    '{"facts":[],"warnings":[],"unexpected":NaN}',
])
def test_ambiguous_json_is_rejected(payload):
    with pytest.raises(ValueError): providers.parse_provider_json(payload)

@pytest.mark.parametrize('question', [
    'List the possible causes of this wound and compare their likelihood.',
    'Summarize whether this wound is infected.',
    'Repeat the reasoning about the best care option.',
])
def test_simple_word_cannot_downgrade_clinical_reasoning(question):
    route=select_route('assistant',json.dumps({'untrusted_question':question}))
    assert route['effort_level'] != 'low'

@pytest.mark.parametrize('value',[[], {}, 1, None])
def test_usage_filter_tolerates_malformed_routing(value):
    assert 'routing' not in billing._safe_usage({'routing':{'effort_level':value,'model':'untrusted'}})

def test_non_ascii_csrf_header_is_forbidden_not_server_error():
    request=Request({'type':'http','headers':[(b'x-csrf-token', b'\xff')],'method':'POST','path':'/'})
    with pytest.raises(HTTPException) as caught: identity.require_csrf(request,user=object())
    assert caught.value.status_code == 403
