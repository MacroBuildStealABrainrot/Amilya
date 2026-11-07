"""Free, instant comparison of a generated report with the visit sources (no AI call).

It catches the losses and changes that word-level comparison can see reliably:
- a measurement, dose, vital sign, score or date in the sources that is missing from the report;
- a measurement, dose, vital sign, score or date in the report that is in no source (possible invention);
- a named product, dressing or drug in the sources that is missing from the report;
- a key diagnosis or risk state in the sources that is missing from the report;
- a side (left/right) in the report that never appears in the sources.
It cannot judge meaning (for example a lost "worse than last week"); the AI check does that.
Results are short English defect lines for the AI repair step, never patient text beyond the matched term.
"""
from __future__ import annotations

import re

_MONTHS = 'january|february|march|april|may|june|july|august|september|october|november|december'
_UNITS = r'(?:cm|mm|mg|mcg|g|kg|ml|mL|L|units?|u|mmol/L|mmol|%|/10|Fr|French|bpm|°C|C|degrees)'
_NUMBER_PATTERNS = [
    re.compile(r'\b\d{2,3}\s*/\s*\d{2,3}\b'),                                   # blood pressure
    re.compile(r'\b\d+(?:\.\d+)?\s*(?:x|by)\s*\d+(?:\.\d+)?(?:\s*(?:x|by)\s*\d+(?:\.\d+)?)?\s*(?:cm|mm)\b', re.I),  # wound size
    re.compile(r'\b\d+(?:\.\d+)?\s*' + _UNITS + r'(?![A-Za-z])', re.I),          # value with unit
    re.compile(r'\b\d{1,2}\s*(?:out of|/)\s*10\b', re.I),                       # pain score
]
_DATE = re.compile(r'\b(' + _MONTHS + r')\s+(\d{1,2})(?:st|nd|rd|th)?\b|\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(' + _MONTHS + r')\b', re.I)

KEY_CONDITIONS = {
    'lymphoma': r'lymphoma', 'leukemia': r'leuka?emia', 'cancer': r'cancer|carcinoma|malignan\w*|tumou?r|metasta\w*',
    'immunocompromised': r'immuno-?(?:compromised|suppress\w*)|immune[- ]compromised|neutropeni\w*',
    'diabetes': r'diabet\w*', 'cellulitis': r'cellulitis', 'osteomyelitis': r'osteomyelitis', 'sepsis': r'sepsis|septic',
    'gangrene': r'gangren\w*', 'pneumonia': r'pneumonia', 'heart failure': r'heart failure|\bCHF\b', 'COPD': r'\bCOPD\b',
    'DVT': r'\bDVT\b|deep vein thrombosis', 'pulmonary embolism': r'pulmonary embol\w*|\bPE\b', 'stroke': r'stroke|\bCVA\b',
    'myocardial infarction': r'myocardial infarction|heart attack|\bSTEMI\b|\bNSTEMI\b', 'dementia': r'dementia',
    'fracture': r'fractur\w*', 'anticoagulation': r'anticoagula\w*|apixaban|rivaroxaban|warfarin|heparin|enoxaparin',
    'peripheral arterial disease': r'peripheral arterial disease|\bPAD\b', 'palliative': r'palliative|end of life|end-of-life',
    'allergy': r'allerg\w*', 'surgery': r'surgery|surgical|operation|procedure scheduled',
}

PRODUCTS = ['PureCleanse', 'Inadine', 'Bactigras', 'Biatain', 'Mesorb', 'Mepilex', 'Betadine', 'povidone', 'Coban', 'Kling',
            'Tegaderm', 'Bacitracin', 'Acticoat', 'Aquacel', 'Hydrofera', 'Vashe', 'Kerramax', 'Iodosorb', 'Mepitel', 'Allevyn',
            'Duoderm', 'Intrasite', 'Medihoney', 'Silvercel', 'Prontosan', 'PICO', 'VAC', 'zinc', 'Tubigrip', 'Compreflex',
            'Coloplast', 'Kerlix', 'Xeroform', 'Adaptic', 'Jelonet', 'UrgoClean', 'Urgotul', 'Exufiber', 'Sorbact', 'Promogran']

