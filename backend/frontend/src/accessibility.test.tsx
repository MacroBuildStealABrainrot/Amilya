import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import App from './App';
afterEach(()=>{cleanup();vi.restoreAllMocks()});
it('has accessible controls and landmarks in the notebook and assessment dialog',async()=>{vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({detail:'Not signed in'}),{status:401})));const {container}=render(<App/>);const user=userEvent.setup();let report=await axe.run(container,{rules:{'color-contrast':{enabled:false}}});expect(report.violations.map(v=>`${v.id}: ${v.nodes.map(n=>n.target).join(', ')}`)).toEqual([]);await user.click(screen.getByRole('button',{name:'Add evidence'}));report=await axe.run(document.body,{rules:{'color-contrast':{enabled:false}}});expect(report.violations.map(v=>`${v.id}: ${v.nodes.map(n=>n.target).join(', ')}`)).toEqual([])});
