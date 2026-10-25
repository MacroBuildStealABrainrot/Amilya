"""Clinical wording and UI state are separate contracts, shared by every document path."""
from __future__ import annotations

import re

DOCUMENT_TYPES = {
    'nursing': 'Nursing Note', 'soap': 'SOAP Note',
    'physician_update': 'Physician Update', 'referral_letter': 'Referral Letter',
    'sbar': 'SBAR', 'handoff': 'Handoff Report',
}
# Historical records used referral for a physician update. Never reinterpret them as referrals.
ALIASES = {'referral': 'physician_update'}
NO_CONTENT = 'Not enough visit information to generate this document.'
NO_REFERRAL = 'Referral reason not found in visit information.'
DOCUMENT_TASKS = {'quick_draft', 'quick_refine', 'quick_repair', 'improve_report'}
UI_LINES = {
    'draft — clinician review required', 'draft - clinician review required',
    'draft – clinician review required', NO_CONTENT.lower(), NO_REFERRAL.lower(),
}
PLACEHOLDER = re.compile(r'(?i)^(?:n/?a|unknown|not (?:available|provided|documented|assessed)|no information available|unable to determine|information missing|ask clinician|please complete)[.!: ]*$')
MISSING_COMMENT = re.compile(r'(?i)^(?:(?:no|missing)\s+(?:wound\s+)?(?:measurements?|vitals?|clinical history|assessment details?|plan details?|information|values|dimensions)\b.{0,100}\b(?:provided|supplied|documented|recorded|available)|(?:measurements?|drainage|vitals?|history|assessment|plan)\s*:\s*(?:not provided|unknown|n/?a))\.?$')
HEADINGS = {'subjective', 'objective', 'assessment', 'plan', 'situation', 'background', 'recommendation', 'recommendations', 'current problem', 'findings', 'treatment', 'next steps'}


def clinical_text(text: str) -> str:
    """Strip only exact application UI lines; leave all other user wording byte-for-byte."""
    lines = text.splitlines(keepends=True)
    return ''.join(line for line in lines if line.strip().casefold() not in UI_LINES)


def response_schema():
    return {'type': 'object', 'additionalProperties': False, 'properties': {
        'text': {'type': 'string', 'maxLength': 30000},
        'status': {'type': 'string', 'enum': ['ready', 'no_content', 'referral_not_supported']},
        'ui_alerts': {'type': 'array', 'maxItems': 12, 'items': {'type': 'object', 'additionalProperties': False,
            'properties': {'field': {'type': 'string', 'maxLength': 80}, 'message': {'type': 'string', 'maxLength': 400}},
            'required': ['field', 'message']}},
    }, 'required': ['text', 'status', 'ui_alerts']}


def validate_response(data):
    if not isinstance(data, dict) or set(data) - {'text', 'status', 'ui_alerts'}:
        raise ValueError('Invalid clinical document fields')
    text = data.get('text');status = data.get('status', 'ready');alerts = data.get('ui_alerts', [])
    if not isinstance(text, str) or len(text) > 30000 or status not in {'ready', 'no_content', 'referral_not_supported'}:
        raise ValueError('Invalid clinical document')
    if not isinstance(alerts, list) or len(alerts) > 12:
        raise ValueError('Invalid document alerts')
    for alert in alerts:
        if not isinstance(alert, dict) or set(alert) != {'field', 'message'} or any(not isinstance(alert[key], str) or not 1 <= len(alert[key]) <= maximum for key, maximum in [('field', 80), ('message', 400)]):
            raise ValueError('Invalid targeted document alert')
    if (status == 'ready') != bool(text.strip()):
        raise ValueError('Document status and content disagree')
    # Do not silently discard problematic prose: the quality/repair pipeline must fix it.
    return {'text': text, 'status': status, 'ui_alerts': alerts}


def visible_result(result):
    """Application-owned empty-state messages never enter the clinical text field."""
    result = dict(result)
    status = result.get('status', 'ready' if result.get('text', '').strip() else 'no_content')
    result['status'] = status
    result['ui_message'] = NO_REFERRAL if status == 'referral_not_supported' else NO_CONTENT if status == 'no_content' else ''
    if status != 'ready': result['text'] = ''
    result.setdefault('ui_alerts', [])
    return result


_TOPIC = r'(?:education|teaching|instructions?|advice)'
_FILLER = r"(?:on assessment|upon assessment|during today'?s visit|visited client today)"


def _cap(original, replacement):
    return replacement[:1].upper() + replacement[1:] if original[:1].isupper() else replacement