_WORD_NUMBERS = {'zero': '0', 'one': '1', 'two': '2', 'three': '3', 'four': '4', 'five': '5', 'six': '6', 'seven': '7',
                 'eight': '8', 'nine': '9', 'ten': '10', 'eleven': '11', 'twelve': '12', 'fifteen': '15', 'twenty': '20',
                 'thirty': '30', 'first': '1', 'second': '2', 'third': '3', 'fourth': '4', 'fifth': '5', 'sixth': '6',
                 'seventh': '7', 'eighth': '8', 'ninth': '9', 'tenth': '10'}


def _normalise(text: str) -> str:
    text = re.sub(r'\b(' + '|'.join(_WORD_NUMBERS) + r')\b', lambda m: _WORD_NUMBERS[m.group(1).lower()], text, flags=re.I)
    text = re.sub(r'(\d),(\d{3})', r'\1\2', text)
    return text


def _squash(value: str) -> str:
    value = value.lower().replace('out of 10', '/10').replace(' by ', 'x').replace('millilitres', 'ml')
    return re.sub(r'[\s.]+$', '', re.sub(r'\s+', '', value)).replace('ml', 'ml')


def _numbers(text: str) -> set[str]:
    found = set()
    for pattern in _NUMBER_PATTERNS:
        for match in pattern.finditer(text):
            found.add(_squash(match.group(0)))
    return found


def _dates(text: str) -> set[str]:
    out = set()
    for m in _DATE.finditer(text):
        month = (m.group(1) or m.group(4)).lower()
        day = int(m.group(2) or m.group(3))
        if 1 <= day <= 31:
            out.add(f'{month} {day}')
    return out


def _contains_number(report: str, item: str) -> bool:
    compact = re.sub(r'\s+', '', report.lower())
    if item in compact:
        return True
    digits = re.findall(r'\d+(?:\.\d+)?', item)
    # A size or BP is found when its numbers appear in the same order close together.
    return bool(digits) and re.search(r'\D{0,12}'.join(map(re.escape, digits)), compact) is not None


def source_text(context: dict) -> str:
    parts = [str(s.get('text') or '') for s in (context or {}).get('untrusted_sources', [])]
    import json
    parts += [json.dumps(f.get('value'), ensure_ascii=False, default=str) for f in (context or {}).get('case_facts', []) if isinstance(f, dict)]
    # The clinician's own typed or spoken words sent with a photo or from Ask Consultant are sources too.
    turns = ((context or {}).get('consultation') or {}).get('turns') or []
    parts += [str(t.get('text') or '') for t in turns if isinstance(t, dict) and t.get('speaker') == 'clinician_message']
    return '\n'.join(parts)


