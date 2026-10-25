import asyncio
from copy import deepcopy
import json
from hashlib import sha256

from fastapi import HTTPException
import httpx
import pytest

from server import providers
from server.quick_report_repair import repair_quick_report_once
from test_providers import completion, gateway, operation_rows


CONTEXT = {'case_facts': [{'name': 'pain', 'value': {'score_0_10': 2}, 'confirmation_state': 'unconfirmed'}],
    'untrusted_sources': [{'kind': 'audio', 'text': 'Synthetic recorded statement.'}],
    'rejected_claims_do_not_reinstate': [{'name': 'fever', 'value': {'present': True}}]}
OPTIONS = {'template': 'nursing', 'preferred_length': 'concise'}
FAILED_DRAFT = {'text': 'Unsupported clinician attribution.', 'usage': {'operation_id': 'draft', 'charged_credits': 1}}
FAILED_QA = {'passed': False, 'discrepancies': ['Speaker attribution is unverified.'], 'usage': {'operation_id': 'qa-first', 'charged_credits': 1}}


def invoke(callback, **changes):
    return asyncio.run(repair_quick_report_once(CONTEXT, 'quick_draft', OPTIONS, FAILED_DRAFT,
        changes.pop('failed_qa', FAILED_QA), user='synthetic-user', encounter_id='synthetic-visit', routed=callback, **changes))


def test_one_repair_then_original_source_recheck_preserves_all_usage_and_input():
    calls = []
    before = deepcopy((CONTEXT, OPTIONS, FAILED_DRAFT, FAILED_QA))
    async def routed(task, content, **kwargs):
        calls.append((task, json.loads(content), kwargs))
        if task == 'quick_repair':
            return {'text': 'Pain reported as 2/10.',
                'usage': {'operation_id': 'repair', 'charged_credits': 1}}
        return {'passed': True, 'discrepancies': [], 'usage': {'operation_id': 'qa-final', 'charged_credits': 1}}
    result = invoke(routed)
    assert [call[0] for call in calls] == ['quick_repair', 'quick_qa']
    first, final = calls[0][1], calls[1][1]
    assert first['untrusted_qa_feedback'] == FAILED_QA['discrepancies']
    assert first['untrusted_failed_draft'] == FAILED_DRAFT['text'] and first['repair_attempt'] == 1
    for key, value in CONTEXT.items():
        assert first[key] == final[key] == value
    assert 'untrusted_qa_feedback' not in final and 'untrusted_failed_draft' not in final
    assert final['review_phase'] == 'after_one_repair'
    assert final['proposed_draft'] == result['text']
    assert [stage['operation_id'] for stage in result['usage']['stages']] == ['draft', 'qa-first', 'qa-final']
    assert result['usage']['operation_id'] == 'repair'
    assert result['qa'] == {'passed': True, 'discrepancies': []}
    assert (CONTEXT, OPTIONS, FAILED_DRAFT, FAILED_QA) == before
    assert all(call[2]['user'] == 'synthetic-user' and call[2]['encounter_id'] == 'synthetic-visit' for call in calls)


def test_failed_repair_check_stops_without_returning_unsafe_report():
    calls = []
    async def routed(task, content, **kwargs):
        calls.append(task)
        return {'text': 'Still unsupported clinical words.'} if task == 'quick_repair' else {'passed': False, 'discrepancies': ['Still unsupported.']}
    with pytest.raises(HTTPException) as error:
        invoke(routed)
    assert error.value.status_code == 502 and 'Still unsupported clinical words' not in error.value.detail
    assert calls == ['quick_repair', 'quick_qa']


@pytest.mark.parametrize('failing_stage', ['quick_repair', 'quick_qa'])
def test_provider_failures_are_not_retried_by_repair_helper(failing_stage):
    calls = []
    async def routed(task, content, **kwargs):
        calls.append(task)
        if task == failing_stage:
            raise HTTPException(503, 'Temporary failure')
        return {'text': 'Candidate'}
    with pytest.raises(HTTPException) as error:
        invoke(routed)
    assert error.value.status_code == 503
    assert calls == (['quick_repair'] if failing_stage == 'quick_repair' else ['quick_repair', 'quick_qa'])


