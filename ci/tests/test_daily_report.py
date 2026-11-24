import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import daily_report as dr  # noqa: E402


def base(**overrides):
    data = {
        'date': '2026-10-28',
        'health': {'ok': True, 'detail': 'version 6.0.0'},
        'github': {'runs': [{'name': 'amilya-sandbox-ci', 'branch': 'main', 'conclusion': 'success', 'url': 'u'}],
                   'running': 0, 'apk': None, 'pulls': [], 'approvals': []},
        'sentry': None,
        'gcp': {'traffic': [{'revision': dr.STABLE_REVISION, 'percent': 100}], 'errors': {}, 'prod_routes': []},
    }
    data.update(overrides)
    return data


def test_quiet_day_has_no_alert():
    assert dr.assess(base()) == []
    text = dr.render(base(), owner='me')
    assert 'Nothing serious today' in text and '@me' not in text


def test_unconnected_sources_are_stated_not_guessed():
    text = dr.render(base(gcp=None))
    assert 'Google Cloud details: not connected yet' in text
    assert 'Sentry' in text and 'Not connected yet' in text
    assert 'Spending' in text and 'Not connected yet' in text


def test_traffic_off_stable_revision_is_serious_and_mentions_owner():
    data = base(gcp={'traffic': [{'revision': 'woundcopilot-new', 'percent': 100}], 'errors': {}, 'prod_routes': []})
    assert any('not on the approved version' in s for s in dr.assess(data))
    assert '@me' in dr.render(data, owner='me')


def test_prod_error_threshold_and_test_revisions_ignored():
    data = base(gcp={'traffic': [{'revision': dr.STABLE_REVISION, 'percent': 100}],
                     'errors': {'woundcopilot-photofix': 50, dr.STABLE_REVISION: dr.SERIOUS_PROD_ERRORS - 1},
                     'prod_routes': []})
    assert dr.assess(data) == []
    data['gcp']['errors'][dr.STABLE_REVISION] = dr.SERIOUS_PROD_ERRORS
    assert len(dr.assess(data)) == 1


def test_health_failure_and_main_test_failure_are_serious():
    data = base(health={'ok': False, 'detail': 'URLError'})
    data['github']['runs'][0]['conclusion'] = 'failure'
    assert len(dr.assess(data)) == 2


def test_route_shape_redacts_ids():
    url = 'https://x.run.app/api/v1/encounters/d2b7615a-140a-5dd9-94b0-ff11963f885c/upload?a=1'
    assert dr.route_shape(url) == '/api/v1/encounters/{id}/upload'


def test_deepgram_states():
    assert 'Deepgram (transcription): not connected yet' in dr.render(base())
    assert 'could not read (HTTP 403)' in dr.render(base(deepgram={'error': 'HTTP 403'}))
    text = dr.render(base(deepgram={'balances': [{'amount': 12.5, 'units': 'usd'}], 'hours_today': 0.4, 'hours_month': 3.1}))
    assert '0.4 audio hours today, 3.1 this month' in text and '12.5 usd' in text


def test_waiting_items_listed():
    data = base()
    data['github']['approvals'] = [{'number': 5, 'title': 'Turn on database backups', 'url': 'a'}]
    data['github']['pulls'] = [{'number': 1, 'title': 'Fix', 'draft': True, 'url': 'p'}]
    text = dr.render(data)
    assert 'Approval #5: Turn on database backups' in text and 'Draft change #1' in text


def test_errors_follow_current_traffic_including_split_and_tagged_revisions():
    service = {'status': {'latestReadyRevisionName': 'active-b', 'traffic': [
        {'revisionName': 'active-a', 'percent': 80},
        {'latestRevision': True, 'percent': 20},
        {'revisionName': 'active-a', 'tag': 'preview'},
        {'revisionName': dr.STABLE_REVISION, 'percent': 0},
    ]}}
    def entry(rev, path):
        return {'resource': {'labels': {'revision_name': rev}},
                'httpRequest': {'status': 502, 'requestUrl': 'https://example.invalid' + path}}
    known = '/api/v1/encounters/example-id-marker/audio-complete?ignored=value'
    records = [entry('active-a', known)] * 4 + [
        entry('active-b', '/unrecognized/private-marker'),
        entry(dr.STABLE_REVISION, known),
    ]
    def fake_gcloud(*args, fields):
        if args[0] == 'run':
            return service
        assert 'run.googleapis.com/requests' in args[-1]
        assert fields == 'resource.labels.revision_name,httpRequest.status,httpRequest.requestUrl'
        return records

    with patch.object(dr, 'gcloud', fake_gcloud):
        text = dr.render(base(gcp=dr.collect_gcp()))
    assert 'currently receiving live traffic: 5.' in text
    assert 'Found 5 server errors' in text and 'not on the approved version' in text
    assert '4 x 502 /api/v1/encounters/{id}/audio-complete' in text
    assert '1 x 502 other route' in text
    assert f'Errors on other versions during this period: {dr.STABLE_REVISION} 1' in text
    assert not any(word in text for word in ('private-marker', 'example-id-marker', 'ignored=value', 'not seen by users'))


def test_logs_unavailable_distinct_from_empty():
    service = {'status': {'traffic': [{'revisionName': dr.STABLE_REVISION, 'percent': 100}]}}
    for logs, expected in [(None, 'Live server error count: unavailable'),
                           ([], 'currently receiving live traffic: 0.')]:
        with patch.object(dr, 'gcloud', side_effect=[service, logs]):
            report = dr.render(base(gcp=dr.collect_gcp()))
        assert expected in report
        if logs is None:
            assert 'Nothing serious today' not in report


def test_unknown_live_traffic_does_not_give_false_zero():
    with patch.object(dr, 'gcloud', side_effect=[{'status': {'traffic': []}}, []]):
        report = dr.render(base(gcp=dr.collect_gcp()))
    assert 'Live server error count: unavailable' in report
    assert 'Could not identify which versions' in report
