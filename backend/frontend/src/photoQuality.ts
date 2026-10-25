export interface PhotoQuality {
  algorithm_version:'capture-quality-v1';
  width:number;
  height:number;
  mean_luminance:number;
  focus_variance:number;
  clipped_fraction:number;
  messages:{name:string;text:string;attention:boolean}[];
}

export function photoReviewSummary(value:unknown){const review=value as {obstruction?:string;limitation_note?:string};return `${review.obstruction==='clear'?'Wound and relevant edges visible':review.obstruction==='partial'?'Partly obstructed or cropped':'Full visibility uncertain'}.${review.limitation_note?' '+review.limitation_note:''}`}

/** Capture-quality signals only. Thresholds do not establish clinical usability. */
export function analyzePhotoPixels(pixels:Uint8ClampedArray,width:number,height:number,originalWidth=width,originalHeight=height):PhotoQuality {
  if(width<3||height<3||pixels.length!==width*height*4)throw Error('Image pixels are unavailable for quality inspection.');
  const luminance=new Float64Array(width*height);
  let total=0,clipped=0;
  for(let i=0;i<luminance.length;i++){
    const r=pixels[i*4],g=pixels[i*4+1],b=pixels[i*4+2];
    luminance[i]=.2126*r+.7152*g+.0722*b;total+=luminance[i];
    if(r>=245&&g>=245&&b>=245)clipped++;
  }
  let sum=0,squares=0,count=0;
  for(let y=1;y<height-1;y++)for(let x=1;x<width-1;x++){
    const i=y*width+x;
    const laplacian=luminance[i-1]+luminance[i+1]+luminance[i-width]+luminance[i+width]-4*luminance[i];
    sum+=laplacian;squares+=laplacian*laplacian;count++;
  }
  const focus=Math.max(0,squares/count-(sum/count)**2),mean=total/luminance.length,highlights=clipped/luminance.length;
  return {algorithm_version:'capture-quality-v1',width:originalWidth,height:originalHeight,mean_luminance:mean,focus_variance:focus,clipped_fraction:highlights,messages:[
    {name:'Resolution',attention:Math.min(originalWidth,originalHeight)<600,text:`${originalWidth} × ${originalHeight} px. ${Math.min(originalWidth,originalHeight)<600?'Limited resolution; consider a closer capture.':'Inspect the full wound at the available resolution.'}`},
    {name:'Focus signal',attention:focus<80,text:focus<80?'Low edge contrast may indicate soft focus or a naturally uniform scene. Check the wound edges and retake if unclear.':'Edge detail is present. Check that the wound itself is in focus; this signal does not certify sharpness.'},
    {name:'Lighting',attention:mean<45||mean>220,text:mean<45?'Overall image is dark. Improve even lighting if detail is obscured.':mean>220?'Overall image is very bright. Check that surface detail has not been lost.':'Overall exposure is within the screening range. Inspect local shadows and color yourself.'},
    {name:'Glare signal',attention:highlights>.03,text:`${(highlights*100).toFixed(1)}% near-white clipped pixels. ${highlights>.03?'Possible glare or bright background; check reflections over the wound.':'No large clipped-highlight region detected; smaller reflections may still be present.'}`},
  ]};
}

export async function inspectPhoto(file:Blob):Promise<PhotoQuality>{
  const bitmap=await createImageBitmap(file);
  try{
    const scale=Math.min(1,256/Math.max(bitmap.width,bitmap.height));
    const canvas=document.createElement('canvas');canvas.width=Math.max(3,Math.round(bitmap.width*scale));canvas.height=Math.max(3,Math.round(bitmap.height*scale));
    const context=canvas.getContext('2d',{willReadFrequently:true});if(!context)throw Error('This browser cannot inspect photo quality.');
    context.drawImage(bitmap,0,0,canvas.width,canvas.height);
    return analyzePhotoPixels(context.getImageData(0,0,canvas.width,canvas.height).data,canvas.width,canvas.height,bitmap.width,bitmap.height);
  }finally{bitmap.close()}
}
