import {useEffect, useRef, useState} from 'react';
import type {PointerEvent as ReactPointerEvent} from 'react';

const controls = 'button,a,input,textarea,select,label,audio,video,[contenteditable="true"],[data-no-swipe]';

/** Touch navigation changes the visible task only; it never submits or edits data. */
export function useSwipeTabs(index: number, count: number, onChange: (index: number) => void) {
  const ref = useRef<HTMLElement>(null);
  const gesture = useRef<{id: number; x: number; y: number; at: number; horizontal: boolean} | null>(null);
  const [offset, setOffset] = useState(0);
  const previous = useRef(index);
  const reduced = () => {const preference = document.documentElement.dataset.motion; return preference === 'reduce' || preference !== 'subtle' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;};
  const reset = () => { gesture.current = null; setOffset(0); };

  useEffect(() => {
    const before = previous.current;
    previous.current = index;
    if (before === index || reduced()) return;
    const animation = ref.current?.animate?.([
      {opacity: .65, transform: `translateX(${index > before ? 10 : -10}px)`},
      {opacity: 1, transform: 'translateX(0)'},
    ], {duration: 170, easing: 'cubic-bezier(.2,.7,.2,1)'});
    return () => animation?.cancel();
  }, [index]);

  function onPointerDown(event: ReactPointerEvent<HTMLElement>) {
    reset();
    if (event.pointerType !== 'touch' || !event.isPrimary || (event.target as Element).closest(controls) || window.getSelection()?.toString()) return;
    gesture.current = {id: event.pointerId, x: event.clientX, y: event.clientY, at: event.timeStamp, horizontal: false};
  }
  function onPointerMove(event: ReactPointerEvent<HTMLElement>) {
    const start = gesture.current;
    if (!start || start.id !== event.pointerId) return;
    const dx = event.clientX - start.x, dy = event.clientY - start.y;
    if (!start.horizontal) {
      if (Math.abs(dy) > 10 && Math.abs(dy) >= Math.abs(dx)) { reset(); return; }
      if (Math.abs(dx) < 12 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
      start.horizontal = true;
      event.currentTarget.setPointerCapture?.(event.pointerId);
    }
    if (window.getSelection()?.toString()) { reset(); return; }
    const edge = dx > 0 && index === 0 || dx < 0 && index === count - 1;
    if (!reduced()) setOffset(Math.max(-32, Math.min(32, dx * (edge ? .08 : .22))));
  }
  function onPointerUp(event: ReactPointerEvent<HTMLElement>) {
    const start = gesture.current;
    reset();
    if (!start || start.id !== event.pointerId || !start.horizontal || event.timeStamp - start.at > 800 || window.getSelection()?.toString()) return;
    const dx = event.clientX - start.x, dy = event.clientY - start.y;
    const threshold = Math.max(48, Math.min(96, event.currentTarget.clientWidth * .16));
    if (Math.abs(dx) < threshold || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    const next = Math.max(0, Math.min(count - 1, index + (dx < 0 ? 1 : -1)));
    if (next !== index) onChange(next);
  }
  return {ref, style: {translate: `${offset}px 0`}, 'data-swiping': offset !== 0 || undefined,
    onPointerDown, onPointerMove, onPointerUp, onPointerCancel: reset, onLostPointerCapture: reset};
}