def avoid_unrequested_wording(text, context=None):
    """Community nursing style: do not add 'reinforced' or stock openers the nurse did not say.

    Only wording the AI introduced is changed. If any original source already uses the phrase,
    the text is left exactly as written (dictated wording is preserved). Only education/advice
    phrasing is changed; other clinical uses (for example a reinforced dressing) are never touched.
    """
    if not text:return text
    source = '\n'.join(str(s.get('text') or '') for s in (context or {}).get('untrusted_sources', [])).casefold()
    if 'reinforc' not in source:
        # "Education reinforced" / "Teaching was reinforced" -> "Education provided" / "Teaching was provided"
        text = re.sub(r'(?i)\b(' + _TOPIC + r')((?:\s+(?:was|were|has been|have been))?)\s+reinforced\b',
                      lambda m: m[1] + m[2] + ' provided', text)
        # "Reinforced education on ..." -> "Provided education on ..."
        text = re.sub(r'(?i)\breinforced(?=\s+' + _TOPIC + r'\b)', lambda m: _cap(m[0], 'provided'), text)
        # "Reinforced (the) importance of ..." -> "Discussed (the) importance of ..."
        text = re.sub(r'(?i)\breinforced(?=\s+(?:the\s+)?importance of\b)', lambda m: _cap(m[0], 'discussed'), text)
    if not re.search(_FILLER, source):
        # Drop a stock opener at the start of a line or sentence: "On assessment, wound ..." -> "Wound ..."
        text = re.sub(r'(?im)(^[ \t]*|(?<=[.!?] ))' + _FILLER + r'\s*[,:]?\s+([a-z])',
                      lambda m: m[1] + m[2].upper(), text)
    return text


def clean_generated_layout(result, options=None, context=None):
    """Remove only application labels and explicit documentation-absence filler before QA.

    The semantic checker still compares the resulting text against original sources.
    Clinical negatives (including 'No fever recorded') and inability/refusal remain.
    """
    if result.get('status', 'ready') != 'ready':return result
    if context is not None:
        result = {**result, 'text': avoid_unrequested_wording(result['text'], context)}
    kept = []; removed = False
    for line in result['text'].splitlines():
        if line.strip().casefold() in UI_LINES:
            removed = True
            continue
        parts = re.split(r'(?<=[.!?])\s+', line)
        clean = [part for part in parts if not MISSING_COMMENT.fullmatch(part.strip()) and not PLACEHOLDER.fullmatch(part.strip())]
        if len(clean) != len(parts):removed = True
        if clean:kept.append(line if len(clean) == len(parts) else ' '.join(clean))
    text = '\n'.join(kept) if removed else result['text']
    if (options or {}).get('template') == 'soap':
        text = place_completed_care_in_objective(text)
    if (options or {}).get('template') in {'soap','sbar'}:
        headings='Subjective|Objective|Assessment|Plan' if options['template']=='soap' else 'Situation|Background|Assessment|Recommendation'
        text=re.sub(r'(?im)^[ \t]*('+headings+r')[ \t]*:?[ \t]*$',lambda m:m[1].capitalize()+':',text)
    return {**result, 'text': text, 'status': 'ready' if text.strip() else 'no_content'}


def place_completed_care_in_objective(text):
    """SOAP Plan is future-only. Relocate explicit past care without changing a word."""
    groups = {}; heading = None
    for line in text.splitlines():
        match = re.fullmatch(r'\s*(?:#{1,6}\s*)?(?:\*\*)?(Subjective|Objective|Assessment|Plan)(?:\*\*)?:?\s*', line, re.I)
        if match:
            heading = match[1].capitalize()
            if heading in groups:return text  # Let QA resolve duplicate/malformed sections.
            groups[heading] = []
        elif heading:groups[heading].append(line)
        elif line.strip():return text  # Do not reinterpret unstructured prose.
    if 'Plan' not in groups:return text
    completed = []; future = []
    for sentence in _sentences('\n'.join(groups['Plan'])):
        past = re.search(r'(?i)\b(?:cleansed|applied|removed|changed|irrigated|dressed|performed)\b', sentence)
        planned = re.search(r'(?i)\b(?:will|plan(?:ned)? to|continue|next visit|tomorrow|to be)\b', sentence)
        (completed if past and not planned else future).append(sentence)
    if not completed:return text
    groups.setdefault('Objective', []).extend(completed)
    groups['Plan'] = future
    return '\n\n'.join(key+'\n'+'\n'.join(groups[key]).strip() for key in ['Subjective','Objective','Assessment','Plan'] if key in groups and any(line.strip() for line in groups[key]))


def quality_defects(text, context=None):
    """Deterministic structure checks supplement semantic model QA; never create facts."""
    source = '\n'.join(str(s.get('text') or '') for s in (context or {}).get('untrusted_sources', []))
    defects = []
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    for index, line in enumerate(lines):
        plain = line.strip('#* ').rstrip(':').strip()
        if line.casefold() in UI_LINES:
            defects.append('Move the application review or empty-state label to UI state.')
        if plain.lower() in HEADINGS and (index + 1 == len(lines) or lines[index + 1].strip('#* :').lower() in HEADINGS):
            defects.append('Omit the empty document section.')
        if PLACEHOLDER.fullmatch(plain) or MISSING_COMMENT.fullmatch(line):
            defects.append('Omit placeholder or missing-information commentary; retain explicit clinical negatives and stated assessment limitations.')
        if re.search(r'(?i)recorded account|speaker attribution unverified|source limitations|transcript indicates|findings were obtained from|generated from audio transcript|it is important to note|furthermore|in conclusion', line) and line not in source:
            defects.append('Remove generic source commentary or formulaic filler; use natural clinical wording and targeted UI alerts.')
    return list(dict.fromkeys(defects))[:12]


