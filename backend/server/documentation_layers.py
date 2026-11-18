"""Versioned, application-owned instructions; clinical/user content stays in user messages."""
from contextvars import ContextVar
from pathlib import Path
from .documentation import ALIASES

ROOT=Path(__file__).parent/'prompts'/'documentation'
VERSION='documentation_layers_v2'
selection=ContextVar('documentation_selection',default=('OTHER_CLINICIAN','nursing'))
ROLES={'NURSE','NURSE_PRACTITIONER','PHYSICIAN','OTHER_CLINICIAN'}
TYPES={'nursing','soap','sbar','physician_update','referral_letter','handoff','diagnostics','medical_order','custom'}

def clinician_role(user):
    value=(getattr(user,'profile',None) or {}).get('profession','')
    if not isinstance(value,str):return 'OTHER_CLINICIAN'
    value=value.strip().upper().replace(' ','_').replace('-','_')
    exact={'RN':'NURSE','RPN':'NURSE','LPN':'NURSE','REGISTERED_NURSE':'NURSE','NP':'NURSE_PRACTITIONER','RN(EC)':'NURSE_PRACTITIONER','MD':'PHYSICIAN','DO':'PHYSICIAN','DOCTOR':'PHYSICIAN'}
    if value in exact:return exact[value]
    if value in ROLES:return value
    # Free-typed professions: recognise common wordings rather than falling back to a neutral voice.
    if 'PRACTITIONER' in value:return 'NURSE_PRACTITIONER'
    if 'NURS' in value or 'NSWOC' in value:return 'NURSE'
    if any(word in value for word in ('PSYCHOLOGIST','PODIATRIST','CHIROPODIST','PHYSIOTHERAP','DIETITIAN','PHARMACIST','AUDIOLOGIST','TECHNOLOGIST','THERAPIST')):return 'OTHER_CLINICIAN'
    if value in {'GP','FP','FAMILY_PHYSICIAN'} or any(word in value for word in ('DOCTOR','PHYSICIAN','SURGEON','RESIDENT','HOSPITALIST','INTERNIST','FAMILY_MEDICINE','OLOGIST','IATRIST')):return 'PHYSICIAN'
    return 'OTHER_CLINICIAN'

def select(user,template):
    template=ALIASES.get(template,template)
    return selection.set((clinician_role(user),template if template in TYPES else 'nursing'))

def instructions():
    role,report=selection.get()
    # Both path components come exclusively from closed internal sets.
    return '\n\n'.join([
        f'Documentation layers: {VERSION}; role={role}; format={report}.',
        (ROOT/'roles'/f'{role.lower()}_v1.md').read_text(encoding='utf-8'),
        (ROOT/'formats'/f'{report}_v1.md').read_text(encoding='utf-8'),
        (ROOT/'community_nursing_v1.md').read_text(encoding='utf-8') if role in {'NURSE','OTHER_CLINICIAN'} else (ROOT/'clinical_style_v1.md').read_text(encoding='utf-8'),
        (ROOT/'clinical_reasoning_v1.md').read_text(encoding='utf-8'),
        (ROOT/'charting_rules_v1.md').read_text(encoding='utf-8'),
        # Owner-supplied wound wording list: comes after every other rule, so the earlier rules win.
        (ROOT/'wound_vocabulary_v1.md').read_text(encoding='utf-8'),
        *([examples.read_text(encoding='utf-8')] if (examples:=ROOT/'examples'/f'{report}_v1.md').is_file() else []),
    ])
