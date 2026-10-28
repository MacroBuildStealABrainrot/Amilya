import {afterEach,expect,it,vi} from 'vitest';
import {act,cleanup,fireEvent,render,screen,waitFor,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {useState} from 'react';
import {Notebook} from './Notebook';
import {AudioVerificationPanel} from './AudioVerificationPanel';
import {EvidencePanel} from './EvidencePanel';
import {DemoRepository} from './demo';
import {Workspace} from './workspace';
import {api} from './api';
import App from './App';
import type {Draft} from './types';

afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals()});
function notebook(){const demo=new DemoRepository(),workspace=new Workspace('demo',demo),initial=demo.get(demo.list()[0].id);function Harness(){const [data,setData]=useState(initial),[draft,setDraft]=useState<Draft>({text:'',template:'nursing',updatedAt:0,caseRevision:initial.encounter.revision});return <Notebook data={data} workspace={workspace} config={null} recovery={null} draft={draft} onDraft={setDraft} onCase={setData} mode="auto" onRecoveryChange={()=>{}} localStatus="Demo"/>}render(<Harness/>);return {demo,initial}}

it('saves exact custom report formatting with its signed version and restores it from history',async()=>{
  const {demo,initial}=notebook();const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'Document'}));
  await user.click(screen.getByRole('button',{name:'Custom layout'}));
  expect((screen.getByRole('button',{name:/Save exact draft/})as HTMLButtonElement).disabled).toBe(true);
  await user.type(screen.getByLabelText('Report name'),'  Community visit update  ');await user.type(screen.getByLabelText('Formatting instructions'),'  Use headings: observations and actions.  ');
  await user.type(screen.getByLabelText('Clinical document text'),'  EXACT CUSTOM NOTE{Enter}SECOND LINE{Enter}');
  await user.click(screen.getByRole('button',{name:/Save exact draft/}));await waitFor(()=>expect(screen.getByText('Exact draft saved.')).toBeTruthy());
  const saved=demo.get(initial.encounter.id).documents[0];expect(saved.text).toBe('  EXACT CUSTOM NOTE\nSECOND LINE\n');expect(saved.custom_template).toEqual({name:'Community visit update',instructions:'Use headings: observations and actions.'});
  await user.click(screen.getByRole('button',{name:'Review & sign'}));await user.click(within(screen.getByRole('dialog')).getByRole('checkbox'));await user.click(screen.getByRole('button',{name:'Sign version 1'}));
  await waitFor(()=>expect((screen.getByLabelText('Report name')as HTMLInputElement).readOnly).toBe(true));await user.click(screen.getByRole('button',{name:'History'}));await user.click(screen.getByRole('button',{name:'Open version'}));
  expect((screen.getByLabelText('Formatting instructions')as HTMLTextAreaElement).value).toBe('Use headings: observations and actions.');expect((screen.getByLabelText('Clinical document text')as HTMLTextAreaElement).readOnly).toBe(true);
});

it('clears custom-only metadata on switching back to a standard template',async()=>{
  notebook();const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'Document'}));await user.click(screen.getByRole('button',{name:'Custom layout'}));await user.type(screen.getByLabelText('Report name'),'Custom heading');await user.selectOptions(screen.getByLabelText('Document Type'),'physician_update');expect(screen.queryByLabelText('Report name')).toBeNull();
  const post=vi.spyOn(api,'post').mockResolvedValue({});await new Workspace('account').draft('case','referral','auto',false,{name:'STALE',instructions:'STALE'});expect(post.mock.calls[0][1]).toEqual({template:'referral',mode:'auto',use_ai:false});
});

