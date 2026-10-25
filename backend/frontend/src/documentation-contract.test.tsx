import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {useState} from 'react';
import {Notebook} from './Notebook';
import {DemoRepository} from './demo';
import {Workspace} from './workspace';
import type {Draft} from './types';
afterEach(()=>{cleanup();vi.restoreAllMocks()});
function setup(text='Client reports pain 6/10.'){
 const demo=new DemoRepository(),workspace=new Workspace('test',demo),initial=demo.get(demo.list()[0].id);
 function Harness(){const [data,setData]=useState(initial),[draft,setDraft]=useState<Draft>({text,template:'nursing',updatedAt:0,caseRevision:initial.encounter.revision});return <Notebook data={data} workspace={workspace} config={{capabilities:{ai:{enabled:true}}}} recovery={null} draft={draft} onDraft={setDraft} onCase={setData} mode="auto" onRecoveryChange={()=>{}} localStatus="Local"/>}
 render(<Harness/>);return {workspace,initial,user:userEvent.setup()}
}
it('offers six normal document types and regenerates a new format without using the edited report as evidence',async()=>{
 const {workspace,initial,user}=setup();const call=vi.spyOn(workspace,'draft').mockResolvedValue({text:'Subjective\nClient reports pain 6/10.',case_revision:initial.encounter.revision,status:'ready',ui_alerts:[]});
 await user.click(screen.getByRole('button',{name:'Document'}));const selector=screen.getByLabelText('Document Type');expect(selector.querySelectorAll('option')).toHaveLength(6);
 await user.selectOptions(selector,'soap');await waitFor(()=>expect(call).toHaveBeenCalledWith(initial.encounter.id,'soap','auto',false,undefined));
 expect((screen.getByLabelText('Clinical document text')as HTMLTextAreaElement).value).toBe('Client reports pain 6/10.');await user.click(screen.getByRole('button',{name:'Use this draft'}));
 expect((screen.getByLabelText('Clinical document text')as HTMLTextAreaElement).value).toBe('Subjective\nClient reports pain 6/10.');expect((selector as HTMLSelectElement).value).toBe('soap');
});
it('keeps an unsupported referral message out of the existing clinical text',async()=>{
 const {workspace,initial,user}=setup();vi.spyOn(workspace,'draft').mockResolvedValue({text:'',case_revision:initial.encounter.revision,status:'referral_not_supported',ui_message:'Referral reason not found in visit information.'});
 await user.click(screen.getByRole('button',{name:'Document'}));await user.selectOptions(screen.getByLabelText('Document Type'),'referral_letter');await waitFor(()=>expect(screen.getByText('Referral reason not found in visit information.')).toBeTruthy());
 expect((screen.getByLabelText('Clinical document text')as HTMLTextAreaElement).value).toBe('Client reports pain 6/10.');expect((screen.getByLabelText('Document Type')as HTMLSelectElement).value).toBe('nursing');
});
it('assessment questions are a separate dialog and report improvements require accepting the proposed text',async()=>{
 const {workspace,initial,user}=setup();vi.spyOn(workspace,'improveAssessment').mockResolvedValue({answer:'Clarify pain onset.',considerations:['When did this pain begin?'],case_revision:initial.encounter.revision});vi.spyOn(workspace,'improveReport').mockResolvedValue({text:'Pain reported as 6/10.',status:'ready',ui_alerts:[],case_revision:initial.encounter.revision});
 await user.click(screen.getByRole('button',{name:'Document'}));await user.click(screen.getByRole('button',{name:'Improve Assessment'}));await waitFor(()=>expect(screen.getByText('When did this pain begin?')).toBeTruthy());expect((screen.getByLabelText('Clinical document text')as HTMLTextAreaElement).value).toBe('Client reports pain 6/10.');await user.click(screen.getByRole('button',{name:'Done'}));
 await user.click(screen.getByRole('button',{name:'Improve Report'}));await waitFor(()=>expect(screen.getByLabelText('Proposed draft')).toBeTruthy());expect((screen.getByLabelText('Clinical document text')as HTMLTextAreaElement).value).toBe('Client reports pain 6/10.');await user.click(screen.getByRole('button',{name:'Use this draft'}));expect((screen.getByLabelText('Clinical document text')as HTMLTextAreaElement).value).toBe('Pain reported as 6/10.');
 expect(screen.getByText('Draft — Clinician Review Required')).toBeTruthy();
});
