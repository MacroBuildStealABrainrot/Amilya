"""'Anything missed?' reminders.

Plain word matching over what the clinician actually recorded, typed or attached. It never
calls an AI, never writes into the note and never decides that something is normal: it only
lists topics that were not mentioned, so the clinician can add them by voice or skip them.
"""
from __future__ import annotations

import re

WOUND_VISIT = re.compile(r'(?i)\b(?:wound|ulcer|incision|dressing|pressure (?:injury|sore|ulcer)|laceration|skin tear|burn|stoma|blister)')

PAIN = ('pain', 'Pain', r'\bpain|painful|\d+\s*/\s*10\b|out of (?:ten|10)\b|discomfort|\btender')
NEXT_VISIT = ('next_visit', 'Next visit or plan', r'next (?:visit|dressing|change|week)|\bplan|\breturn|follow|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|in \d+ (?:day|week)|weekly|daily|twice|every \d|\bcontinue|discharg|\brefer')

NURSE_WOUND = [
    PAIN,
    ('size', 'Wound size', r'\b\d+(?:\.\d+)?\s*(?:cm|mm|centimet|millimet)|\bmeasur|\blength\b|\bwidth\b|\bdepth\b|\d\s*x\s*\d'),
    ('wound_bed', 'Wound bed', r'granulat|slough|eschar|necro|epitheli|wound bed|\bbed\b|\btissue|fibrin'),
    ('drainage', 'Drainage', r'drain|exudat|serous|sanguin|purulent|\bpus\b|\bdry\b(?!\s+(?:dressing|gauze))|\bmoist|weeping|\bleak'),
    ('periwound', 'Skin around the wound', r'peri-?wound|surrounding skin|macerat|erythem|redness|indurat|callus|\bintact|o?edema|swelling|swollen'),
    ('dressing', 'Dressing used', r'dressing|\bapplied|covered|packed|cleans|irrigat|gauze|\bfoam|alginate|hydrocolloid|\bsilver|iodine|inadine|bactigras|mepilex|aquacel|biatain|tegaderm|\bkling|coban|\babd\b|\bvac\b'),
    NEXT_VISIT,
]
NURSE_GENERAL = [
    PAIN,
    ('vitals', 'Vital signs', r'\bbp\b|blood pressure|heart rate|\bhr\b|\bpulse|\btemp|resp|\brr\b|\bo2\b|\bsats?\b|spo2|oxygen'),
    NEXT_VISIT,
]
PRESCRIBER = [
    ('complaint', 'Reason for visit', r'complain|\bpresent|here (?:for|today)|reason for|\bc/o\b|concern|came in|referred for|\breports?\b|\bhpi\b|follow[- ]?up (?:for|of|on)'),
    ('exam', 'Exam findings', r'\bexam|o/e|on examination|auscult|palpat|inspect|\bbp\b|blood pressure|heart rate|lungs?\b|chest|abdomen|\btender|range of motion|\brom\b|neuro|vitals|\btemp'),
    ('assessment', 'Assessment', r'assess|impression|diagnos|\bdx\b|\blikely|consistent with|differential|\bddx\b|suspect|probabl|rule out|\br/o\b'),
    ('plan', 'Plan', r'\bplan|\bstart|\bstop|continue|increase|decrease|\border|requisit|\brefer|prescri|\brx\b|advis|recommend|counsel|\bbook|schedul|\blabs?\b|bloodwork|x-?ray|imaging'),
    ('follow_up', 'Follow-up', r'follow[- ]?up|\bf/u\b|\breturn|recheck|re-check|see (?:you|again|back)|in \d+ (?:day|week|month)|next (?:visit|week|month)|\bprn\b|as needed'),
]
# A referral letter is judged by its own reason-for-referral gate, not by visit topics.
SKIPPED_TEMPLATES = {'referral_letter'}
MINIMUM_SOURCE_CHARACTERS = 40


def _source_text(context):
    parts = [str(source.get('text') or '') for source in context.get('untrusted_sources', [])]
    for fact in context.get('case_facts', []):
        value = fact.get('value')
        parts.append(str(fact.get('name') or ''))
        parts.append(str(value.get('text') if isinstance(value, dict) and value.get('text') else value or ''))
    return '\n'.join(part for part in parts if part)


def checklist(role, text):
    if role in {'PHYSICIAN', 'NURSE_PRACTITIONER'}:
        return PRESCRIBER
    return NURSE_WOUND if WOUND_VISIT.search(text) else NURSE_GENERAL


def missing_items(context, role, template):
    """Topics not mentioned anywhere in the visit sources: [{'key', 'label'}], in checklist order."""
    if template in SKIPPED_TEMPLATES:
        return []
    text = _source_text(context)
    if len(text.strip()) < MINIMUM_SOURCE_CHARACTERS:
        return []
    return [{'key': key, 'label': label} for key, label, pattern in checklist(role, text)
            if not re.search(pattern, text, re.IGNORECASE)]


def enabled(user):
    """On unless the clinician hid it."""
    return (getattr(user, 'profile', None) or {}).get('missed_check') is not False
