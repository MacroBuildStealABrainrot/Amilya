"""The Render test launcher must reject live/unsafe settings before migration."""
import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location('render_test_start', Path(__file__).parents[1] / 'scripts/render_test_start.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


def source():
    return dict(RENDER='true', AMILYA_RENDER_TEST='true',
                RENDER_EXTERNAL_HOSTNAME='amilya-repair-test.onrender.com',
                AMILYA_RENDER_DB_HOST='dpg-synthetic-a',
                DATABASE_URL='postgresql://test:synthetic-password@dpg-synthetic-a/amilya_repair_test_db',
                APP_SECRET='synthetic-app-secret-never-used-live-' * 2, PORT='10000')


def test_normalizes_driver_and_derives_exact_web_origin():
    env = launcher.runtime_environment(source())
    assert env['DATABASE_URL'].startswith('postgresql+psycopg://')
    assert env['PUBLIC_BASE_URL'] == 'https://amilya-repair-test.onrender.com'
    assert env['TRUSTED_HOSTS'] == 'amilya-repair-test.onrender.com,localhost,127.0.0.1'
    assert env['CLINICAL_DATA_ENABLED'] == env['PROVIDER_EXECUTION_ENABLED'] == env['BILLING_ENABLED'] == 'false'
    assert env['ENVIRONMENT'] == 'production'


@pytest.mark.parametrize('changes', [
    {'RENDER': 'false'}, {'AMILYA_RENDER_TEST': 'false'},
    {'RENDER_EXTERNAL_HOSTNAME': 'repair---woundcopilot-rhd7rrcqdq-uc.a.run.app'},
    {'RENDER_EXTERNAL_HOSTNAME': '*.onrender.com'},
    {'DATABASE_URL': 'postgresql://test:synthetic-password@10.30.0.10/woundcopilot'},
    {'DATABASE_URL': 'postgresql://test:synthetic-password@dpg-synthetic-a/woundcopilot'},
    {'DATABASE_URL': 'postgresql://test:synthetic-password@dpg-other-a/amilya_repair_test_db'},
    {'DATABASE_URL': 'sqlite:///work/test.db'}, {'APP_SECRET': ''},
    {'PORT': '18012'}, {'PORT': 'bad'},
    {'CLINICAL_DATA_ENABLED': 'true'}, {'BILLING_ENABLED': 'true'},
    {'PROVIDER_EXECUTION_ENABLED': 'true'}, {'TEST_PROVIDER_MODE': 'true'},
    {'AUTO_CREATE_SCHEMA': 'true'}, {'ALLOW_SIGNUP': 'true'},
])
def test_rejects_before_migrating_without_exposing_values(changes):
    config = source() | changes
    with pytest.raises(launcher.ConfigurationError) as exc:
        launcher.runtime_environment(config)
    assert 'synthetic-password' not in str(exc.value)
    assert 'postgresql://' not in str(exc.value)


def test_existing_google_settings_cannot_override_render_origin():
    env = launcher.runtime_environment(source() | {'PUBLIC_BASE_URL': 'https://old.example', 'TRUSTED_HOSTS': '*', 'WOUNDCOPILOT_ENV_FILE': '/run/secrets/app.env'})
    assert env['PUBLIC_BASE_URL'] == 'https://amilya-repair-test.onrender.com'
    assert '*' not in env['TRUSTED_HOSTS']
    assert env['WOUNDCOPILOT_ENV_FILE'] != '/run/secrets/app.env'
