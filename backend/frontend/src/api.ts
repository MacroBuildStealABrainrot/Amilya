import type {Configuration,Session} from './types';
export class ApiError extends Error {constructor(message:string,public status:number){super(message)}}
export class ApiClient {
  csrf='';
  async request<T>(path:string,options:RequestInit={}):Promise<T>{const method=(options.method||'GET').toUpperCase();const headers=new Headers(options.headers);if(this.csrf&&!['GET','HEAD','OPTIONS'].includes(method))headers.set('X-CSRF-Token',this.csrf);const response=await fetch('/api/v1'+path,{...options,headers,credentials:'same-origin'});if(response.status===204)return undefined as T;const body=await response.json().catch(()=>({detail:'The server returned an unreadable response.'}));if(!response.ok){const detail=body.detail;throw new ApiError(typeof detail==='string'?detail:Array.isArray(detail)?detail.map((x:{msg?:string})=>x.msg).join('. '):'The request could not be completed.',response.status)}return body as T}
  post<T>(path:string,body:unknown,method='POST',key?:string):Promise<T>{return this.request(path,{method,headers:{'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},body:JSON.stringify(body)})}
  async session(){const s=await this.request<Session>('/auth/session');this.csrf=s.csrf_token;return s}
}
export const api = new ApiClient();
export function capability(config:Configuration|null,name:string):{available:boolean;reason:string}{const item=config?.capabilities?.[name];if(typeof item==='boolean')return {available:item,reason:item?'':'This service is not enabled for this workspace.'};if(item)return {available:item.enabled??item.available??false,reason:item.reason||'This service is not enabled for this workspace.'};return {available:false,reason:'Service availability has not been verified. Manual documentation remains available.'}}