it('requires clinician photo visibility review and preserves an honest manual fallback when pixel analysis is unavailable',async()=>{
  vi.stubGlobal('URL',class extends URL{static createObjectURL(){return 'blob:synthetic'}static revokeObjectURL(){}});vi.stubGlobal('createImageBitmap',vi.fn().mockRejectedValue(Error('Unavailable')));
  const demo=new DemoRepository(),data=demo.get(demo.list()[0].id),workspace=new Workspace('account');const upload=vi.spyOn(workspace,'upload').mockResolvedValue({...data,uploadedEvidenceId:'photo-source'});
  render(<EvidencePanel kind="photo" data={data} workspace={workspace} config={{capabilities:{photo:{enabled:true}}}} recovery={null} onSaved={()=>{}} onClose={()=>{}} onRecoveryChange={()=>{}}/>);
  const user=userEvent.setup();fireEvent.change(screen.getByLabelText('Choose from gallery'),{target:{files:[new File(['synthetic only'],'wound.png',{type:'image/png'})]}});await waitFor(()=>expect(screen.getByText(/Automatic quality signals are unavailable/)).toBeTruthy());await user.click(screen.getByRole('button',{name:'Upload & review'}));expect(upload).not.toHaveBeenCalled();
  await user.selectOptions(screen.getByLabelText('Clinician obstruction check'),'partial');await user.type(screen.getByLabelText('Capture limitations (optional)'),'Dressing obscures upper edge');await user.click(screen.getByRole('button',{name:'Upload & review'}));await waitFor(()=>expect(upload).toHaveBeenCalledTimes(1));expect(upload.mock.calls[0][6]).toEqual({algorithm_version:'manual-review-v1',obstruction:'partial',limitation_note:'Dressing obscures upper edge',rotation_degrees:0,crop_percent:0});
});

it('keeps the original transcript unchanged until a reviewed candidate is explicitly added',async()=>{
  vi.stubGlobal('URL',class extends URL{static createObjectURL(){return 'blob:synthetic'}static revokeObjectURL(){}});
  const demo=new DemoRepository(),data=demo.get(demo.list()[0].id),workspace=new Workspace('account');const evidence={id:'original-audio',kind:'audio',text:'Original: uncertain left or right'};
  const verify=vi.spyOn(workspace,'verifyAudio').mockResolvedValue({verification_id:'verify-1',source_evidence_id:evidence.id,original_text:evidence.text,candidate_text:'Candidate: left',uncertain_term:'left or right',context:'',window:{start_seconds:12,end_seconds:18,duration_seconds:6},comparison:{changed:true,original_contains_term:true,candidate_contains_term:false,notices:['Review the original source.']},status:'pending_clinician_review',case_revision:4});
  const accept=vi.spyOn(workspace,'acceptAudioVerification').mockResolvedValue({});vi.spyOn(workspace,'get').mockResolvedValue(data);const updated=vi.fn();
  render(<AudioVerificationPanel data={data} evidence={evidence} workspace={workspace} recovery={null} config={{capabilities:{audio:{enabled:true}}}} onClose={()=>{}} onCase={updated}/>);
  const user=userEvent.setup();const file=new File(['synthetic-only'],'original.webm',{type:'audio/webm'});fireEvent.change(screen.getByLabelText('Choose original audio file'),{target:{files:[file]}});
  await user.clear(screen.getByLabelText('Start time (seconds)'));await user.type(screen.getByLabelText('Start time (seconds)'),'12');await user.clear(screen.getByLabelText('End time (seconds)'));await user.type(screen.getByLabelText('End time (seconds)'),'18');await user.type(screen.getByLabelText('Uncertain phrase or term'),'left or right');await user.click(screen.getByRole('checkbox'));await user.click(screen.getByRole('button',{name:'Verify excerpt'}));
  await waitFor(()=>expect(screen.getByLabelText('Original transcript')).toBeTruthy());expect(screen.getByLabelText('Original source audio')).toBeTruthy();expect(screen.getByRole('button',{name:'Listen to selected window'})).toBeTruthy();expect(accept).not.toHaveBeenCalled();expect(updated).not.toHaveBeenCalled();expect(verify.mock.calls[0][0]).toBe(data.encounter.id);expect(verify.mock.calls[0][2].source_evidence_id).toBe(evidence.id);
  await user.clear(screen.getByLabelText('Candidate excerpt — review or correct'));await user.type(screen.getByLabelText('Candidate excerpt — review or correct'),'Clinician reviewed: left');await user.click(screen.getByRole('checkbox'));await user.click(screen.getByRole('button',{name:'Add reviewed transcript evidence'}));
  await waitFor(()=>expect(accept).toHaveBeenCalledWith('verify-1',4,'Clinician reviewed: left'));expect(evidence.text).toBe('Original: uncertain left or right');
});

