"""A long whole-visit recording must still become a note: the check has room to finish, an empty note that the check
rejects is written afresh once, and a writer that ran out of room is asked once more. Made-up text and provider doubles only
(owner request 2026-11-13); this is NOT proof that the live AI writes a good note."""
import asyncio
import pytest
from fastapi import HTTPException
from server import providers
from test_providers import gateway, completion

CONTEXT = {'case_facts': [], 'rejected_claims_do_not_reinstate': [], 'untrusted_sources': [
    {'kind': 'audio', 'text': 'Come on in. Thank you. Wound on the coccyx area draining a large amount. Client denies pain.'}]}
EMPTY = {'text': '', 'status': 'no_content', 'ui_alerts': [{'field': 'recording_quality', 'message': 'Recording quality: much of this recording was unclear.'}]}
NOTE = {'text': 'Coccyx wound, large drainage. Client denies pain.', 'status': 'ready', 'ui_alerts': []}
PASS = {'passed': True, 'discrepancies': []}
FAIL = {'passed': False, 'discrepancies': ['no_content is not permitted: the source holds clear clinical statements',
                                           'Lost: coccyx wound', 'Lost: large drainage', 'Lost: client denies pain']}


def report(gateway, responses, name):
    gateway.http.responses = [r if 'choices' in r else completion(r) for r in responses]
    return asyncio.run(providers.quick_documentation(dict(CONTEXT), 'nursing', 'concise', user=gateway.user, encounter_id=name))


def budget(call):
    return call['json'].get('max_tokens') or call['json'].get('max_completion_tokens')


def test_the_check_has_room_to_finish_on_a_long_visit(gateway):
    report(gateway, [NOTE, PASS], 'room')
    assert budget(gateway.http.calls[1]) >= 5000


def test_an_empty_note_the_check_rejects_is_written_afresh_once(gateway):
    result = report(gateway, [EMPTY, FAIL, NOTE, PASS], 'fresh')
    assert result['text'] == NOTE['text'] and result['qa']['passed'] is True
    assert len(gateway.http.calls) == 4
    first, second = gateway.http.calls[0]['json']['messages'][1]['content'], gateway.http.calls[2]['json']['messages'][1]['content']
    assert 'previous_attempt_returned_no_note' not in first and '"previous_attempt_returned_no_note": true' in second
    # The check of the fresh note sees the sources and the note, never the hint.
    assert 'previous_attempt_returned_no_note' not in gateway.http.calls[3]['json']['messages'][1]['content']


def test_only_one_fresh_attempt_then_the_usual_single_repair(gateway):
    with pytest.raises(HTTPException) as failure:
        # The second check of the same empty note is answered from the saved first check (no new request, no new cost).
        report(gateway, [EMPTY, FAIL, EMPTY, EMPTY, FAIL, EMPTY, FAIL], 'still-empty')
    assert failure.value.status_code == 502
    # write, check, fresh write, (saved check), one repair, check: never more than six requests
    assert 4 <= len(gateway.http.calls) <= 6
    assert sum('"previous_attempt_returned_no_note": true' in c['json']['messages'][1]['content'] for c in gateway.http.calls) == 1


def test_a_recording_with_nothing_clinical_still_gives_no_note_without_extra_requests(gateway):
    result = report(gateway, [EMPTY, PASS], 'noise-only')
    assert result['text'] == '' and len(gateway.http.calls) == 2


def test_a_writer_that_ran_out_of_room_is_asked_once_more(gateway):
    result = report(gateway, [completion(NOTE, finish='length'), NOTE, PASS], 'length-once')
    assert result['text'] == NOTE['text'] and len(gateway.http.calls) == 3
    assert '"retry_after_incomplete_output": true' in gateway.http.calls[1]['json']['messages'][1]['content']


def test_running_out_of_room_twice_ends_the_report_and_nothing_partial_is_used(gateway):
    with pytest.raises(HTTPException) as failure:
        report(gateway, [completion(NOTE, finish='length'), completion(NOTE, finish='length')], 'length-twice')
    assert failure.value.status_code == 502 and (failure.value.headers or {}).get('X-WCP-Error') == 'OUTPUT_LIMIT'
    assert len(gateway.http.calls) == 2


LONG = {'case_facts': [], 'rejected_claims_do_not_reinstate': [], 'untrusted_sources': [
    {'kind': 'audio', 'text': 'Thank you. How was he overnight? He was up twice. ' * 60}]}


def test_long_visit_recordings_can_have_their_own_writer_and_nothing_else_changes(gateway):
    import json
    gateway.config.ai_model_all = 'minimax/minimax-m3'
    long_text, short_text = json.dumps(LONG), json.dumps(CONTEXT)
    assert providers.long_visit_recording(long_text) and not providers.long_visit_recording(short_text)
    summary = json.dumps({'untrusted_sources': [{**LONG['untrusted_sources'][0], 'source_role': "Clinician's own spoken summary"}]})
    assert not providers.long_visit_recording(summary) and not providers.long_visit_recording('not json')
    # Switch off (the default): every step stays on the one chosen model.
    assert providers.configured_route('quick_draft', long_text)['model'] == 'minimax/minimax-m3'
    gateway.config.ai_model_long_visit = 'anthropic/claude-haiku-5.5'
    assert providers.configured_route('quick_draft', long_text)['model'] == 'anthropic/claude-haiku-5.5'
    assert providers.configured_route('quick_repair', long_text)['model'] == 'anthropic/claude-haiku-5.5'
    assert providers.configured_route('quick_draft', short_text)['model'] == 'minimax/minimax-m3'
    assert providers.configured_route('consult', long_text)['model'] == 'minimax/minimax-m3'
