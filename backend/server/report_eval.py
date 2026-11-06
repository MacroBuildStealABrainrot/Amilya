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

# The permanent visits live in eval_cases.py (every detail invented).
from .eval_cases import CASES as SCENARIOS
MAX_CASES = 40


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
    problems, style = [], []
    if missing: problems.append('missing')
    if invented: problems.append('unsupported_or_wrong')
    if scenario.get('clarify') and not clarify: problems.append('no_clarify_alert')
    if scenario.get('max_words') and words > scenario['max_words']: style.append(f'longer than {scenario["max_words"]} words ({words})')
    return {'passed': not problems and bool(text.strip()), 'problems': problems, 'style': style, 'missing': missing, 'invented': invented, 'words': words,
            'clarify_alerts': [a.get('message') for a in clarify],
            'coverage_after': coverage_defects(text, {'untrusted_sources': [{'text': scenario['text'] + ' ' + scenario.get('note', '')}],
                                                       'case_facts': scenario.get('facts', [])})}


PAUSE_SECONDS = 6


async def _paced(call):
    """One AI step at a time, with a pause, and patient retries when the AI service says 'too many requests'."""
    from fastapi import HTTPException
    for attempt in range(4):
        await asyncio.sleep(PAUSE_SECONDS)
        try:
            return await call()
        except HTTPException as error:
            if error.status_code not in {429, 503} or attempt == 3:
                raise
            await asyncio.sleep(30 * (attempt + 1))


async def mark(scenario, report, user, encounter_id, alerts=None):
    """Independent AI marker (Claude Haiku via the normal route). Accuracy problems are kept apart from style notes."""
    from . import providers
    payload = {'sources': {'clinician_said': scenario.get('text', ''), 'clinician_note_with_photo': scenario.get('note', ''),
                           'photo_findings': [f['value']['text'] for f in scenario.get('facts', [])]},
               'profession': scenario['profession'], 'report_format': scenario['template'],
               'expected_key_facts': scenario.get('expected', []), 'candidate_report': report,
               'alerts_shown_outside_the_note': [a.get('message') for a in (alerts or []) if isinstance(a, dict)]}
    try:
        verdict = await _paced(lambda: providers.routed('eval_marker', json.dumps(payload, ensure_ascii=False), user=user,
                                                       encounter_id=encounter_id, max_tokens=1200))
    except Exception as error:
        return {'error': type(error).__name__, 'accuracy': [], 'style': []}
    items = [str(item) for item in verdict.get('discrepancies') or []]
    accuracy = [item for item in items if item.upper().startswith('ACCURACY')]
    if verdict.get('passed') is False and not accuracy:
        accuracy = ['ACCURACY: marker failed the report without a reason']
    return {'accuracy': accuracy, 'style': [item for item in items if item.upper().startswith('STYLE')]}


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
        container = db.get(Patient, patient_id)
        if container is None:
            container = Patient(id=patient_id, workspace_id=user.workspace_id, display_name='Made-up evaluation container', external_ref=None)
            db.add(container); db.commit()
        # Once per label: the free test server restarts often, and a restart must never repeat (and pay for) a run.
        if container.external_ref == f'eval-done:{label}'[:120]:
            logger.warning('AMILYA_REPORT_EVAL %s', json.dumps({'label': label, 'skipped': 'already run for this label'}))
            return
        container.external_ref = f'eval-done:{label}'[:120]; db.commit()
        summary, planted_results = [], []
        for scenario in SCENARIOS[:MAX_CASES]:
            from .db import uid
            encounter = Encounter(id=uid(), workspace_id=user.workspace_id, patient_id=patient_id, wound_id=None,
                title='Made-up evaluation visit', status='open', revision=1, created_by=user.id,
                draft_data={'retention': 'temporary', 'quick_mode': 'document', 'evaluation': label})
            db.add(encounter); db.commit()
            sources = [{'id': 'eval-source', 'kind': 'audio', 'text': scenario['text'], 'warnings': [],
                        'created_at': utcnow().isoformat()}] if scenario['text'] else []
            context = {'photo_groups_clinician_assigned': [], 'case_facts': scenario.get('facts', []), 'rejected_claims_do_not_reinstate': [],
                       'untrusted_sources': sources}
            if scenario.get('note'):
                # Exactly what the phone sends from the photo screen: the clinician's own words as one clinician message.
                from .quick_workflows import consultation_context
                context['consultation'] = consultation_context([SimpleNamespace(role='user', content=scenario['note'])])
            record = {'label': label, 'scenario': scenario['id'], 'specialty': scenario['area'],
                      'profession': scenario['profession'], 'template': scenario['template']}
            try:
                result = await _paced(lambda: providers.quick_documentation(context, scenario['template'], 'concise',
                    user=_ProfessionUser(user, scenario['profession']), encounter_id=encounter.id))
                result = documentation.visible_result(result)
                record.update(_check(scenario, result))
                record.update({'qa': result.get('qa'), 'text': result.get('text'), 'alerts': result.get('ui_alerts')})
                marked = await mark(scenario, result.get('text') or '', user, encounter.id, result.get('ui_alerts'))
                record['marker'] = marked
                if marked.get('accuracy'):
                    record['passed'] = False
                    record['problems'] = record.get('problems', []) + ['marker_accuracy']
                record['style'] = record.get('style', []) + marked.get('style', [])
                # Proof that the checks catch deliberately wrong reports for this case.
                for planted in scenario.get('planted', []):
                    by_patterns = not _check(scenario, {'text': planted['text'], 'ui_alerts': []})['passed']
                    by_marker = bool((await mark(scenario, planted['text'], user, encounter.id)).get('accuracy'))
                    planted_results.append({'scenario': scenario['id'], 'mistake': planted['mistake'],
                                            'caught_by_patterns': by_patterns, 'caught_by_marker': by_marker})
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
        for item in planted_results:
            logger.warning('AMILYA_REPORT_EVAL %s', json.dumps({'label': label, 'planted': item}, ensure_ascii=False))
        logger.warning('AMILYA_REPORT_EVAL %s', json.dumps({'label': label, 'summary': summary,
            'passed': sum(1 for _, ok in summary if ok), 'total': len(summary),
            'accuracy_failures': [case for case, ok in summary if not ok],
            'planted_caught_by_marker': sum(1 for p in planted_results if p['caught_by_marker']),
            'planted_caught_by_patterns': sum(1 for p in planted_results if p['caught_by_patterns']),
            'planted_caught_by_either': sum(1 for p in planted_results if p['caught_by_marker'] or p['caught_by_patterns']),
            'planted_total': len(planted_results)}))


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