def coverage_defects(report: str, context: dict) -> list[str]:
    source = _normalise(source_text(context))
    note = _normalise(report or '')
    if not source.strip() or not note.strip():
        return []
    defects = []
    note_compact = re.sub(r'\s+', '', note.lower())
    for item in sorted(_numbers(source)):
        if not _contains_number(note, item):
            defects.append(f'The sources contain the value "{item}" but the report does not include it; keep it exactly as stated if it is clinical.')
    source_digits = set(re.findall(r'\d+(?:\.\d+)?', source))
    for item in sorted(_numbers(note)):
        # Lenient on purpose: flag only a value whose numbers appear nowhere in the sources.
        if not set(re.findall(r'\d+(?:\.\d+)?', item)) <= source_digits:
            defects.append(f'The report contains the value "{item}" that is not in any source; remove it unless the sources state it.')
    source_dates, note_dates = _dates(source), _dates(note)
    for item in sorted(source_dates - note_dates):
        defects.append(f'The sources mention the date "{item}" but the report does not.')
    for item in sorted(note_dates - source_dates):
        defects.append(f'The report contains the date "{item}" that is not in the sources.')
    for product in PRODUCTS:
        pattern = re.compile(r'\b' + re.escape(product), re.I)
        if pattern.search(source) and not pattern.search(note):
            defects.append(f'The sources name "{product}" but the report does not.')
    for name, pattern in KEY_CONDITIONS.items():
        rx = re.compile(r'(?<![A-Za-z])(?:' + pattern + r')', re.I)
        if rx.search(source) and not rx.search(note):
            defects.append(f'The sources mention {name} but the report does not.')
    for side in ('left', 'right'):
        if re.search(r'\b' + side + r'\b', note, re.I) and not re.search(r'\b' + side + r'\b', source, re.I):
            defects.append(f'The report says "{side}" but no source mentions {side}; check the side.')
    defects += negation_defects(note, source)
    return list(dict.fromkeys(defects))[:12]


# ---------- Negation: "no pain" must never become "pain", and a "denies X" needs a source (owner principle 2026-11-07) ----------
NEGATABLE = {'pain': r'pain', 'fever': r'fever|febrile', 'chills': r'chills|rigors', 'cough': r'cough', 'shortness of breath': r'shortness of breath|\bSOB\b|dyspn\w*',
             'nausea': r'nause\w*', 'vomiting': r'vomit\w*', 'diarrhea': r'diarrh\w*', 'dizziness': r'dizz\w*', 'chest pain': r'chest pain',
             'bleeding': r'bleed\w*', 'odour': r'odou?r', 'swelling': r'swell\w*|o?edema', 'redness': r'redness|erythema'}
_NEG_WORD = r'\b(?:no|not|denies|denied|denying|without|nil|negative for|free of|absent)\b'


def _sentences(text):
    # Clauses joined by "but" are judged apart: "no pain at rest, but pain with walking" holds both.
    return [s for s in re.split(r'(?<=[.!?;])\s+|\n+|,?\s+\bbut\b\s+', text or '', flags=re.I) if s and s.strip()]


def _states(text, pattern):
    """('negated', 'present') seen for one symptom: plain statements and spoken question-and-answer ("Any pain? No.")."""
    negated = present = False
    flat = ' '.join((text or '').split())
    if re.search(r'(?i)(?:' + pattern + r')[^.?!]{0,40}\?\s*(?:no|nope|none|not really)\b', flat):
        negated = True
    for sentence in _sentences(text):
        if '?' in sentence or not re.search(r'(?i)(?:' + pattern + r')', sentence):
            continue
        if re.search(r'(?i)' + _NEG_WORD + r'[^.!?]{0,60}(?:' + pattern + r')|(?:' + pattern + r')[^.!?]{0,15}\b(?:0\s*/\s*10|zero out of ten)|(?:' + pattern + r')[- ]free', sentence):
            negated = True
        else:
            present = True
    return negated, present


def negation_defects(note: str, source: str) -> list[str]:
    out = []
    for name, pattern in NEGATABLE.items():
        source_negated, source_present = _states(source, pattern)
        note_negated, note_present = _states(note, pattern)
        if source_negated and not source_present and note_present:
            out.append(f'The sources say there is no {name}, but the report states {name} without the negation; check the negation.')
        if note_negated and not source_negated and not source_present and not re.search(r'(?i)(?:' + pattern + r')', source):
            out.append(f'The report says there is no {name}, but no source mentions {name}; remove it unless it was assessed.')
        if note_present and not re.search(r'(?i)(?:' + pattern + r')', source):
            out.append(f'The report states {name}, but no source mentions {name}; remove it unless it was assessed.')
    return out


