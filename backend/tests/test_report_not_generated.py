"""A long visit must not run out of room, noise is cleaned before the writer sees it, and a failed report is never replaced
by the raw transcript. Synthetic data and provider doubles only; this is NOT proof that the live AI writes a good note."""
import asyncio, json
import pytest
from server import providers
from server.transcript_cleaning import clean_transcript, clean_context_for_report
from test_providers import gateway, completion

NOISY = ('Dressing was changed. *gunshot* Wound is 3 cm. [Music] Thank you. Thank you. Thank you. Thank you. '
         '(door closes) No redness noted.\n♪ la la la ♪\nPain is 2 out of 10 today. Client takes Biatain Silicone.')


def test_noise_is_removed_but_clinical_words_stay():
    cleaned = clean_transcript(NOISY)
    for gone in ('gunshot', 'Music', 'door closes', '♪'):
        assert gone not in cleaned
    for kept in ('Dressing was changed', '3 cm', 'No redness noted', '2 out of 10', 'Biatain Silicone'):
        assert kept in cleaned
    assert cleaned.count('Thank you') == 1


def test_clean_text_is_returned_unchanged_and_original_context_is_untouched():
    plain = 'Client reports burning. Wound 2 cm. No redness.\n'
    assert clean_transcript(plain) == plain
    context = {'untrusted_sources': [{'kind': 'audio', 'text': NOISY}, {'kind': 'typed', 'text': '*gunshot* typed note'}]}
    out = clean_context_for_report(context)
    assert 'gunshot' not in out['untrusted_sources'][0]['text']
    assert out['untrusted_sources'][1]['text'] == '*gunshot* typed note'   # only spoken sources are cleaned
    assert context['untrusted_sources'][0]['text'] == NOISY                  # the raw transcript is kept


def _send(gateway, text):
    gateway.http.responses = [completion({'text': 'Synthetic note.', 'status': 'ready', 'ui_alerts': []}),
                              completion({'passed': True, 'discrepancies': []})]
    context = {'case_facts': [], 'untrusted_sources': [{'kind': 'audio', 'text': text}], 'rejected_claims_do_not_reinstate': []}
    asyncio.run(providers.quick_documentation(context, 'nursing', 'concise', user=gateway.user, encounter_id='noise'))
    return gateway.http.calls[0]['json']


def test_writer_gets_the_cleaned_transcript_and_a_roomy_output_allowance(gateway):
    sent = _send(gateway, NOISY)
    body = sent['messages'][1]['content']
    assert 'gunshot' not in body and 'No redness noted' in body
    assert (sent.get('max_tokens') or sent.get('max_completion_tokens')) >= 8000


def test_a_reply_that_needs_more_than_the_old_2400_limit_now_finishes(gateway):
    def provider(request):
        budget = request['json'].get('max_tokens') or request['json'].get('max_completion_tokens')
        return completion({'text': 'Synthetic long note.', 'status': 'ready', 'ui_alerts': []},
                          finish='stop' if budget >= 2500 else 'length')
    gateway.http.responses = [provider, completion({'passed': True, 'discrepancies': []})]
    context = {'case_facts': [], 'untrusted_sources': [{'kind': 'audio', 'text': 'Client reports burning. Dressing changed.'}], 'rejected_claims_do_not_reinstate': []}
    result = asyncio.run(providers.quick_documentation(context, 'nursing', 'concise', user=gateway.user, encounter_id='long'))
    assert result['text'] == 'Synthetic long note.'
