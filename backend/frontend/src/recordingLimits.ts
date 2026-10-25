export const AUDIO_LIMITS={seconds:3600,requestedBitsPerSecond:32000,stopBytes:24*1024*1024,uploadBytes:25*1024*1024};
export function recordingLimit(activeSeconds:number,capturedBytes:number):'duration'|'size'|null{
  if(capturedBytes>=AUDIO_LIMITS.stopBytes)return 'size';
  if(activeSeconds>=AUDIO_LIMITS.seconds)return 'duration';
  return null;
}
export function signalAmplitudes(samples:Uint8Array,count=32){return Array.from({length:count},(_,bar)=>{const start=Math.floor(bar*samples.length/count),end=Math.floor((bar+1)*samples.length/count);let sum=0;for(let i=start;i<end;i++)sum+=((samples[i]-128)/128)**2;return end>start?Math.min(1,Math.sqrt(sum/(end-start))):0})}
