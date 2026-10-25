"""1.8.5-test10: 3-second overlap between audio pieces, and long visits recorded in several parts -> one report.

Clinical safety rules tested here: doses, numbers, negation and genuinely repeated phrases are never lost; the
second copy of the overlapping words is removed only on an exact word-for-word match inside the overlap window;
otherwise both copies are kept and the join is flagged. The full unmerged transcript is always kept.
"""
from io import BytesIO
from uuid import uuid4
import wave

from server import audio_parts, providers, quick_workflows
from server.audio_overlap import merge_pair, merge_parts
from test_clinical import clinical_api  # noqa: F401
from test_quick_workflows import quick_api, start  # noqa: F401


# ---------- the join rule itself ----------

def test_a_dose_cut_at_the_boundary_is_kept_once_and_whole():
    rest, removed, status = merge_pair('Plan: give metformin 500', 'metformin 500 mg twice daily with meals.', 3)
    assert status == 'merged' and removed == 'metformin 500'
    assert 'Plan: give metformin 500 ' + rest == 'Plan: give metformin 500 mg twice daily with meals.'


def test_negation_is_never_lost():
    rest, removed, status = merge_pair('The client denies', 'denies chest pain or fever.', 3)
    assert (status, rest) == ('merged', 'chest pain or fever.')
    # The speech service heard the overlap differently: keep BOTH, never drop "denies".
    rest, removed, status = merge_pair('The client denies', 'the nice chest pain or fever.', 3)
    assert status == 'unmatched' and rest == 'the nice chest pain or fever.' and removed == ''


def test_numbers_that_differ_between_copies_are_both_kept():
    rest, _, status = merge_pair('Wound measures 2.5 by', '2.0 by 3 cm today.', 3)
    assert status == 'unmatched' and rest == '2.0 by 3 cm today.'
    rest, _, status = merge_pair('Wound measures 2.5 by', '2.5 by 3 cm today.', 3)
    assert status == 'merged' and rest == '3 cm today.'


def test_a_genuinely_repeated_phrase_keeps_the_spoken_count():
    # Spoken: "he said no no no and then left". The overlap holds the middle "no no".
    previous, current = 'He said no no', 'no no no and then left.'
    rest, removed, status = merge_pair(previous, current, 3)
    assert status == 'merged' and removed == 'no no'
    assert (previous + ' ' + rest).split().count('no') == 3


def test_words_outside_the_overlap_window_are_never_removed():
    segments = [{'text': ' Wound cleansed.', 'start': 9.0, 'end': 10.5}]
    rest, _, status = merge_pair('Wound cleansed.', 'Wound cleansed.', 3, segments)
    assert status == 'unmatched' and rest == 'Wound cleansed.'


def test_no_overlap_means_the_old_behaviour_exactly():
    text, notes, joins = merge_parts([{'text': 'Right heel. No fever.', 'seconds': 120}, {'text': 'Dose 5 mg tomorrow.', 'seconds': 120}])
    assert text == 'Right heel. No fever.\nDose 5 mg tomorrow.' and notes == [] and joins == []


def test_every_join_is_reported_in_plain_words():
    text, notes, joins = merge_parts([
        {'text': 'Apply Mepilex and give 5', 'seconds': 120},
        {'text': 'give 5 mg morphine now. Pain 7 out of', 'seconds': 123, 'overlap_seconds': 3},
        {'text': 'eight out of 10 later.', 'seconds': 60, 'overlap_seconds': 3},
    ])
    assert text == 'Apply Mepilex and give 5\nmg morphine now. Pain 7 out of\neight out of 10 later.'
    assert [j['status'] for j in joins] == ['merged', 'unmatched']
    assert joins[0]['at_seconds'] == 120 and joins[1]['at_seconds'] == 240
    assert '"give 5"' in notes[0] and 'both were kept' in notes[1] and '4:00' in notes[1]


# ---------- through the server ----------

