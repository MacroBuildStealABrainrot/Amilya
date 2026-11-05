"""Real-AI report evaluation on the ISOLATED TEST SERVER only, with made-up visits.

Runs once in the background at start-up when AMILYA_REPORT_EVAL=<label> is set. Each scenario goes through the
same report pipeline the app uses (providers.quick_documentation), inside a temporary visit that is erased
afterwards. One log line per scenario starts with AMILYA_REPORT_EVAL and holds the label, the checks and the
(made-up) report, so before/after runs can be compared. Never runs anywhere except the isolated test server.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from types import SimpleNamespace

logger = logging.getLogger('woundcopilot.report_eval')

# Every visit below is invented. No real patient, clinician or date of a real visit.
SCENARIOS = [
    {'id': 'oncology_bleeding_lesion', 'specialty': 'community nursing / oncology', 'profession': 'RPN', 'template': 'nursing',
     'text': "Okay so this client has lymphoma, she's immune compromised. She also has cell cancer, one lesion on the left thigh. "
             "It's bleeding today, she says it hurts, pain about 6 out of 10. It's getting bigger and there's more drainage than last week, "
             "serous, moderate amount. She cannot change her own dressing so we're seeing her daily. I cleansed with saline and applied Mepilex. "
             "High risk for infection. She has surgery scheduled November 6th.",
     'must': [r'lymphoma', r'immuno-?\s?compromised|immune[- ]compromised', r'left thigh', r'bleed', r'6\s*/\s*10|6 out of 10',
              r'(increas|bigger|larger)\w*.{0,40}size|size.{0,40}increas', r'drainage', r'daily', r'(unable|cannot|can.t).{0,30}(change|manage).{0,20}dressing',
              r'Mepilex', r'November 6', r'saline|NS\b'],
     'must_not': [r'squamous|basal|melanoma', r'\bcell cancer\b', r'physician (was )?(notified|informed)', r'no signs of infection'],
     'clarify': True},
    {'id': 'routine_catheter', 'specialty': 'community nursing', 'profession': 'RPN', 'template': 'nursing',
     'text': 'Seen for blocked suprapubic catheter. Changed it, 16 French, clear yellow urine back right away. No other concerns, thanks.',
     'must': [r'suprapubic', r'16\s*(Fr|French)', r'clear'], 'must_not': [r'no signs of infection', r'tolerated'], 'max_words': 45},
    {'id': 'long_wound_soap_with_chatter', 'specialty': 'wound care', 'profession': 'RN', 'template': 'soap',
     'text': "Hi how was the hockey game last night, oh nice. Okay, client seen for the left, sorry, correction, right heel pressure injury. "
             "She says pain is 3 out of 10 with dressing changes, no fever or chills. Diabetes, on metformin. Wound measures 3.2 by 2.1 cm, "
             "depth 0.4 cm. Wound bed 70 percent granulation 30 percent slough, edges rolled, periwound macerated. Moderate serous drainage, no odour. "
             "Cleansed with saline, Inadine applied, covered with Mepilex Border, heel offloaded with a pillow. Plan dressing every 3 days and "
             "she'll see the wound clinic next Tuesday. My daughter also plays hockey actually.",
     'must': [r'right heel', r'3\s*/\s*10', r'3\.2\s*x\s*2\.1|3\.2 by 2\.1', r'0\.4', r'granulation', r'slough', r'macerat', r'Inadine',
              r'Mepilex', r'offload', r'(every|q)\s*3\s*days|q3', r'diabet', r'metformin', r'denies fever|no fever'],
     'must_not': [r'hockey', r'left heel', r'osteomyelitis is', r'cellulitis is present']},
    {'id': 'medicine_heart_failure', 'specialty': 'internal medicine', 'profession': 'MD', 'template': 'soap',
     'text': "Seventy eight year old man with heart failure, ejection fraction 30 percent. Three kilo weight gain this week, more short of breath "
             "climbing stairs, orthopnea two pillows, no chest pain. BP 142 over 86, heart rate 96, sats 93 percent room air. Crackles at both bases, "
             "pitting edema to the knees. I'm increasing furosemide from 40 mg to 80 mg daily, checking electrolytes and creatinine in 3 days, "
             "and see him back in one week.",
     'must': [r'heart failure', r'30\s*%|30 percent', r'3\s*(kg|kilo)', r'142\s*/\s*86', r'96', r'93\s*%', r'crackles', r'edema',
              r'furosemide.{0,40}80\s*mg|80\s*mg.{0,40}furosemide', r'40\s*mg', r'electrolytes|creatinine', r'(one|1) week'],
     'must_not': [r'chest pain (is )?present', r'spironolactone', r'BNP']},
    {'id': 'surgery_post_op', 'specialty': 'general surgery', 'profession': 'Surgeon', 'template': 'nursing',
     'text': "Post-op day 2 laparoscopic cholecystectomy. Port sites clean and dry, no erythema. JP drain 50 mL serosanguinous over 24 hours. "
             "Tolerating clear fluids, passing flatus. Pain 4 out of 10 on oral analgesia. Plan remove drain tomorrow if under 30 mL, advance diet.",
     'must': [r'(post-?op|postoperative).{0,15}(day )?2|POD ?2', r'cholecystectomy', r'50\s*mL', r'serosanguin', r'flatus', r'4\s*/\s*10',
              r'30\s*mL', r'drain'], 'must_not': [r'bowel sounds', r'abdomen (is )?soft']},
    {'id': 'emergency_hypoxia_transfer', 'specialty': 'emergency / community', 'profession': 'RN', 'template': 'nursing',
     'text': "Wife said he's been more tired and confused since yesterday. On arrival sats 86 percent on room air, resp rate 28, heart rate 118, "
             "temp 38.4. Recently discharged after aspiration pneumonia. I called 911, EMS came and took him to the emergency department.",
     'must': [r'86\s*%', r'28', r'118', r'38\.4', r'(wife|spouse).{0,30}(report|state|said)', r'aspiration pneumonia', r'(EMS|paramedics).{0,60}(transport|took|transfer)|transported'],
     'must_not': [r'ER recommended|recommended.{0,20}(emergency|ED|ER)\b(?!.*transport)', r'client is (lethargic|confused)', r'lungs clear']},
    {'id': 'dermatology_drug_rash', 'specialty': 'dermatology consult', 'profession': 'Dermatologist', 'template': 'soap',
     'text': "Rash started 5 days after starting amoxicillin. Itchy, red, blotchy on trunk and arms. No blistering, no mouth or eye involvement, "
             "no fever. Stop amoxicillin, cetirizine for itch, review in 48 hours or sooner if blistering or mucosal symptoms.",
     'must': [r'amoxicillin', r'(5|five) days', r'trunk', r'arms', r'(no|without|denies).{0,40}blister', r'(mouth|mucosal|oral)', r'cetirizine', r'48 hours'],
     'must_not': [r'\d+\s*mg', r'DRESS (is|confirmed)', r'Stevens']},
    {'id': 'palliative_symptom', 'specialty': 'palliative care', 'profession': 'RPN', 'template': 'nursing',
     'text': "Palliative client with metastatic lung cancer. Daughter reports more breathless overnight. Pain 8 out of 10 in the chest. "
             "Gave hydromorphone 1 mg subcut at 14:00 as per order. Will reassess in 30 minutes. Goals of care comfort at home.",
     'must': [r'metasta', r'daughter.{0,30}(report|state)', r'8\s*/\s*10', r'hydromorphone.{0,20}1\s*mg', r'(subcut|SC|subcutaneous)', r'14:00|1400',
              r'30 minutes', r'comfort'],
     'must_not': [r'(pain|symptoms?) (improved|relieved|decreased)|good effect|effective', r'PPS\s*\d', r'ESAS']},
    {'id': 'physician_update_toe', 'specialty': 'wound care letter', 'profession': 'RPN', 'template': 'physician_update',
     'text': "Letter to the doctor. Left third toe has dry black eschar at the tip, mild swelling and redness, no drainage. "
             "Compression makes her foot throb when tightened. Previous ABI normal. Please assess circulation and whether daily wound care is needed.",
     'must': [r'left (3rd|third) toe', r'eschar', r'(swell|edema)', r'(red|erythema)', r'compression', r'(ABI|ABPI)', r'(circulation|vascular|perfusion)',
              r'daily'], 'must_not': [r'gangrene (is )?(present|confirmed)', r'antibiotic']},
    {'id': 'np_uti_short', 'specialty': 'primary care NP', 'profession': 'Nurse Practitioner', 'template': 'nursing',
     'text': 'Burning on urination 2 days, frequency, no fever, no flank pain. Urine dip positive nitrites and leukocytes. Nitrofurantoin started per guideline, culture sent.',
     'must': [r'(2|two) days', r'frequency', r'nitrite', r'leukocyte', r'nitrofurantoin', r'culture', r'(no|denies).{0,30}fever'],
     'must_not': [r'\d+\s*mg', r'pyelonephritis (is )?(present|confirmed)'], 'max_words': 70},
]


class _ProfessionUser:
    """The real test user (for usage limits), seen by the report layers with this scenario's profession."""
    def __init__(self, user, profession):
        self._user = user
        self.profile = {**(user.profile or {}), 'profession': profession}

    def __getattr__(self, name):
        return getattr(self._user, name)


