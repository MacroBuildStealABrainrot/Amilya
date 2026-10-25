export type Value = Record<string, unknown>;
export interface User {id:string;email:string;display_name:string;workspace_id:string;is_platform_admin:boolean;profile:Record<string,unknown>}
export interface Session {user:User;csrf_token:string;requires_legal_acceptance?:boolean}
export interface Patient {id:string;display_name:string;external_ref?:string;created_at:string}
export interface Wound {id:string;patient_id:string;label:string;location?:string;laterality?:string;wound_type?:string}
export interface Encounter {id:string;patient_id:string;wound_id?:string;title:string;status:string;revision:number;updated_at:string;draft_data?:Record<string,unknown>}
export interface Evidence {id:string;kind:string;text?:string;created_at?:string;filename?:string;warnings?:unknown[];metadata?:Value}
export interface Fact {id:string;revision:number;name:string;value:Value;confirmation_state:'unconfirmed'|'confirmed'|'rejected';source_type:string;source_ref?:string;source_quote?:string;wound_id?:string;confirmed_by?:string;confirmed_at?:string}
export interface ClinicalDocument {id:string;text:string;content_hash:string;status:string;version:number;case_revision:number;template?:string;custom_template?:CustomTemplate|null;signed_at?:string;signed_by?:string;amends_id?:string}
export interface Review {gaps:unknown[];warnings:unknown[];conflicts:unknown[];considerations:unknown[];case_revision:number}
export interface CaseData {uploadedEvidenceId?:string;encounter:Encounter;patient:Patient;wound:Wound|null;evidence:Evidence[];facts:Fact[];documents:ClinicalDocument[];review:Review|null;conflicts:unknown[]}
export interface CustomTemplate {name:string;instructions:string}
export type Template = 'nursing'|'soap'|'sbar'|'handoff'|'referral'|'physician_update'|'referral_letter'|'custom';
export type Mode = 'auto'|'fast'|'advanced'|'deep';
export interface Draft {text:string;template:Template;customTemplate?:CustomTemplate;updatedAt:number;caseRevision:number;savedDocumentId?:string;amendsId?:string;amendReason?:string}
export interface Capability {available?:boolean;enabled?:boolean;reason?:string}
export interface Configuration {capabilities?:Record<string,Capability|boolean>;public_signup?:boolean;launch_ready?:boolean;[key:string]:unknown}
export interface Plan {id:string;name:string;price_cad:number;credits:number;encounter_estimate:number}
export interface Plans {plans:Plan[];topup:{price_cad:number;credits:number};currency:string}
export interface Billing {plan?:string;status:string;active?:boolean;balance_credits:number;reserved_credits:number;is_unlimited:boolean;history:Record<string,unknown>[]}
export const human = (value:string) => value.replaceAll('_',' ').replace(/^./,s=>s.toUpperCase());
export function formatValue(value:unknown):string {if(value==null)return 'Not recorded';if(typeof value!=='object')return String(value);if(Array.isArray(value))return value.map(formatValue).join('; ');const v=value as Value;if(v.text!==undefined)return String(v.text);if(v.length_cm!==undefined)return `${v.length_cm} × ${v.width_cm}${v.depth_cm!==undefined?` × ${v.depth_cm}`:''} cm`;return Object.entries(v).filter(([,x])=>x!=null).map(([k,x])=>`${human(k)}: ${formatValue(x)}`).join(' · ')}
export function issueText(value:unknown):string {if(typeof value==='string')return value;const v=(value||{}) as Value;return [v.title||v.question||v.message||v.code,v.rationale||v.detail||v.description||v.suggested_consideration].filter(Boolean).join(' — ')||formatValue(v)}

export interface DocumentResult {text:string;case_revision:number;status?:string;ui_message?:string;ui_alerts?:{field:string;message:string}[];warnings?:unknown[];fact_snapshot?:unknown[]}
