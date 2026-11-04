"""Synthetic 60-minute recording (30 pieces of 2 minutes) with provider failures at the beginning, middle and end.
Fake data, provider double, no network. Checks: saved pieces are reused, nothing is sent twice, the transcript is complete and in order."""
from uuid import uuid4
from fastapi import HTTPException
from server import audio_parts, providers
from test_clinical import clinical_api
from test_quick_workflows import quick_api, start
from test_audio_parts import audio

PIECES = 30
SENTENCES = [f'Synthetic section {i + 1}. Dose {i + 1} mg, no fever.' for i in range(PIECES)]


def run_job(quick_api, monkeypatch, fail_at):
    api = quick_api
    api.client.app.include_router(audio_parts.router, prefix='/api/v1')
    case_id = start(api)['encounter']['id']
    job = str(uuid4())
    sent = []          # every piece that reached the speech service
    remaining_failures = dict.fromkeys(fail_at, 1)
    monkeypatch.setattr(providers, 'audio_duration', lambda _: 120.0)
    index_of = {}

    async def transcribe(data, *args, **kwargs):
        index = index_of['now']
        sent.append(index)
        if remaining_failures.get(index):
            remaining_failures[index] -= 1
            raise HTTPException(502, 'Synthetic provider failure', headers={'X-WCP-Error': 'INVALID_RESPONSE'})
        return {'text': SENTENCES[index], 'segments': [{'text': SENTENCES[index], 'start': 0, 'end': 119}], 'warnings': []}
    monkeypatch.setattr(providers, 'transcribe', transcribe)

    def part(index):
        index_of['now'] = index
        return api.client.post(f'/api/v1/encounters/{case_id}/audio-parts', files={'file': ('s.wav', audio(), 'audio/wav')},
            data={'job_id': job, 'index': index, 'count': PIECES, 'consent': 'true'})

    # The phone sends pieces in order and, after a failure, resumes at the failed piece (earlier pieces are not sent again).
    completed = 0
    failures_seen = []
    while completed < PIECES:
        response = part(completed)
        if response.status_code == 200:
            completed += 1
        else:
            assert response.status_code == 502
            failures_seen.append(completed)
            assert api.client.post(f'/api/v1/encounters/{case_id}/audio-complete', json={'job_id': job, 'count': PIECES}).status_code == 409
    done = api.client.post(f'/api/v1/encounters/{case_id}/audio-complete', json={'job_id': job, 'count': PIECES})
    return api, case_id, job, sent, failures_seen, done


def check_complete(done, sent, failures_seen, fail_at):
    assert done.status_code == 200, done.text
    text = done.json()['evidence']['text']
    positions = [text.index(s) for s in SENTENCES]          # every section present
    assert positions == sorted(positions)                    # and in order
    assert failures_seen == sorted(fail_at)
    # Each piece reached the speech service once, plus exactly one extra send per simulated failure. No piece was re-sent otherwise.
    assert sorted(sent) == sorted(list(range(PIECES)) + list(fail_at))


def test_failure_at_the_beginning(quick_api, monkeypatch):
    _, _, _, sent, seen, done = run_job(quick_api, monkeypatch, [0])
    check_complete(done, sent, seen, [0])


def test_failure_in_the_middle(quick_api, monkeypatch):
    _, _, _, sent, seen, done = run_job(quick_api, monkeypatch, [15])
    check_complete(done, sent, seen, [15])


def test_failure_at_the_end(quick_api, monkeypatch):
    _, _, _, sent, seen, done = run_job(quick_api, monkeypatch, [29])
    check_complete(done, sent, seen, [29])


def test_failures_at_beginning_middle_and_end_together(quick_api, monkeypatch):
    _, _, _, sent, seen, done = run_job(quick_api, monkeypatch, [0, 15, 29])
    check_complete(done, sent, seen, [0, 15, 29])


def test_closing_and_reopening_the_app_reuses_saved_pieces(quick_api, monkeypatch):
    """The phone forgets where it was; it re-sends every piece. Saved pieces come back instantly with no new speech request."""
    api, case_id, job, sent, _, done = run_job(quick_api, monkeypatch, [15])
    before = len(sent)
    for index in range(PIECES):
        response = api.client.post(f'/api/v1/encounters/{case_id}/audio-parts', files={'file': ('s.wav', audio(), 'audio/wav')},
            data={'job_id': job, 'index': index, 'count': PIECES, 'consent': 'true'})
        assert response.status_code == 200
    assert len(sent) == before, 'no piece may be transcribed or charged twice'
    again = api.client.post(f'/api/v1/encounters/{case_id}/audio-complete', json={'job_id': job, 'count': PIECES})
    assert again.json()['evidence']['id'] == done.json()['evidence']['id']