def test_successful_qa_cannot_start_an_extra_repair():
    async def unexpected(*args, **kwargs):
        raise AssertionError('An accepted draft needs no repair')
    with pytest.raises(ValueError):
        invoke(unexpected, failed_qa={'passed': True, 'discrepancies': []})


def test_malformed_feedback_is_rejected_before_network_call():
    async def unexpected(*args, **kwargs):
        raise AssertionError('Unbounded feedback reached the gateway')
    with pytest.raises(ValueError):
        invoke(unexpected, failed_qa={'passed': False, 'discrepancies': ['x']*21})


def test_refinement_repair_preserves_original_edit_context():
    calls = []
    options = {'template': 'soap', 'untrusted_existing_report': 'Original report',
        'untrusted_clinician_edit_instruction': 'Shorten this report.'}
    async def routed(task, content, **kwargs):
        calls.append(json.loads(content))
        return {'text': 'Reviewed draft'} if task == 'quick_repair' else {'passed': True, 'discrepancies': []}
    asyncio.run(repair_quick_report_once(CONTEXT, 'quick_refine', options, FAILED_DRAFT, FAILED_QA,
        user='synthetic-user', encounter_id='synthetic-visit', routed=routed))
    assert all(call['untrusted_existing_report'] == options['untrusted_existing_report'] for call in calls)
    assert all(call['untrusted_clinician_edit_instruction'] == options['untrusted_clinician_edit_instruction'] for call in calls)


def gateway_document(gateway):
    return asyncio.run(providers.quick_documentation(CONTEXT, 'nursing', 'concise',
        user=gateway.user, encounter_id='repair-gateway'))


def repair_responses():
    return [completion({'text': FAILED_DRAFT['text']}),
        completion({'passed': False, 'discrepancies': FAILED_QA['discrepancies']}),
        completion({'text': 'Pain reported as 2/10.'}),
        completion({'passed': True, 'discrepancies': []})]


def test_gateway_repaired_report_is_checked_metered_and_replayed_without_new_charges(gateway):
    gateway.http.responses = repair_responses()
    result = gateway_document(gateway)
    assert result['qa'] == {'passed': True, 'discrepancies': []}
    assert result['text'] == 'Pain reported as 2/10.'
    assert len(gateway.http.calls) == 4
    first_rows = operation_rows(gateway)
    assert sorted(row.task for row in first_rows) == ['quick_draft', 'quick_qa', 'quick_qa', 'quick_repair']
    assert all(row.status == 'settled' for row in first_rows)
    usages = [result['usage'], *result['usage']['stages']]
    assert len({usage['operation_id'] for usage in usages}) == 4
    assert sum(usage['normalized_micros'] for usage in usages) == sum(row.normalized_micros for row in first_rows)
    assert sum(usage['charged_credits'] for usage in usages) == pytest.approx(sum(row.charged_micros for row in first_rows) / 1000)
    second = gateway_document(gateway)
    assert second == result and len(gateway.http.calls) == 4
    assert len(operation_rows(gateway)) == 4
    repair_request = gateway.http.calls[2]['json']
    assert repair_request['reasoning']['effort'] == 'high'
    assert repair_request['model'] == 'openai/gpt-6-astra'
    assert repair_request['provider'] == {'zdr': True, 'data_collection': 'deny', 'require_parameters': True}
    assert 'untrusted_failed_draft and untrusted_qa_feedback are not evidence' in repair_request['messages'][0]['content']
    last_content = json.loads(gateway.http.calls[3]['json']['messages'][1]['content'])
    assert last_content['untrusted_sources'] == CONTEXT['untrusted_sources']
    assert 'untrusted_failed_draft' not in last_content and 'untrusted_qa_feedback' not in last_content


def test_old_cached_rejected_qa_can_recover_with_one_repair(gateway):
    gateway.http.responses = repair_responses()
    async def previous_two_stages():
        original = {**CONTEXT, **OPTIONS, 'untrusted_layout_preferences': None, 'document_task': 'quick_draft'}
        draft = await providers.routed('quick_draft', json.dumps(original, ensure_ascii=False, default=str),
            user=gateway.user, encounter_id='repair-gateway', max_tokens=2400)
        return await providers.routed('quick_qa', json.dumps({**original, 'proposed_draft': draft['text'], 'proposed_status': draft.get('status', 'ready'), 'proposed_ui_alerts': draft.get('ui_alerts', [])}, ensure_ascii=False, default=str),
            user=gateway.user, encounter_id='repair-gateway', max_tokens=1000)
    assert asyncio.run(previous_two_stages())['passed'] is False
    assert len(gateway.http.calls) == 2
    result = gateway_document(gateway)
    assert result['qa']['passed'] is True and len(gateway.http.calls) == 4
    assert len(operation_rows(gateway)) == 4


