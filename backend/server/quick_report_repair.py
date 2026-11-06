"""At most one source-constrained report repair, followed by an independent recheck.

The injected routed callback keeps provider privacy, accounting and replay in the
existing gateway. This module never persists report text or changes source facts.
"""
from __future__ import annotations

from collections.abc import Awaitable, Callable
import json
from typing import Any

from .report_errors import TerminalReportError

REVIEW_FAILURE = 'The report did not pass source-faithfulness review. Your original source and editor text remain available.'


async def repair_quick_report_once(context: dict, task: str, options: dict,
    initial_draft: dict, failed_qa: dict, *, user: Any, encounter_id: str,
    routed: Callable[..., Awaitable[dict]]) -> dict:
    """Called only after a completed QA rejection; never retry a failed provider call."""
    if task not in {'quick_draft', 'quick_refine', 'improve_report'} or failed_qa.get('passed') is not False:
        raise ValueError('Repair requires a failed quick-report source check')
    feedback = failed_qa.get('discrepancies')
    if not isinstance(feedback, list) or len(feedback) > 20 or any(not isinstance(item, str) or len(item) > 2000 for item in feedback):
        raise ValueError('Invalid bounded QA feedback')
    original = {**context, **options}
    repair_input = {**original, 'original_document_task': task,
        'untrusted_failed_draft': initial_draft['text'], 'untrusted_qa_feedback': feedback,
        'repair_attempt': 1}
    repaired = await routed('quick_repair', json.dumps(repair_input, ensure_ascii=False, default=str),
        user=user, encounter_id=encounter_id, max_tokens=8000 if len(json.dumps(context,ensure_ascii=False))>20000 else 2400)
    from .documentation import clean_generated_layout
    repaired = clean_generated_layout(repaired, options, context)
    # The recheck receives the original source context, not QA feedback or the
    # failed draft. Phase metadata distinguishes this bounded check for replay.
    check_input = {**original, 'proposed_draft': repaired['text'], 'proposed_status': repaired.get('status', 'ready'), 'proposed_ui_alerts': repaired.get('ui_alerts', []), 'review_phase': 'after_one_repair'}
    checked = await routed('quick_qa', json.dumps(check_input, ensure_ascii=False, default=str),
        user=user, encounter_id=encounter_id, max_tokens=1000)
    from .documentation import quality_defects
    from . import diagnostics
    diagnostics.faithfulness(checked.get('passed') is True and checked.get('discrepancies')==[] and not quality_defects(repaired['text'],context),len(checked.get('discrepancies',[])),'after_repair')
    if checked.get('passed') is not True or checked.get('discrepancies') != [] or quality_defects(repaired['text'], context):
        failure = TerminalReportError(502, REVIEW_FAILURE)
        # Kept on the error object only (never sent to the phone): lets the made-up-visit evaluation explain a rejection.
        failure.check_discrepancies = [str(item)[:300] for item in (checked.get('discrepancies') or [])][:10]
        raise failure
    # The repaired generation is the top-level usage entry. Include the initial
    # draft and both checks so all four stages remain visible to cost accounting.
    repaired.setdefault('usage', {})['stages'] = [
        initial_draft.get('usage', {}), failed_qa.get('usage', {}), checked.get('usage', {})]
    repaired['qa'] = {'passed': True, 'discrepancies': []}
    # The free source comparison is a heuristic: after the AI recheck passes, anything it still sees becomes at most
    # three short "please check" notes for the clinician. It never blocks or discards a checked report.
    from .source_coverage import coverage_defects, strong_findings, clarify_alerts
    repaired['ui_alerts'] = list(repaired.get('ui_alerts') or []) + clarify_alerts(strong_findings(coverage_defects(repaired['text'], context)))
    return repaired
