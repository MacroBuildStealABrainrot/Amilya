"""Loopback-only synthetic transport harness; never receives production credentials."""
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from urllib.parse import urlsplit
from uuid import uuid4
import json,time,threading,socket,hashlib
from email.parser import BytesParser
from email.policy import default
import httpx
lock=threading.RLock()
state={'delay':0.4,'open_delay':0.8,'failure':'','calls':{},'cases':{},'parts':{},'cache':{},'live':False,'client':None}
def live_client():
    if state['client'] is None:
        c=httpx.Client(base_url='http://127.0.0.1:18767',timeout=280,headers={'X-WCP-Client':'android'})
        config=c.get('/api/v1/config').json()
        result=c.post('/api/v1/auth/signup',json={'email':f'benchmark-{uuid4()}@example.invalid','password':f'Synthetic-{uuid4()}!','display_name':'Synthetic benchmark','workspace_name':'SYNTHETIC ONLY','accept_terms':True,'terms_version':config['legal_version'],'privacy_version':config['legal_version']})
        result.raise_for_status();session=result.json();c.headers['X-CSRF-Token']=session['csrf_token']
        c.post('/evaluation/unlimited',json={'id':session['user']['id']}).raise_for_status()
        state['client']=c
    return state['client']
def case(identifier):
    return {'patient':{'id':'synthetic','display_name':'Synthetic'},'wound':None,'encounter':{'id':identifier,'patient_id':'synthetic','title':'Synthetic visit','revision':1,'status':'open','updated_at':'2026-09-09T12:00:00Z','draft_data':{'quick_mode':'document','retention':'temporary'}},'evidence':[],'facts':[],'documents':[],'review':None,'conflicts':[]}
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):self.handle_request()
    def do_POST(self):self.handle_request()
    def handle_request(self):
        if state.get('drop_upload_once') and self.path.endswith('/audio-parts'):
            state['drop_upload_once']=False
            self.rfile.read(min(1024,int(self.headers.get('Content-Length','0'))))
            self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
        raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
        try:body=json.loads(raw or '{}') if 'json' in self.headers.get('Content-Type','') else {}
        except ValueError:body={}
        path=urlsplit(self.path).path.removeprefix('/api/v1');route=path.rsplit('/',1)[-1]
        status=200
        if path=='/test/config':
            with lock:
                if body.pop('reset',False):state.update(calls={},cases={},parts={},cache={})
                state.update({k:v for k,v in body.items() if k in ('delay','open_delay','failure','live','drop_upload_once','drop_response_once','malformed_once','pending_once')})
            result={'ok':True}
        elif path=='/test/stats':result={'calls':state['calls']}
        else:
            with lock:state['calls'][route]=state['calls'].get(route,0)+1
            if state['live']:
                if route=='logout':result={}
                else:
                    response=live_client().request(self.command,'/api/v1'+path,content=raw,headers={'Content-Type':self.headers.get('Content-Type','application/json'),**({'Idempotency-Key':self.headers['Idempotency-Key']} if self.headers.get('Idempotency-Key') else {})})
                    status=response.status_code;result=response.json()
                return self.respond(status,result)
            time.sleep(state['open_delay'] if route=='start' else state['delay'])
            key=(path,self.headers.get('Idempotency-Key'))
            if state['failure']==route or (state['failure']=='report-ai' and route=='quick-report' and body.get('use_ai',True)):status=503;result={'detail':'Synthetic stage failure'}
            elif key in state['cache'] and self.command=='POST':result=state['cache'][key]
            elif route=='start':
                identifier=body['request_id'];state['cases'].setdefault(identifier,case(identifier));result=state['cases'][identifier];status=201
            elif route=='upload':
                message=BytesParser(policy=default).parsebytes(('Content-Type: '+self.headers['Content-Type']+'\r\nMIME-Version: 1.0\r\n\r\n').encode()+raw)
                image=next(p.get_payload(decode=True) for p in message.iter_parts() if p.get_param('name',header='content-disposition')=='file')
                c=state['cases'][path.split('/')[2]]
                e={'id':str(uuid4()),'kind':'photo','text':None,'metadata':{'photo_quality':{'status':'insufficient','reason':'no_wound','retake_required':True,'message':'No wound is visible in this synthetic image.'}},'warnings':[]}
                c['evidence'].append(e);c['encounter']['revision']+=1
                result={'evidence':e,'facts':[],'case_revision':c['encounter']['revision'],'warnings':[]}
                state['calls']['photo_successes']=state['calls'].get('photo_successes',0)+1
                state['calls']['last_photo_hash']=hashlib.sha256(image).hexdigest()
            elif route=='audio-parts':result={'index':0,'status':'transcribed','id':str(uuid4())}
            elif route=='audio-complete':
                c=state['cases'][path.split('/')[2]];e={'id':str(uuid4()),'kind':'audio','text':'Synthetic patient reports pain 2 out of 10. No fever reported.','metadata':{},'warnings':[]}
                c['evidence']=[e];c['encounter']['revision']=2
                result={'evidence':e,'facts':[],'case_revision':2,'warnings':[]}
            elif route=='quick-report':
                state['calls']['reports_created']=state['calls'].get('reports_created',0)+1
                c=state['cases'][path.split('/')[2]]
                result={'text':'Patient reports pain 2/10. No fever reported.','template':body.get('template','nursing'),'case_revision':c['encounter']['revision'],'status':'ready','qa':{'passed':True},'warnings':[]}
            elif path.startswith('/encounters/'):result=state['cases'].get(route,case(route))
            elif route in ('work','patients','encounters'):result=[]
            elif route=='session':result={'user':{'id':'reliability-synthetic','workspace_id':'synthetic','display_name':'Synthetic test'},'csrf_token':'synthetic','requires_legal_acceptance':False}
            else:result={}
            if self.command=='POST' and status<300 and self.headers.get('Idempotency-Key'):state['cache'][key]=result
            if state.get('drop_response_once')==route:
                state['drop_response_once']=''
                self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
            if state.get('malformed_once')==route:
                state['malformed_once']=''
                self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length','6');self.end_headers();self.wfile.write(b'broken');return
            if state.get('pending_once')==route:
                state['pending_once']=''
                return self.respond(409,{'detail':'This AI request is already processing or awaiting reconciliation. Refresh the case before retrying.'})
        self.respond(status,result)
    def respond(self,status,result):
        encoded=json.dumps(result).encode()
        self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(encoded)));self.end_headers()
        try:self.wfile.write(encoded)
        except (BrokenPipeError,ConnectionResetError):pass
ThreadingHTTPServer(('127.0.0.1',18766),Handler).serve_forever()