def _sentences(text):
    return [part.strip() for part in re.split(r'\n+|(?<=[.!?])\s+(?=[A-Z])', clinical_text(text)) if part.strip()]


def _meaningful(text):
    return bool(text.strip()) and not PLACEHOLDER.fullmatch(text) and not MISSING_COMMENT.fullmatch(text) and not re.fullmatch(r'(?i)(?:hello|hi|thanks?|test|testing|synthetic (?:test|example)(?: only)?)[.! ]*', text)


def referral_supported(context):
    """Conservative local gate. Semantic QA additionally verifies the reason and intended request."""
    statements = [part for source in context.get('untrusted_sources', []) for part in _sentences(source.get('text') or '')]
    statements += [str(fact.get('value', {}).get('text', '')) for fact in context.get('case_facts', [])]
    return any(re.search(r'(?i)\b(?:refer(?:ral|red|ring)?|specialist (?:review|consultation) (?:requested|planned))\b', item)
        and re.search(r'(?i)\b(?:for|because|due to|reason[: ])\s+\S', item)
        and not re.search(r'(?i)\b(?:no|not|declin\w*|cancel\w*|without|denies)\b.{0,45}\breferr?\w*|\breferr?\w*.{0,35}\b(?:not needed|declined|cancelled)\b', item)
        for item in statements)


def supported_lines(context, phrase):
    excluded = {f.get('source_ref') for f in context.get('rejected_claims_do_not_reinstate', [])}
    excluded |= {f.get('source_ref') for f in context.get('case_facts', []) if f.get('source_locator', {}).get('revised_from') or f.get('action') == 'edit'}
    lines = [];represented = set()
    for source in context.get('untrusted_sources', []):
        if source.get('id') in excluded or source.get('kind') in {'photo', 'audio_verification'}:continue
        parts = [part for part in _sentences(source.get('text') or '') if _meaningful(part)]
        if parts: lines.extend(parts);represented.add(source.get('id'))
    for fact in context.get('case_facts', []):
        if fact.get('confirmation_state') == 'rejected' or fact.get('source_ref') in represented:continue
        line = phrase(fact)
        if _meaningful(line):lines.append(line)
    return list(dict.fromkeys(lines))


def format_source_lines(lines, template):
    template = ALIASES.get(template, template)
    groups = {}
    for line in lines:
        future = bool(re.search(r'(?i)\b(?:plan(?:ned|s)?|will|next visit|tomorrow|continue|refer(?:ral|red)?)\b', line))
        subjective = bool(re.search(r'(?i)\b(?:reports?|denies|recall|thinks|symptoms?|pain|soreness)\b', line))
        assessment = bool(re.search(r'(?i)\b(?:diagnos(?:is|ed)|impression|assessment:)\b', line))
        history = bool(re.search(r'(?i)\b(?:history|previous|past|started|present for)\b', line))
        completed = bool(re.search(r'(?i)\b(?:cleansed|applied|removed|changed|irrigated|dressed|performed)\b', line))
        if completed and not re.search(r'(?i)\b(?:will|tomorrow|next visit|plan to|continue)\b', line):future = False
        if template == 'soap':heading = 'Plan' if future else 'Assessment' if assessment else 'Subjective' if subjective else 'Objective'
        elif template == 'sbar':heading = 'Recommendation' if future else 'Background' if history else 'Assessment' if assessment or completed else 'Situation'
        elif template == 'handoff':heading = 'Next steps' if future else 'Treatment' if completed else 'Current problem'
        else:heading = ''
        groups.setdefault(heading, []).append(line)
    order = {'soap': ['Subjective', 'Objective', 'Assessment', 'Plan'], 'sbar': ['Situation', 'Background', 'Assessment', 'Recommendation'], 'handoff': ['Current problem', 'Treatment', 'Next steps']}.get(template, [''])
    return '\n\n'.join((heading + '\n' if heading else '') + ' '.join(groups[heading]) for heading in order if groups.get(heading))


def source_report(context, template, phrase):
    safe_context = {**context, 'untrusted_sources': [{'text': line} for line in supported_lines(context, phrase)], 'case_facts': []}
    if template == 'referral_letter' and not referral_supported(safe_context):
        return visible_result({'text': '', 'status': 'referral_not_supported'})
    lines = supported_lines(context, phrase)
    text = format_source_lines(lines, template)
    if len(text) > 60000:
        from fastapi import HTTPException
        raise HTTPException(413, 'This visit exceeds the document size limit.')
    return visible_result({'text': text, 'status': 'ready' if text else 'no_content'})
