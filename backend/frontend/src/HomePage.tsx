import {useMemo,useState} from 'react';
import {ArrowRight, Plus, Search} from 'lucide-react';
import {Button, Empty} from './components';
import {human} from './types';
import type {Encounter,Patient} from './types';

export function HomePage({encounters,patients,onOpen,onNew,demo}:{encounters:Encounter[];patients:Patient[];onOpen:(id:string)=>void;onNew:()=>void;demo:boolean}) {
  const [filter,setFilter]=useState<'all'|'open'|'signed'>('all');
  const [search,setSearch]=useState('');
  const people=useMemo(()=>new Map(patients.map(p=>[p.id,p])),[patients]);
  const open=encounters.filter(e=>e.status!=='signed').length;
  const signed=encounters.length-open;
  const visible=encounters.filter(e=>(filter==='all'||(filter==='signed'?e.status==='signed':e.status!=='signed'))&&[people.get(e.patient_id)?.display_name,people.get(e.patient_id)?.external_ref,e.title].join(' ').toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())).sort((a,b)=>Date.parse(b.updated_at)-Date.parse(a.updated_at));
  return <div className="page-content visits-page">
    <div className="page-heading queue-heading"><div><div className="record-kicker">{demo?'Demonstration workspace':'Practice workspace'}</div><h1>Visits</h1></div><Button variant="primary" onClick={onNew}><Plus size={17}/>New encounter</Button></div>
    <div className="queue-summary"><span><strong>{open}</strong> in progress</span><span><strong>{signed}</strong> signed</span><span><strong>{patients.length}</strong> {patients.length===1?'client':'clients'}</span></div>
    <div className="queue-tools"><nav aria-label="Filter visits">{(['all','open','signed']as const).map(f=><button key={f} onClick={()=>setFilter(f)} aria-pressed={filter===f} className={filter===f?'active':''}>{f==='all'?'All visits':f==='open'?'In progress':'Signed'}<span>{f==='all'?encounters.length:f==='open'?open:signed}</span></button>)}</nav><label className="search-input"><Search size={17}/><input aria-label="Search visits" value={search} onChange={e=>setSearch(e.target.value)} placeholder="Client or reference"/></label></div>
    <div className="visit-table"><div className="visit-table-head" aria-hidden="true"><span>Client / record</span><span>Encounter</span><span>Last updated</span><span>Status</span><span/></div>
      {visible.map(e=>{const person=people.get(e.patient_id);return <article className="visit-table-row" key={e.id}><div className="queue-client"><span className="queue-initials">{(person?.display_name||e.title).split(' ').map(s=>s[0]).slice(0,2).join('')}</span><span><strong>{person?.display_name||e.title}</strong><small>{person?.external_ref||'No external reference'}</small></span></div><span className="queue-encounter">{e.title}</span><time>{new Date(e.updated_at).toLocaleDateString(undefined,{day:'numeric',month:'short'})}</time><span className={`record-status ${e.status==='signed'?'complete':''}`}>{e.status==='signed'?'Signed':e.status==='open'?'In progress':human(e.status)}</span><Button variant="ghost" onClick={()=>onOpen(e.id)}>{e.status==='signed'?'Open':'Resume'}<ArrowRight size={16}/></Button></article>})}
      {!visible.length&&<Empty title={search?'No matching visits':filter==='signed'?'No signed visits':filter==='open'?'No visits in progress':'No visits yet'}>{search?'Try a client name or reference.':'Create an encounter to add a client and start documenting.'}</Empty>}
    </div><p className="queue-footnote">Sorted by last update. Signed records open in read-only mode.</p>
  </div>;
}
