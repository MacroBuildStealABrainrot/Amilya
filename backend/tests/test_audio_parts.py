from uuid import uuid4
from io import BytesIO
import wave
from fastapi import HTTPException
from server import audio_parts, providers, quick_workflows
from test_clinical import clinical_api
from test_quick_workflows import quick_api,start

def audio():
    stream=BytesIO()
    with wave.open(stream,'wb') as wav:wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(16000);wav.writeframes(b'\0\0'*1600)
    return stream.getvalue()

def test_chunk_order_complete_only_and_exact_retry(quick_api,monkeypatch):
    api=quick_api;api.client.app.include_router(audio_parts.router,prefix='/api/v1')
    case=start(api);id=case['encounter']['id'];job=str(uuid4());calls=[]
    monkeypatch.setattr(providers,'audio_duration',lambda _:120.0)
    async def transcribe(*args,**kwargs):
        calls.append(True);return {'text':['Right heel. No fever.','Dose 5 mg tomorrow.'][len(calls)-1],'segments':[{'text':'Synthetic','start':0,'end':1}],'warnings':[]}
    monkeypatch.setattr(providers,'transcribe',transcribe)
    def part(index):return api.client.post(f'/api/v1/encounters/{id}/audio-parts',files={'file':('synthetic.wav',audio(),'audio/wav')},data={'job_id':job,'index':index,'count':2,'consent':'true'})
    assert part(0).status_code==200
    assert part(0).status_code==200 and len(calls)==1
    assert api.client.get(f'/api/v1/encounters/{id}').json()['evidence']==[]
    assert api.client.post(f'/api/v1/encounters/{id}/audio-complete',json={'job_id':job,'count':2}).status_code==409
    assert part(1).status_code==200
    result=api.client.post(f'/api/v1/encounters/{id}/audio-complete',json={'job_id':job,'count':2})
    assert result.status_code==200,result.text
    assert result.json()['evidence']['text']=='Right heel. No fever.\nDose 5 mg tomorrow.'
    assert result.json()['evidence']['metadata']['segments'][1]['start']==120
    replay=api.client.post(f'/api/v1/encounters/{id}/audio-complete',json={'job_id':job,'count':2})
    assert replay.json()['evidence']['id']==result.json()['evidence']['id']
    assert len(calls)==2

def test_segment_failure_retains_other_segments(quick_api,monkeypatch):
    api=quick_api;api.client.app.include_router(audio_parts.router,prefix='/api/v1')
    id=start(api)['encounter']['id'];job=str(uuid4());counter=0
    monkeypatch.setattr(providers,'audio_duration',lambda _:120.0)
    async def transcribe(*args,**kwargs):
        nonlocal counter
        counter+=1
        if counter==2:raise HTTPException(504,'Synthetic timeout')
        return {'text':'No allergy reported.','warnings':[]}
    monkeypatch.setattr(providers,'transcribe',transcribe)
    def part(index):return api.client.post(f'/api/v1/encounters/{id}/audio-parts',files={'file':('synthetic.wav',audio(),'audio/wav')},data={'job_id':job,'index':index,'count':2,'consent':'true'})
    assert part(0).status_code==200
    assert part(1).status_code==504
    assert part(0).status_code==200 and counter==2
    assert part(1).status_code==200 and counter==3
