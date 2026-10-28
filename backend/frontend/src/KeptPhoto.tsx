import {useState} from 'react';
import type {Evidence} from './types';

/** The server keeps a viewable copy of an uploaded photo for a limited time; its findings outlive it. */
export function KeptPhoto({evidence}:{evidence:Evidence}){
 const metadata=(evidence.metadata||{}) as {raw_media_retained?:boolean;photo_expires_at?:string};
 const [failed,setFailed]=useState(false);
 if(!metadata.raw_media_retained)return null;
 const expires=metadata.photo_expires_at?Date.parse(metadata.photo_expires_at):NaN;
 if(!(expires>Date.now()))return <p className="small muted">This photo was deleted after its viewing period. Its findings remain.</p>;
 if(failed)return <p className="small muted">The photo could not be loaded. Its findings remain.</p>;
 const src=`/api/v1/evidence/${encodeURIComponent(evidence.id)}/photo`;
 return <a className="kept-photo" href={src} target="_blank" rel="noopener noreferrer"><img src={src} alt="Uploaded wound photo" loading="lazy" onError={()=>setFailed(true)}/><span className="small muted">Viewable until {new Date(expires).toLocaleString(undefined,{weekday:'short',hour:'2-digit',minute:'2-digit'})}</span></a>
}
