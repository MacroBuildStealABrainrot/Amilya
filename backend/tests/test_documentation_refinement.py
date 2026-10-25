"""Synthetic contract and failure-injection checks, not live-model quality claims."""
import asyncio,copy,json
import pytest
from fastapi import HTTPException
from server import providers
from test_providers import gateway,completion

CORRECTIONS=[
 ('product','Iodine, sorry not iodine, Bactigras applied.','Bactigras applied.','Iodine applied.'),
 ('laterality','Left toe, sorry right toe, cleansed with NS.','Right toe cleansed with NS.','Left toe cleansed with NS.'),
 ('symptom','No swelling, sorry mild swelling.','Mild swelling.','No swelling.'),
 ('measurement','Wound length 2 cm, I mean 3 cm.','Wound length 3 cm.','Wound length 2 cm.'),
 ('treatment','Dressing changed, actually dressing change declined.','Dressing change declined.','Dressing changed.'),
 ('cancelled','Next client Jordan Maple, ignore that client, already completed. Current client Taylor Birch, right heel cleansed with NS.','Taylor Birch\nRight heel cleansed with NS.','Jordan Maple\nRight heel cleansed with NS.'),
 ('repeated','Left toe, sorry right toe, actually left heel.','Left heel.','Right toe.'),
 ('unrelated','Left toe, sorry right toe. Separate left heel dressing unchanged.','Right toe. Separate left heel dressing unchanged.','Right toe. Separate right heel dressing unchanged.'),
 ('negative','No fever or chills.','No fever or chills.','Fever and chills.'),
 ('appointment','Appointment Friday at 13:30, sorry Monday at 13:30.','Appointment Monday at 13:30.','Appointment Friday at 13:30.'),
 ('medication','Morphine discussed, not administered.','Morphine discussed; not administered.','Morphine administered.'),
 ('multiple_clients','Jordan Maple: right heel pain. Next patient Taylor Birch: left toe pain. Active client is not specified.','','Right heel pain and left toe pain.'),
]

@pytest.mark.parametrize('case',CORRECTIONS,ids=lambda c:c[0])
def test_incorrect_fact_is_repaired_from_unchanged_original_and_checked_again(gateway,case):
    _,source,good,bad=case
    original={'case_facts':[],'untrusted_sources':[{'kind':'audio','text':source}],'rejected_claims_do_not_reinstate':[]}
    before=copy.deepcopy(original)
    def draft(text):return completion({'text':text,'status':'ready' if text else 'no_content','ui_alerts':[]})
    gateway.http.responses=[draft(bad),completion({'passed':False,'discrepancies':['Synthetic incorrect corrected fact or encounter attribution.']}),draft(good),completion({'passed':True,'discrepancies':[]})]
    result=asyncio.run(providers.quick_documentation(original,'nursing','concise',user=gateway.user,encounter_id='synthetic'))
    assert result['text']==good and original==before
    assert len(gateway.http.calls)==4
    for call in gateway.http.calls:
        messages=call['json']['messages'];data=json.loads(messages[1]['content'])
        assert data['untrusted_sources']==before['untrusted_sources']
        assert source not in messages[0]['content']
    final_check=json.loads(gateway.http.calls[-1]['json']['messages'][1]['content'])
    assert 'untrusted_failed_draft' not in final_check and final_check['proposed_draft']==good

@pytest.mark.parametrize('case',CORRECTIONS,ids=lambda c:c[0])
def test_second_failed_qa_never_returns_an_invented_fallback(gateway,case):
    _,source,_,bad=case
    context={'case_facts':[],'untrusted_sources':[{'kind':'audio','text':source}]}
    before=copy.deepcopy(context)
    draft=completion({'text':bad,'status':'ready','ui_alerts':[]});qa=completion({'passed':False,'discrepancies':['Synthetic factual mismatch.']})
    gateway.http.responses=[draft,qa,draft,qa]
    with pytest.raises(HTTPException) as exc:asyncio.run(providers.quick_documentation(context,'nursing','concise',user=gateway.user,encounter_id='synthetic'))
    assert exc.value.status_code==502 and context==before and len(gateway.http.calls)==4

def test_speech_keeps_correction_cues_without_local_normalization(gateway,monkeypatch):
    source='Left toe, sorry right toe. Iodine, no Bactigras. Next client Jordan Maple, ignore that.'
    gateway.config.cloudflare_api_token='synthetic';gateway.config.cloudflare_account_id='synthetic'
    gateway.http.responses=[{'success':True,'result':{'text':source}}]
    result=asyncio.run(providers.speech_attempt('cloudflare',b'synthetic','audio/wav','synthetic.wav',30,user=gateway.user,encounter_id='synthetic'))
    assert result['text']==source

def test_style_and_verification_are_contextual():
    from server.documentation_layers import ROOT
    style=(ROOT/'community_nursing_v1.md').read_text()
    assert 'Canadian spelling' in style and 'PICC insertion site' in style
    verifier=(ROOT.parent/'30_transcript_verification.system.md').read_text()
    assert 'correction cues' in verifier
