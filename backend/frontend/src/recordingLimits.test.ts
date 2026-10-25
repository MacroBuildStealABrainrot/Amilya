import {expect,it} from 'vitest';
import {recordingLimit,signalAmplitudes} from './recordingLimits';
it('allows a 59-minute capture and stops at one hour or the earlier device-size boundary',()=>{
  expect(recordingLimit(59*60,14_160_000)).toBeNull();
  expect(recordingLimit(3600,14_400_000)).toBe('duration');
  expect(recordingLimit(1200,24*1024*1024)).toBe('size');
  expect(recordingLimit(3599,24*1024*1024-1)).toBeNull();
});
it('shows silence as zero and measured oscillation as signal rather than fabricated motion',()=>{
  expect(signalAmplitudes(new Uint8Array(64).fill(128),4)).toEqual([0,0,0,0]);
  const signal=Uint8Array.from({length:64},(_,i)=>i%2?255:1);
  expect(signalAmplitudes(signal,4).every(value=>value>.9&&value<=1)).toBe(true);
});