def _check(scenario, result):
    text = result.get('text') or ''
    from .source_coverage import coverage_defects
    missing = [p for p in scenario.get('must', []) if not re.search(p, text, re.I | re.S)]
    invented = [p for p in scenario.get('must_not', []) if re.search(p, text, re.I | re.S)]
    alerts = [a for a in (result.get('ui_alerts') or []) if isinstance(a, dict)]
    clarify = [a for a in alerts if a.get('field') == 'clarify']
    words = len(text.split())
    problems = []
    if missing: problems.append('missing')
    if invented: problems.append('unsupported_or_wrong')
    if scenario.get('clarify') and not clarify: problems.append('no_clarify_alert')
    if scenario.get('max_words') and words > scenario['max_words']: problems.append('too_long')
    return {'passed': not problems and bool(text.strip()), 'problems': problems, 'missing': missing, 'invented': invented, 'words': words,
            'clarify_alerts': [a.get('message') for a in clarify],
            'coverage_after': coverage_defects(text, {'untrusted_sources': [{'text': scenario['text']}]})}


async def run(label):
    from sqlalchemy import select
    from .db import SessionLocal, utcnow
    from .identity import User
    from .clinical_models import Encounter, Patient
    from . import providers, documentation
    from .work_privacy import erase_work
    from .report_errors import TerminalReportError
    with SessionLocal() as db:
        user = db.scalar(select(User).where(User.is_platform_admin.is_(True), User.is_active.is_(True)).order_by(User.created_at))
        user = user or db.scalar(select(User).where(User.is_active.is_(True)).order_by(User.created_at.desc()))
        if user is None:
            logger.warning('AMILYA_REPORT_EVAL %s', json.dumps({'label': label, 'error': 'no test user'}))
            return
        patient_id = f'eval-{user.workspace_id}'[:36]
        if db.get(Patient, patient_id) is None:
            db.add(Patient(id=patient_id, workspace_id=user.workspace_id, display_name='Made-up evaluation container', external_ref=None))
            db.commit()
        summary = []
        for scenario in SCENARIOS:
            from .db import uid
            encounter = Encounter(id=uid(), workspace_id=user.workspace_id, patient_id=patient_id, wound_id=None,
                title='Made-up evaluation visit', status='open', revision=1, created_by=user.id,
                draft_data={'retention': 'temporary', 'quick_mode': 'document', 'evaluation': label})
            db.add(encounter); db.commit()
            context = {'photo_groups_clinician_assigned': [], 'case_facts': [], 'rejected_claims_do_not_reinstate': [],
                       'untrusted_sources': [{'id': 'eval-source', 'kind': 'audio', 'text': scenario['text'], 'warnings': [],
                                              'created_at': utcnow().isoformat()}]}
            record = {'label': label, 'scenario': scenario['id'], 'specialty': scenario['specialty'],
                      'profession': scenario['profession'], 'template': scenario['template']}
            try:
                result = await providers.quick_documentation(context, scenario['template'], 'concise',
                    user=_ProfessionUser(user, scenario['profession']), encounter_id=encounter.id)
                result = documentation.visible_result(result)
                record.update(_check(scenario, result))
                record.update({'qa': result.get('qa'), 'text': result.get('text'), 'alerts': result.get('ui_alerts')})
            except TerminalReportError as error:
                record.update({'passed': False, 'problems': ['report_not_generated'], 'error': str(error.detail)[:200]})
            except Exception as error:  # evaluation must never affect the server
                record.update({'passed': False, 'problems': ['error'], 'error': type(error).__name__})
            finally:
                try:
                    db.rollback(); erase_work(db, db.get(Encounter, encounter.id)); db.commit()
                except Exception:
                    db.rollback()
            logger.warning('AMILYA_REPORT_EVAL %s', json.dumps(record, ensure_ascii=False, default=str))
            summary.append((scenario['id'], record.get('passed')))
        logger.warning('AMILYA_REPORT_EVAL %s', json.dumps({'label': label, 'summary': summary,
            'passed': sum(1 for _, ok in summary if ok), 'total': len(summary)}))


def start_if_requested():
    from . import isolation
    label = os.environ.get('AMILYA_REPORT_EVAL', '').strip()
    if not label or not re.fullmatch(r'[A-Za-z0-9_.-]{1,40}', label) or not isolation.is_isolated_test_server():
        return None
    async def later():
        await asyncio.sleep(5)
        try:
            await run(label)
        except Exception as error:
            logger.warning('AMILYA_REPORT_EVAL %s', json.dumps({'label': label, 'error': type(error).__name__}))
    return asyncio.create_task(later())
