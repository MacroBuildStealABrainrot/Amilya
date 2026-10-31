"""Auditable, bounded routing policy. This is task selection, not clinical triage."""
import json
import re

VERSION = 'kosmo-2026-10-25.1'
MODELS = {'low': 'openai/gpt-6-luna', 'medium': 'openai/gpt-6.1-sol', 'high': 'openai/gpt-6-astra'}


def select_route(task, content):
    try:
        data = json.loads(content) if isinstance(content, str) else {}
    except (ValueError, TypeError):
        data = {}
    if not isinstance(data, dict): data = {}
    facts = data.get('case_facts', data.get('confirmed_facts', data.get('approved_fact_revisions', [])))
    if not isinstance(facts, list): facts = []
    facts = [f for f in facts if isinstance(f, dict) and f.get('confirmation_state') != 'rejected']
    question = str(data.get('untrusted_question', ''))
    effort, reason = 'low', 'straightforward_task'
    # Synthesis/advice deserves more work than extraction or recalling a value.
    if task in {'draft', 'qa', 'review', 'holistic'}:
        effort, reason = 'medium', 'report_or_review'
    simple_recall = re.fullmatch(r'(?i)\s*(?:please\s+)?(?:what (?:is|was|are|were) (?:the |its )?(?:length|width|depth|size|measurements?|date)|(?:summarize|summarise|list|repeat) (?:the )?(?:documented |confirmed |recorded )?(?:findings|measurements|observations|facts))\s*[?.!]?\s*', question)
    if task == 'assistant' and not simple_recall:
        effort, reason = 'medium', 'clinical_question'
    if task == 'assistant' and re.search(r'(?i)\b(advice|recommend|report|plan|treat|dressing|compression|debrid|refer|offload|should|next step)\w*\b', question):
        effort, reason = 'medium', 'advice_requested'
    if len(facts) > 40 or len({f.get('wound_id') for f in facts if f.get('wound_id')}) > 2:
        effort, reason = 'high', 'complex_case'
    # Contradictions and active risk findings route conservatively, never declare
    # a diagnosis. Rejected/explicitly absent findings cannot trigger escalation.
    grouped = {}
    singleton = {'wound_measurement', 'wound_location', 'wound_stage', 'wound_etiology', 'pain'}
    for fact in facts:
        value = fact.get('value') or {}
        if fact.get('name') in singleton:
            grouped.setdefault((fact.get('wound_id'), fact['name']), set()).add(json.dumps(value, sort_keys=True))
        if fact.get('name') in {'infection_findings', 'compression', 'perfusion_assessment'} and isinstance(value, dict) and value.get('present') is not False:
            effort, reason = 'high', 'consequential_evidence'
    if any(len(values) > 1 for values in grouped.values()): effort, reason = 'high', 'source_conflict'
    # A wound photo needs a careful specialist-level look, not a quick glance.
    if task == 'image':
        effort, reason = 'high', 'wound_photo_specialist'
    if task in {'escalation', 'challenge', 'reconcile'} or data.get('mode') == 'deep':
        effort, reason = 'high', 'deep_review'
    if task == 'assistant' and re.search(r'(?i)\b(sepsis|ischemi\w*|ischaemi\w*|necrosis|conflict\w*|contradict\w*|urgent|emergency|vascular|compression)\b', question):
        effort, reason = 'high', 'consequential_question'
    # Only a failed source check escalates the bounded repair and its recheck.
    if data.get('repair_attempt') == 1 or data.get('review_phase') == 'after_one_repair':
        effort, reason = 'high', 'source_check_repair'
    return {'router': 'Kosmo', 'policy_version': VERSION, 'model': MODELS[effort],
            'effort_level': effort, 'reason': reason}
