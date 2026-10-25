import {useEffect,useRef,useState} from 'react';
import {signalAmplitudes} from './recordingLimits';
export function MicrophoneWaveform({stream,recording}:{stream:MediaStream|null;recording:boolean}){
  const canvas=useRef<HTMLCanvasElement>(null);const [available,setAvailable]=useState(false);
  useEffect(()=>{
    setAvailable(false);
    if(!stream||!recording||typeof AudioContext==='undefined')return;
    let context:AudioContext|undefined,timer:ReturnType<typeof setInterval>|undefined;
    try{
      context=new AudioContext();const analyser=context.createAnalyser();analyser.fftSize=1024;
      const input=context.createMediaStreamSource(stream);input.connect(analyser);
      const samples=new Uint8Array(analyser.fftSize);setAvailable(true);
      void context.resume().catch(()=>setAvailable(false));
      timer=setInterval(()=>{const surface=canvas.current;const brush=surface?.getContext('2d');if(!surface||!brush)return;analyser.getByteTimeDomainData(samples);const bars=signalAmplitudes(samples);brush.clearRect(0,0,surface.width,surface.height);brush.fillStyle=getComputedStyle(surface).color;bars.forEach((value,index)=>{const height=Math.max(2,value*surface.height);brush.fillRect(index*8,(surface.height-height)/2,4,height)})},matchMedia('(prefers-reduced-motion:reduce)').matches?750:75);
    }catch{setAvailable(false)}
    return()=>{if(timer)clearInterval(timer);void context?.close().catch(()=>{})};
  },[stream,recording]);
  return <div className="microphone-signal">{available?<canvas width={256} height={50} ref={canvas} role="img" aria-label="Measured microphone signal"/>:<span className="small muted">{recording?'Recording · signal display unavailable':'Microphone signal appears while recording'}</span>}</div>;
}
