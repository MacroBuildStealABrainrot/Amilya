import {History, Plus, ArrowUpRight, Check} from 'lucide-react';
import {Button, SourceBadge} from './components';
import type {CaseData} from './types';
import {formatValue} from './types';

export function EncounterIdentity({data,demo,onHistory,onEvidence}:{data:CaseData;demo:boolean;onHistory:()=>void;onEvidence:()=>void}) {
  return <header className="encounter-heading patient-header">
    <div className="patient-identity">
      <div className="record-kicker"><span>{demo?'Demonstration record':'Patient record'}</span><span className="record-reference">{data.patient.external_ref||'No external reference'}</span></div>
      <h1>{data.patient.display_name}</h1>
      <div className="patient-condition"><span>{data.wound?.label||data.encounter.title}</span><span className="patient-condition-detail">{data.wound?.location||'Location not recorded'}</span></div>
    </div>
    <div className="patient-visit">
      <div className="visit-stamp"><time>{new Date(data.encounter.updated_at).toLocaleDateString(undefined,{day:'numeric',month:'short',year:'numeric'})}</time><span className={`visit-state ${data.encounter.status==='signed'?'signed':''}`}>{data.encounter.status==='signed'?<><Check size={13}/>Signed</>:'In progress'}</span></div>
      <div className="heading-actions"><Button onClick={onHistory}><History size={16}/>History</Button><Button variant="primary" onClick={onEvidence}><Plus size={17}/>Add evidence</Button></div>
    </div>
  </header>;
}

export function EncounterOverview({data,onEvidence,onDocument,onAudit}:{data:CaseData;onEvidence:()=>void;onDocument:()=>void;onAudit:()=>void}) {
  const confirmed=data.facts.filter(f=>f.confirmation_state==='confirmed').length;
  const rejected=data.facts.filter(f=>f.confirmation_state==='rejected').length;
  const pending=data.facts.length-confirmed-rejected;
  const total=data.facts.length;
  return <aside className="clinical-inspector" aria-label="Encounter context">
    <div className="inspector-heading"><h2>Visit overview</h2><span>{String(data.evidence.length).padStart(2,'0')} sources</span></div>
    <section className="review-progress" aria-label="Finding review progress">
      <div><strong>{!total?'No findings':pending?`${pending} to review`:'Review complete'}</strong><span>{confirmed+rejected}/{total}</span></div>
      <div className="review-track" aria-hidden="true"><i style={{width:`${total?confirmed/total*100:0}%`}}/><i className="rejected-track" style={{width:`${total?rejected/total*100:0}%`}}/></div>
      <p>{total?`${confirmed} confirmed · ${rejected} rejected`:'No findings added'}</p>
    </section>
    <section className="source-index"><h3>Sources</h3>{data.evidence.length?data.evidence.map((e,i)=><button key={e.id} onClick={onEvidence}><span className="source-order">{String(i+1).padStart(2,'0')}</span><span><SourceBadge source={e.kind}/><small>{e.filename||e.text?.slice(0,100)||data.facts.filter(f=>f.source_ref===e.id).map(f=>formatValue(f.value)).join(' · ')||'No text recorded'}</small></span><ArrowUpRight size={14}/></button>):<p className="muted">No sources added to this visit.</p>}</section>
    <button className="document-shortcut" onClick={onDocument}><span>Documentation<strong>{data.documents.length?`${data.documents.length} saved ${data.documents.length===1?'version':'versions'}`:'No saved document'}</strong></span><ArrowUpRight size={17}/></button>
    <Button variant="ghost" className="audit-link" onClick={onAudit}><History size={15}/>Encounter audit</Button>
  </aside>;
}