def audio():
    stream = BytesIO()
    with wave.open(stream, 'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000); wav.writeframes(b'\0\0' * 1600)
    return stream.getvalue()


def _setup(api, monkeypatch, texts, seconds=None):
    api.client.app.include_router(audio_parts.router, prefix='/api/v1')
    calls = []
    durations = list(seconds or [120.0] * len(texts))
    monkeypatch.setattr(providers, 'audio_duration', lambda _: durations[min(len(calls), len(durations) - 1)])

    async def transcribe(*args, **kwargs):
        text = texts[len(calls)]
        calls.append(text)
        return text if isinstance(text, dict) else {'text': text, 'segments': [], 'warnings': []}
    monkeypatch.setattr(providers, 'transcribe', transcribe)
    return calls


def _part(api, encounter, job, index, count, overlap_ms=0, body=b''):
    data = {'job_id': job, 'index': index, 'count': count, 'consent': 'true'}
    if overlap_ms:
        data['overlap_ms'] = overlap_ms
    return api.client.post(f'/api/v1/encounters/{encounter}/audio-parts', files={'file': ('s.wav', audio() + body, 'audio/wav')}, data=data)


def test_overlapping_pieces_are_joined_safely_and_the_original_is_kept(quick_api, monkeypatch):
    api = quick_api
    _setup(api, monkeypatch, ['Give 5', 'Give 5 mg now.'], [120.0, 123.0])
    encounter = start(api)['encounter']['id']; job = str(uuid4())
    assert _part(api, encounter, job, 0, 2).status_code == 200
    assert _part(api, encounter, job, 1, 2, overlap_ms=2986).status_code == 200
    result = api.client.post(f'/api/v1/encounters/{encounter}/audio-complete', json={'job_id': job, 'count': 2})
    assert result.status_code == 200, result.text
    evidence = result.json()['evidence']
    assert evidence['text'] == 'Give 5\nmg now.'
    assert evidence['metadata']['original_transcript'] == 'Give 5\nGive 5 mg now.'
    assert evidence['metadata']['overlap_joins'][0]['removed'] == 'Give 5'
    assert abs(evidence['metadata']['duration_seconds'] - 240.014) < 0.01
    assert any('shown once' in warning for warning in evidence['warnings'])


def test_the_first_piece_cannot_claim_an_overlap(quick_api, monkeypatch):
    api = quick_api
    _setup(api, monkeypatch, ['x'])
    encounter = start(api)['encounter']['id']
    assert _part(api, encounter, str(uuid4()), 0, 1, overlap_ms=3000).status_code == 422
    assert _part(api, encounter, str(uuid4()), 1, 2, overlap_ms=9000).status_code == 422


def test_shortened_speech_loops_and_their_warnings_survive_assembly(quick_api, monkeypatch):
    api = quick_api
    _setup(api, monkeypatch, [{'text': 'Thank you.', 'segments': [], 'original_text': 'Thank you. Thank you. Thank you.',
                               'warnings': ['The speech service repeated the same short phrase several times in a row; ...']}])
    encounter = start(api)['encounter']['id']; job = str(uuid4())
    assert _part(api, encounter, job, 0, 1).status_code == 200
    evidence = api.client.post(f'/api/v1/encounters/{encounter}/audio-complete', json={'job_id': job, 'count': 1}).json()['evidence']
    assert evidence['text'] == 'Thank you.'
    assert evidence['metadata']['original_transcript'] == 'Thank you. Thank you. Thank you.'
    assert any('repeated the same short phrase' in warning for warning in evidence['warnings'])


def test_a_ninety_minute_visit_in_two_parts_gives_one_report_with_all_the_audio(quick_api, monkeypatch):
    api = quick_api
    _setup(api, monkeypatch, ['Part one: right heel wound cleansed with NS.', 'Part two: client denies pain. Next visit Tuesday.'])
    encounter = start(api)['encounter']['id']; visit = 'a' * 32
    for number, job in ((1, str(uuid4())), (2, str(uuid4()))):
        assert _part(api, encounter, job, 0, 1, body=bytes([number])).status_code == 200
        response = api.client.post(f'/api/v1/encounters/{encounter}/audio-complete',
                                   json={'job_id': job, 'count': 1, 'visit_part': number, 'visit_parts': 2, 'visit_id': visit})
        assert response.status_code == 200, response.text
        assert response.json()['evidence']['metadata']['visit_part'] == number
    with api.sessions() as db:
        from server.clinical_models import Encounter
        context = quick_workflows.source_context(db, db.get(Encounter, encounter))
    audio_sources = [s for s in context['untrusted_sources'] if s['kind'] == 'audio']
    assert [s['recording_part'] for s in audio_sources] == ['Part 1 of 2 of the same visit recording', 'Part 2 of 2 of the same visit recording']
    report = api.client.post(f'/api/v1/encounters/{encounter}/quick-report', json={'template': 'nursing', 'use_ai': False})
    assert report.status_code == 200, report.text
    text = report.json()['text']
    assert 'right heel wound cleansed' in text and 'denies pain' in text, 'one report contains both parts'
    assert len(report.json()['source_ids']) == 2


def test_visit_fields_must_come_together(quick_api, monkeypatch):
    api = quick_api
    _setup(api, monkeypatch, ['x'])
    encounter = start(api)['encounter']['id']; job = str(uuid4())
    assert _part(api, encounter, job, 0, 1).status_code == 200
    bad = api.client.post(f'/api/v1/encounters/{encounter}/audio-complete', json={'job_id': job, 'count': 1, 'visit_part': 1, 'visit_parts': 2})
    assert bad.status_code == 422
    bad = api.client.post(f'/api/v1/encounters/{encounter}/audio-complete', json={'job_id': job, 'count': 1, 'visit_part': 3, 'visit_parts': 2, 'visit_id': 'b' * 32})
    assert bad.status_code == 422


def test_parts_uploaded_out_of_order_are_still_given_to_the_report_in_order():
    rows = {}
    class Row:
        def __init__(self, meta): self.metadata_json = meta
    sources = [{'id': 'p2', 'kind': 'audio'}, {'id': 'typed', 'kind': 'typed'}, {'id': 'p1', 'kind': 'audio'}]
    rows = {'p2': Row({'visit_id': 'v', 'visit_part': 2, 'visit_parts': 2}), 'typed': Row({}), 'p1': Row({'visit_id': 'v', 'visit_part': 1, 'visit_parts': 2})}
    assert [s['id'] for s in quick_workflows._order_visit_parts(sources, rows)] == ['p1', 'p2', 'typed']
