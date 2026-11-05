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
    return list(dict.fromkeys(defects))[:12]
