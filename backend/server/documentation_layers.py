"""Versioned, application-owned instructions; clinical/user content stays in user messages."""
from contextvars import ContextVar
from pathlib import Path
from .documentation import ALIASES

ROOT=Path(__file__).parent/'prompts'/'documentation'
VERSION='documentation_layers_v2'
selection=ContextVar('documentation_selection',default=('OTHER_CLINICIAN','nursing'))
ROLES={'NURSE','NURSE_PRACTITIONER','PHYSICIAN','OTHER_CLINICIAN'}
TYPES={'nursing','soap','sbar','physician_update','referral_letter','handoff','custom'}

MODE_ROLES={'nurse':'NURSE','np':'NURSE_PRACTITIONER','doctor':'PHYSICIAN'}

def clinician_role(user):
    profile=getattr(user,'profile',None) or {}
    # The Nurse / NP / Doctor switch wins; older accounts fall back to the typed profession.
    mode=profile.get('clinician_mode')
    if isinstance(mode,str) and mode in MODE_ROLES:return MODE_ROLES[mode]
    value=profile.get('profession','')
    if not isinstance(value,str):return 'OTHER_CLINICIAN'
    value=value.strip().upper().replace(' ','_')
    return {'RN':'NURSE','RPN':'NURSE','REGISTERED_NURSE':'NURSE','NP':'NURSE_PRACTITIONER','MD':'PHYSICIAN','DOCTOR':'PHYSICIAN'}.get(value,value if value in ROLES else 'OTHER_CLINICIAN')

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
    ])

CONSULTANT_VOICES={
    'NURSE':'Consultant voice: NURSE. The clinician asking is a nurse. Answer as an experienced nursing colleague: practical bedside and community priorities, what to assess and document next, and when and how to involve the prescriber or another discipline. Use wound-specialist depth only when the question is about a wound.',
    'NURSE_PRACTITIONER':'Consultant voice: NURSE_PRACTITIONER. The clinician asking is a nurse practitioner. Answer as an experienced NP or physician colleague: the leading explanation, the finding or test that best separates it from the alternatives, and management considerations within NP scope. Use wound-specialist depth only when the question is about a wound.',
    'PHYSICIAN':'Consultant voice: PHYSICIAN. The clinician asking is a physician. Answer as an experienced physician colleague across specialties: the leading explanation, the finding or test that best separates it from the alternatives, and management considerations. Use wound-specialist depth only when the question is about a wound.',
    'OTHER_CLINICIAN':'Consultant voice: OTHER_CLINICIAN. Answer as an experienced clinical colleague. Use wound-specialist depth only when the question is about a wound.',
}

def consultant_voice():
    """Perspective and depth only. Every consultant safety rule still applies."""
    role,_=selection.get()
    return CONSULTANT_VOICES.get(role,CONSULTANT_VOICES['OTHER_CLINICIAN'])+' This changes perspective and depth only; all other consultant rules still apply, including no invented doses, no orders and no claimed guideline citations.'