it('reopens a saved verification without repeating a paid call and ignores a late acceptance after closing',async()=>{
  const demo=new DemoRepository(),data=demo.get(demo.list()[0].id),workspace=new Workspace('account');const proposal={verification_id:'saved-proposal',source_evidence_id:'audio-original',original_text:'Original phrase',candidate_text:'Candidate phrase',uncertain_term:'phrase',context:'',window:{start_seconds:3,end_seconds:8,duration_seconds:5},comparison:{changed:true,original_contains_term:true,candidate_contains_term:true,notices:[]},status:'pending_clinician_review',case_revision:6};
  const get=vi.spyOn(workspace,'getAudioVerification').mockResolvedValue(proposal),verify=vi.spyOn(workspace,'verifyAudio');let finish!:(value:unknown)=>void;vi.spyOn(workspace,'acceptAudioVerification').mockImplementation(()=>new Promise(resolve=>{finish=resolve}));vi.spyOn(workspace,'get').mockResolvedValue(data);const apply=vi.fn(),close=vi.fn();
  const view=render(<AudioVerificationPanel data={data} evidence={{id:'audio-original',kind:'audio'}} workspace={workspace} recovery={null} config={{capabilities:{audio:{enabled:true}}}} verificationId="saved-proposal" onCase={apply} onClose={close}/>);await waitFor(()=>expect(screen.getByLabelText('Original transcript')).toBeTruthy());expect(get).toHaveBeenCalledWith('saved-proposal');expect(verify).not.toHaveBeenCalled();const user=userEvent.setup();await user.click(screen.getByRole('checkbox'));await user.click(screen.getByRole('button',{name:'Add reviewed transcript evidence'}));view.unmount();await act(async()=>finish({}));expect(apply).not.toHaveBeenCalled();expect(close).not.toHaveBeenCalled();
});

it('preserves the original audio selection and idempotency key after verification fails',async()=>{
  vi.stubGlobal('URL',class extends URL{static createObjectURL(){return 'blob:synthetic'}static revokeObjectURL(){}});
  const demo=new DemoRepository(),data=demo.get(demo.list()[0].id),workspace=new Workspace('account');const verify=vi.spyOn(workspace,'verifyAudio').mockRejectedValue(Error('Connection interrupted'));
  render(<AudioVerificationPanel data={data} evidence={{id:'audio-a',kind:'audio'}} workspace={workspace} recovery={null} config={{capabilities:{audio:{enabled:true}}}} onClose={()=>{}} onCase={()=>{}}/>);const user=userEvent.setup();
  fireEvent.change(screen.getByLabelText('Choose original audio file'),{target:{files:[new File(['synthetic'],'original.webm',{type:'audio/webm'})]}});await user.type(screen.getByLabelText('Uncertain phrase or term'),'dose');await user.click(screen.getByRole('checkbox'));await user.click(screen.getByRole('button',{name:'Verify excerpt'}));await waitFor(()=>expect(screen.getByText('Connection interrupted')).toBeTruthy());await user.click(screen.getByRole('button',{name:'Verify excerpt'}));await waitFor(()=>expect(verify).toHaveBeenCalledTimes(2));expect(verify.mock.calls[0][1]).toBe(verify.mock.calls[1][1]);expect(verify.mock.calls[0][3]).toBe(verify.mock.calls[1][3]);
});

it('masks demo visit lists while an authenticated workspace list is still loading',async()=>{
  const session={user:{id:'user-a',workspace_id:'workspace-a',display_name:'AUTHENTICATED TEST',email:'test@example.test',profile:{},is_platform_admin:false},csrf_token:'test'};
  let finish!:(response:Response)=>void;const delayed=new Promise<Response>(resolve=>{finish=resolve});vi.spyOn(window,'scrollTo').mockImplementation(()=>{});
  vi.stubGlobal('fetch',vi.fn(async(input:RequestInfo|URL)=>{const url=String(input);if(url.endsWith('/auth/session'))return new Response(JSON.stringify(session));if(url.endsWith('/config'))return new Response(JSON.stringify({capabilities:{}}));if(url.endsWith('/patients')||url.endsWith('/encounters'))return (await delayed).clone();return new Response('{}')}));
  render(<App/>);await waitFor(()=>expect(screen.getByText('AUTHENTICATED TEST')).toBeTruthy());expect(screen.queryByText('Avery Morgan')).toBeNull();await waitFor(()=>expect(screen.getByText('Loading this workspace…')).toBeTruthy());expect(screen.queryByText('Avery Morgan')).toBeNull();await act(async()=>finish(new Response('[]')));
});