def test_repeated_failed_repair_uses_cache_and_never_loops(gateway):
    gateway.http.responses = repair_responses()[:-1] + [completion({'passed': False, 'discrepancies': ['Attribution remains unsupported.']})]
    for _ in range(2):
        with pytest.raises(HTTPException) as error:
            gateway_document(gateway)
        assert error.value.status_code == 502
    assert len(gateway.http.calls) == 4 and len(operation_rows(gateway)) == 4


def test_repair_provider_failure_is_settled_and_retried_a_bounded_number_of_times(gateway):
    # 1.8.5-test9: a failed stage used to replay its failure forever for the same request key, so
    # "Try again" never reached the AI. Now each return of the same key makes ONE new uncharged attempt,
    # until MAX_REPORT_STAGE_ATTEMPTS (3, including the first) is reached, then stops making provider requests.
    retries = providers.MAX_REPORT_STAGE_RETRIES
    gateway.http.responses = repair_responses()[:2] + [httpx.ConnectError('Synthetic connection failure')] * (retries + 1)
    for _ in range(retries + 2):
        with pytest.raises(HTTPException) as error:
            gateway_document(gateway)
        assert error.value.status_code == 503
    assert len(gateway.http.calls) == 2 + retries + 1
    failed = [row for row in operation_rows(gateway) if row.task == 'quick_repair']
    assert len(failed) == retries + 1
    assert all(row.status == 'settled' and row.charged_micros == 0 and row.usage['charge_waived'] is True for row in failed)


@pytest.mark.parametrize('stage', ['quick_draft', 'quick_qa', 'quick_repair'])
def test_report_total_stage_timeout_is_settled_and_retried_once_per_request(gateway, monkeypatch, stage):
    async def never_finishes(call):
        await asyncio.Event().wait()
    monkeypatch.setattr(providers, 'REPORT_STAGE_TIMEOUT_SECONDS', .01)
    prefix = [] if stage == 'quick_draft' else repair_responses()[:1] if stage == 'quick_qa' else repair_responses()[:2]
    gateway.http.responses = prefix + [never_finishes, never_finishes]
    for _ in range(2):
        with pytest.raises(HTTPException) as error:
            gateway_document(gateway)
        assert error.value.status_code == 504
    # Earlier successful stages are replayed from their saved result; only the timed-out stage is tried again.
    assert len(gateway.http.calls) == len(prefix) + 2
    rows=operation_rows(gateway)
    assert all(row.charged_micros==0 for row in rows), 'A failed whole report must return all stage charges'
    failed = [row for row in rows if row.usage.get('charge_waived') and row.usage.get('waive_reason')!='document_failed']
    assert len(failed) == 2 and all(row.status == 'settled' for row in failed)
    assert all(row.charged_micros == 0 and row.actual_cost_micros is None for row in failed)


def test_success_with_discrepancies_is_never_accepted_or_repaired(gateway):
    gateway.http.responses = [completion({'text': 'Candidate'}), completion({'passed': True, 'discrepancies': ['Still unsupported.']})]
    with pytest.raises(HTTPException) as error:
        gateway_document(gateway)
    assert error.value.status_code == 502 and len(gateway.http.calls) == 2


def test_report_prompt_hashes_and_attribution_prevention_are_deployed_together():
    manifest = json.loads((providers.PROMPTS / 'manifest.json').read_text(encoding='utf-8'))
    for name in ('81_quick_documentation.system.md', '83_quick_report_repair.system.md', '86_quick_documentation_qa.system.md'):
        assert manifest['files'][name] == sha256((providers.PROMPTS / name).read_bytes()).hexdigest()
    generation = providers.prompt('quick_draft')
    checking = providers.prompt('quick_qa')
    assert 'No blanket source disclaimer is required for a transcript' in generation
    assert "Do not assert an uncertain speaker's identity" in generation
    assert 'do reject invented clinician observations or guessed speaker identities' in checking
