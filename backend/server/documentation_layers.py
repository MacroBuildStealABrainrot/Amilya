"""Versioned, application-owned instructions; clinical/user content stays in user messages."""
from contextvars import ContextVar
from pathlib import Path
from .documentation import ALIASES

ROOT=Path(__file__).parent/'prompts'/'documentation'
VERSION='documentation_layers_v2'
selection=ContextVar('documentation_selection',default=('OTHER_CLINICIAN','nursing'))
ROLES={'NURSE','NURSE_PRACTITIONER','PHYSICIAN','OTHER_CLINICIAN'}
TYPES={'nursing','soap','sbar','physician_update','referral_letter','handoff','custom'}

def clinician_role(user):
    value=(getattr(user,'profile',None) or {}).get('profession','')
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
