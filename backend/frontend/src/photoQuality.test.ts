import {expect,it} from 'vitest';
import {analyzePhotoPixels} from './photoQuality';
function scene(size:number,sample:(x:number,y:number)=>number){const pixels=new Uint8ClampedArray(size*size*4);for(let y=0;y<size;y++)for(let x=0;x<size;x++){const i=(y*size+x)*4;pixels[i]=pixels[i+1]=pixels[i+2]=sample(x,y);pixels[i+3]=255}return pixels}
it('distinguishes soft low-contrast capture from visible edge detail without asserting clinical suitability',()=>{
  const soft=analyzePhotoPixels(scene(64,()=>120),64,64,1200,1200);
  const detailed=analyzePhotoPixels(scene(64,(x,y)=>(x+y)%2?180:70),64,64,1200,1200);
  expect(soft.messages.find(x=>x.name==='Focus signal')?.attention).toBe(true);
  expect(detailed.focus_variance).toBeGreaterThan(soft.focus_variance);
  expect(detailed.messages.find(x=>x.name==='Focus signal')?.text).toContain('does not certify');
});
it('flags dark capture and detects clipped highlights separately from overall exposure',()=>{
  const dark=analyzePhotoPixels(scene(64,()=>15),64,64);
  const glare=analyzePhotoPixels(scene(64,x=>x<8?255:100),64,64);
  expect(dark.messages.find(x=>x.name==='Lighting')?.attention).toBe(true);
  expect(glare.clipped_fraction).toBe(.125);
  expect(glare.messages.find(x=>x.name==='Glare signal')?.attention).toBe(true);
  expect(glare.messages.find(x=>x.name==='Lighting')?.attention).toBe(false);
});
