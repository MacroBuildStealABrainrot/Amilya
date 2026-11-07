"""Hourly Sentry monitor, driven with synthetic Sentry answers only (no real events, no network)."""
import sys
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sentry_monitor as m  # noqa: E402

NOW = datetime(2026, 11, 7, 18, 30, tzinfo=timezone.utc)
HOUR = int(NOW.timestamp()) // 3600 * 3600


def issue(id_, short, count, *, level='warning', status='unresolved', substatus='new', title='Amilya android: generation_request failed',
          last_hour=1):
    return {'id': id_, 'shortId': short, 'count': str(count), 'level': level, 'status': status, 'substatus': substatus,
            'title': title, 'permalink': f'https://amilya.sentry.io/issues/{id_}/', 'firstSeen': '2026-11-07T17:50:00Z',
            'lastSeen': '2026-11-07T18:20:00Z', 'stats': {'24h': [[HOUR - 7200, 4], [HOUR - 3600, 0], [HOUR, last_hour]]}}


def sentry(answers, tags=None):
    """A fake Sentry: answers maps project slug to its issue list (or an exception to raise)."""
    tags = tags or {}

    def get(url, token):
        for project, answer in answers.items():
            if f'/projects/amilya/{project}/issues/' in url:
                if isinstance(answer, Exception):
                    raise answer
                return answer
        if '/events/latest/' in url:
            issue_id = url.split('/issues/')[1].split('/')[0]
            return {'tags': [{'key': k, 'value': v} for k, v in tags.get(issue_id, {}).items()],
                    'message': 'PATIENT TEXT THAT MUST NEVER BE COPIED', 'entries': [{'data': 'request body'}]}
        raise AssertionError(url)
    return get


ANDROID_TAGS = {'environment': 'isolated-test', 'component': 'android', 'stage': 'generation_request', 'code': 'failed',
                'retries': '0', 'recording_exists': 'true', 'kind': 'device'}


def test_a_new_issue_is_reported_once_with_its_sentry_link():
    get = sentry({'amilya-android': [issue('101', 'AMILYA-ANDROID-3', 1)], 'amilya-backend': []}, {'101': ANDROID_TAGS})
    comment, state = m.run(NOW, 'token', {}, get)
    assert '### New: [AMILYA-ANDROID-3](https://amilya.sentry.io/issues/101/)' in comment
    assert 'Report generation' in comment and 'Test server' in comment and 'Medium severity' in comment
    assert 'PATIENT TEXT' not in comment and 'request body' not in comment
    # The same issue an hour later with one more event is not a new incident.
    get = sentry({'amilya-android': [issue('101', 'AMILYA-ANDROID-3', 2)], 'amilya-backend': []}, {'101': ANDROID_TAGS})
    comment, state = m.run(NOW, 'token', state, get)
    assert comment is None and state['issues']['101']['count'] == 2


def test_material_worsening_reopening_and_a_louder_level_are_reported():
    state = {'issues': {'7': {'count': 4, 'level': 'warning', 'status': 'unresolved', 'substatus': 'ongoing'},
                        '8': {'count': 3, 'level': 'warning', 'status': 'resolved'},
                        '9': {'count': 2, 'level': 'warning', 'status': 'unresolved'}}}
    get = sentry({'amilya-android': [issue('7', 'A-7', 12), issue('8', 'A-8', 4, substatus='regressed'), issue('9', 'A-9', 3, level='fatal')],
                  'amilya-backend': []})
    comment, _ = m.run(NOW, 'token', state, get)
    assert '### Getting worse: [A-7]' in comment and '### Reopened: [A-8]' in comment and '### Getting worse: [A-9]' in comment


def test_an_unreachable_sentry_is_a_monitoring_failure_never_zero_errors():
    for failure in (urllib.error.HTTPError('u', 401, 'no', {}, None), urllib.error.URLError('down'), TimeoutError()):
        comment, state = m.run(NOW, 'token', {}, sentry({'amilya-android': failure, 'amilya-backend': []}))
        assert 'Monitoring failure' in comment and 'not** a report of zero errors' in comment and state['failing']
    # The same failure next hour is not posted again; recovery is announced.
    comment, state = m.run(NOW, 'token', state, sentry({'amilya-android': TimeoutError(), 'amilya-backend': []}))
    assert comment is None
    comment, state = m.run(NOW, 'token', state, sentry({'amilya-android': [], 'amilya-backend': []}))
    assert 'can be read again' in comment and state['failing'] is None
    comment, _ = m.run(NOW, '', {}, sentry({}))
    assert 'token is not available' in comment


def test_each_workflow_gets_its_own_category():
    cases = {'recording_stopped': 'Recording', 'upload': 'Multipart handover', 'transcript_assembly': 'Multipart handover',
             'transcription_request': 'Transcription', 'photo_request': 'Image upload', 'external_failed': 'Image upload',
             'generation_request': 'Report generation', 'report_save': 'Report saving', 'consult_reply': 'Ask Consultant',
             'app_crash': 'App crash or freeze'}
    for stage, category in cases.items():
        assert m.classify({'stage': stage, 'component': 'android'}) == category, stage
    assert m.classify({'stage': 'source_faithfulness_initial', 'kind': 'clinical_check'}) == 'Clinical accuracy check'
    assert m.classify({'stage': 'report_model_fallback', 'kind': 'fallback'}) == 'AI fallback'
    assert m.classify({'stage': 'server', 'code': 'unexpected_exception', 'component': 'backend'}) == 'Backend exception'
    assert m.where('isolated-test') == 'Test server' and m.where('production') == 'Production' and m.where('').startswith('Unknown')


def test_only_safe_metadata_is_kept():
    tags = m.safe_tags([{'key': 'stage', 'value': 'upload'}, {'key': 'note', 'value': 'left heel wound'},
                        {'key': 'code', 'value': 'contains spaces and words'}, ['http_status', '502']])
    assert tags == {'stage': 'upload', 'http_status': '502'}
    assert m.safe_title('Amilya backend: source_faithfulness_initial report_check_failed').startswith('Amilya backend')
    assert m.safe_title('ValueError: patient Jane Doe left heel') == '(title withheld for privacy)'


def test_state_survives_in_the_issue_body():
    state = {'issues': {'1': {'count': 3}}, 'last_check': 'x', 'failing': None}
    assert m.read_state(m.issue_body(state, NOW)) == state and m.read_state('no state here') == {}