def strong_findings(defects: list[str]) -> list[str]:
    """Findings that are almost never harmless: a value, date or side in the report that no source contains, or a lost negation."""
    return [item for item in defects if 'not in any source' in item or 'that is not in the sources' in item or 'check the side' in item
            or 'check the negation' in item or 'but no source mentions' in item]


def clarify_alerts(findings: list[str]) -> list[dict]:
    return [{'field': 'clarify', 'message': 'Please check: ' + item} for item in findings[:3]]


def selection_difference_alerts(context: dict, alerts=None) -> list[dict]:
    """A difference between the clinician's photo picks and the photo assessment always reaches the note as its own
    "please confirm" alert, separate from the note text, even when the AI forgot to add it."""
    have = ' '.join(str(a.get('message', '')) for a in (alerts or []) if isinstance(a, dict))
    sources = ' '.join(str(s.get('text') or '') for s in (context or {}).get('untrusted_sources', []) if isinstance(s, dict))
    found = re.findall(r'Possible difference to confirm: (Please confirm [^.]+\. Your selection and the image assessment may differ\.)', sources)
    return [{'field': 'clarify', 'message': m} for m in dict.fromkeys(found) if m not in have]


# ---------- Photo findings must reach the note (found by the evaluation suite: a note dropped every photo finding) ----------
PHOTO_FACT_NAMES = {'wound_tissue', 'wound_edges', 'periwound', 'exudate', 'visible_finding'}
PHOTO_TERMS = {
    'eschar': (r'eschar',), 'slough': (r'slough',), 'granulation tissue': (r'granulat',), 'necrotic tissue': (r'necro',),
    'redness': (r'\bred', r'erythem'), 'swelling': (r'swell', r'o?edema'), 'maceration': (r'macerat',),
    'callus': (r'callus', r'hyperkerat'), 'purulent drainage': (r'purulen', r'\bpus\b'), 'bleeding': (r'bleed', r'\bblood'),
}


WOUND_BED_TERMS = {'eschar', 'slough', 'granulation tissue', 'necrotic tissue'}


def photo_finding_losses(report: str, context: dict, alerts=None) -> list[str]:
    """A tissue or skin finding the photo showed that the note leaves out. Interpretation fields are not checked.
    A finding named in a "please confirm" alert (a conflict with what the clinician recorded) is handled, not lost."""
    alert_text = ' '.join(str(a.get('message', '')) for a in (alerts or []) if isinstance(a, dict))
    seen = ' '.join(str((f.get('value') or {}).get('text', '')) for f in (context or {}).get('case_facts', [])
                    if isinstance(f, dict) and f.get('name') in PHOTO_FACT_NAMES and f.get('confirmation_state') != 'rejected')
    if not seen.strip() or not (report or '').strip():
        return []
    # The clinician's own wound-bed selection outranks the photo: then photo tissue types are not required in the note.
    sources = ' '.join(str(src.get('text') or '') for src in (context or {}).get('untrusted_sources', []) if isinstance(src, dict))
    clinician_bed = re.search(r'Clinician-recorded findings on assessment:[^.]*wound bed:', sources, re.I) is not None
    hedge = re.compile(r'\b(likely|possible|possibly|probable|probably|suggest\w*|may be|might be|appears?|consistent with|query)\b', re.I)
    losses = []
    for label, patterns in PHOTO_TERMS.items():
        if clinician_bed and label in WOUND_BED_TERMS:
            continue
        # Only a finding stated plainly must reach the note; one the photo marked uncertain belongs to the review.
        plain = [sentence for sentence in re.split(r'(?<=[.;])\s+', seen) if not hedge.search(sentence)]
        in_photo = any(re.search(p, sentence, re.I) for sentence in plain for p in patterns)
        if in_photo and not any(re.search(p, report, re.I) or re.search(p, alert_text, re.I) for p in patterns):
            losses.append(f'The photo assessment found {label}, but the note leaves it out; add it as a photo finding.')
    return losses
