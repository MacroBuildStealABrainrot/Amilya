import {afterEach, beforeEach, expect, it, vi} from 'vitest';
import {cleanup, fireEvent, render, screen} from '@testing-library/react';
import {useState} from 'react';
import {useSwipeTabs} from './useSwipeTabs';

class TouchPointer extends MouseEvent {
  pointerType: string; pointerId: number; isPrimary: boolean;
  constructor(type: string, init: PointerEventInit = {}) {
    super(type, init); this.pointerType = init.pointerType || 'touch'; this.pointerId = init.pointerId ?? 1; this.isPrimary = init.isPrimary ?? true;
  }
}
beforeEach(() => vi.stubGlobal('PointerEvent', TouchPointer));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function Harness() {
  const [index, change] = useState(0);
  const swipe = useSwipeTabs(index, 4, change);
  return <section aria-label="Task surface" {...swipe}><h2>Task {index}</h2><textarea aria-label="Draft" defaultValue={'Exact text\n  preserved.'}/><button>Save</button></section>;
}
function drag(target: Element, from: [number, number], to: [number, number], extra: PointerEventInit = {}, cancel = false) {
  const common = {pointerType: 'touch', pointerId: 1, isPrimary: true, ...extra};
  fireEvent.pointerDown(target, {...common, clientX: from[0], clientY: from[1]});
  fireEvent.pointerMove(target, {...common, clientX: to[0], clientY: to[1]});
  fireEvent[cancel ? 'pointerCancel' : 'pointerUp'](target, {...common, clientX: to[0], clientY: to[1]});
}
it('moves one task per deliberate horizontal swipe and respects both boundaries', () => {
  render(<Harness/>); const surface = screen.getByLabelText('Task surface');
  drag(surface,[40,80],[200,85]); expect(screen.getByText('Task 0')).toBeTruthy();
  for (let i=1;i<=3;i++) { drag(surface,[220,80],[40,85]); expect(screen.getByText(`Task ${i}`)).toBeTruthy(); }
  drag(surface,[220,80],[40,85]); expect(screen.getByText('Task 3')).toBeTruthy();
  drag(surface,[40,80],[220,85]); expect(screen.getByText('Task 2')).toBeTruthy();
});
it('preserves vertical scrolling, short taps, mouse selection and cancelled gestures', () => {
  render(<Harness/>); const surface = screen.getByLabelText('Task surface');
  drag(surface,[220,80],[210,250]); drag(surface,[220,80],[200,80]);
  drag(surface,[220,80],[40,80],{pointerType:'mouse'}); drag(surface,[220,80],[40,80],{},true);
  expect(screen.getByText('Task 0')).toBeTruthy(); expect(surface.getAttribute('data-swiping')).toBeNull();
});
it('never navigates from editable fields or action buttons', () => {
  render(<Harness/>);
  drag(screen.getByLabelText('Draft'),[220,80],[40,80]);
  drag(screen.getByRole('button',{name:'Save'}),[220,80],[40,80]);
  expect(screen.getByText('Task 0')).toBeTruthy();
  expect((screen.getByLabelText('Draft') as HTMLTextAreaElement).value).toBe('Exact text\n  preserved.');
});
it('cancels the first gesture when another finger is placed on the surface', () => {
  render(<Harness/>); const surface = screen.getByLabelText('Task surface');
  fireEvent.pointerDown(surface,{pointerType:'touch',pointerId:1,isPrimary:true,clientX:220,clientY:80});
  fireEvent.pointerDown(surface,{pointerType:'touch',pointerId:2,isPrimary:false,clientX:180,clientY:80});
  fireEvent.pointerMove(surface,{pointerType:'touch',pointerId:1,isPrimary:true,clientX:40,clientY:80});
  fireEvent.pointerUp(surface,{pointerType:'touch',pointerId:1,isPrimary:true,clientX:40,clientY:80});
  expect(screen.getByText('Task 0')).toBeTruthy();
});
it('keeps touch navigation available without motion when reduced motion is requested', () => {
  vi.stubGlobal('matchMedia',vi.fn().mockReturnValue({matches:true}));
  render(<Harness/>); const surface = screen.getByLabelText('Task surface');
  drag(surface,[220,80],[40,80]); expect(screen.getByText('Task 1')).toBeTruthy();
  expect(surface.style.translate).toBe('0px 0');
});
