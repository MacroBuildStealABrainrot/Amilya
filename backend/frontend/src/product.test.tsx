import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {HomePage} from './HomePage';
import {EncounterOverview} from './EncounterIdentity';
import {DemoRepository} from './demo';
import App from './App';
import type {Encounter,Patient} from './types';

afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals()});

const patients:Patient[]=[
  {id:'a',display_name:'Alpha Person',external_ref:'REF-101',created_at:'2026-09-01'},
  {id:'b',display_name:'Beta Person',external_ref:'REF-202',created_at:'2026-09-01'},
];
const encounters:Encounter[]=[
  {id:'old',patient_id:'a',title:'Earlier assessment',status:'open',revision:1,updated_at:'2026-09-02'},
  {id:'signed',patient_id:'b',title:'Signed assessment',status:'signed',revision:2,updated_at:'2026-09-03'},
  {id:'new',patient_id:'a',title:'Recent assessment',status:'open',revision:1,updated_at:'2026-09-04'},
];

it('orders visits by recency and opens the selected record ID',async()=>{
  const onOpen=vi.fn();
  render(<HomePage encounters={encounters} patients={patients} onOpen={onOpen} onNew={vi.fn()} demo={false}/>);
  const rows=screen.getAllByRole('article');
  expect(rows.map(r=>r.textContent)).toEqual([expect.stringContaining('Recent assessment'),expect.stringContaining('Signed assessment'),expect.stringContaining('Earlier assessment')]);
  await userEvent.click(within(rows[1]).getByRole('button',{name:'Open'}));
  expect(onOpen).toHaveBeenCalledWith('signed');
  expect(encounters[0].id).toBe('old');
});

it('combines status filtering with a case-insensitive trimmed reference search',async()=>{
  render(<HomePage encounters={encounters} patients={patients} onOpen={vi.fn()} onNew={vi.fn()} demo={false}/>);
  const user=userEvent.setup();
  await user.click(screen.getByRole('button',{name:'Signed 1'}));
  await user.type(screen.getByRole('textbox',{name:'Search visits'}),'  ref-202 ');
  expect(screen.getAllByRole('article')).toHaveLength(1);
  expect(screen.getByText('Beta Person')).toBeTruthy();
  await user.click(screen.getByRole('button',{name:'In progress 2'}));
  expect(screen.queryAllByRole('article')).toHaveLength(0);
  expect(screen.getByText('No matching visits')).toBeTruthy();
  await user.clear(screen.getByRole('textbox',{name:'Search visits'}));
  expect(screen.getAllByRole('article')).toHaveLength(2);
});

it('does not label an empty encounter as a completed review',()=>{
  const repo=new DemoRepository();
  const empty=repo.create(repo.addPatient('Empty synthetic record').id);
  render(<EncounterOverview data={empty} onEvidence={vi.fn()} onDocument={vi.fn()} onAudit={vi.fn()}/>);
  expect(screen.getByText('No findings')).toBeTruthy();
  expect(screen.queryByText('Review complete')).toBeNull();
});

it('shows pending findings first without changing their confirmation state',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({detail:'Not signed in'}),{status:401})));
  render(<App/>);
  const rows=screen.getAllByRole('article');
  expect(within(rows[0]).getByText('Exudate')).toBeTruthy();
  expect(rows.slice(0,3).every(row=>within(row).getByText('Needs review'))).toBe(true);
  await userEvent.click(screen.getByRole('button',{name:'Confirmed'}));
  expect(screen.getAllByRole('article')).toHaveLength(3);
  expect(screen.queryByText('Small amount of serous drainage visible on the dressing')).toBeNull();
  expect(screen.getByText('3 to review')).toBeTruthy();
});
