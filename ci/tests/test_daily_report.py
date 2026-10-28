import sys
from pathlib import Path

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
