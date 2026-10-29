"""Start only the isolated Render test service; never import live Cloud Run settings."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


class ConfigurationError(ValueError):
    pass


def runtime_environment(source):
    """Validate before migrations and return an environment without logging its values."""
    env = dict(source)
    # Runtime grants can only come from the verified account/key check below.
    env.pop('RENDER_OWNER_TEST_USER_ID', None)
    env.pop('RENDER_OWNER_TEST_UNTIL', None)
    if env.get('RENDER') != 'true' or env.get('AMILYA_RENDER_TEST') != 'true':
        raise ConfigurationError('RENDER_TEST_CONTEXT_REQUIRED')
    hostname = env.get('RENDER_EXTERNAL_HOSTNAME', '')
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]*\.onrender\.com', hostname):
        raise ConfigurationError('RENDER_HOST_REQUIRED')
    database = env.get('DATABASE_URL', '')
    try:
        parsed = urlsplit(database)
        expected_host = env.get('AMILYA_RENDER_DB_HOST', '')
        if (parsed.scheme not in ('postgres', 'postgresql', 'postgresql+psycopg')
                or not re.fullmatch(r'dpg-[a-z0-9-]+(?:\.[a-z0-9.-]+)?', expected_host)
                or parsed.hostname != expected_host
                or parsed.path != '/amilya_repair_test_db'
                or not parsed.username or not parsed.password
                or parsed.port not in (None, 5432)):
            raise ValueError()
    except ValueError:
        raise ConfigurationError('ISOLATED_DATABASE_REQUIRED') from None
    if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]{0,62}', parsed.username):
        raise ConfigurationError('ISOLATED_DATABASE_ROLE_REQUIRED')
    env['WCP_DB_RUNTIME_ROLE'] = parsed.username
    if len(env.get('APP_SECRET', '')) < 32:
        raise ConfigurationError('APP_SECRET_REQUIRED')
    port = env.get('PORT', '10000')
    if not port.isdigit() or not 1024 <= int(port) <= 65535 or int(port) in (18012, 18013, 19099):
        raise ConfigurationError('PORT_INVALID')
    # These guards are deliberate: changing this test service into clinical use
    # or activating billable providers requires a separately reviewed deployment.
    for name in ('CLINICAL_DATA_ENABLED', 'BILLING_ENABLED', 'PROVIDER_EXECUTION_ENABLED',
                 'TEST_PROVIDER_MODE', 'AUTO_CREATE_SCHEMA', 'ALLOW_SIGNUP'):
        if env.get(name, 'false').lower() != 'false':
            raise ConfigurationError('TEST_BOUNDARY_REQUIRED')
        env[name] = 'false'
    env.update(ENVIRONMENT='production', SECURE_COOKIES='true',
               PUBLIC_BASE_URL='https://' + hostname,
               TRUSTED_HOSTS=hostname + ',localhost,127.0.0.1',
               WOUNDCOPILOT_ENV_FILE='/tmp/amilya-render-no-env-file',
               FRONTEND_PATH='/app/frontend/dist',
               DATABASE_URL='postgresql+psycopg://' + database.split('://', 1)[1],
               PORT=port)
    return env


def main():
    try:
        env = runtime_environment(os.environ)
    except ConfigurationError as exc:
        print('Render test startup blocked: ' + str(exc), file=sys.stderr)
        return 1
    root = Path(__file__).resolve().parents[1]
    # Free Render services have no pre-deploy command. Migrate this dedicated,
    # empty test database before opening the HTTP socket. Never expose exception
    # tracebacks or connection strings from failed migration commands.
    try:
        result = subprocess.run([sys.executable, '-m', 'alembic', 'upgrade', 'head'],
                                cwd=root, env=env, capture_output=True, timeout=180)
    except (subprocess.TimeoutExpired, OSError):
        print('Render test startup blocked: MIGRATION_UNAVAILABLE', file=sys.stderr)
        return 1
    if result.returncode:
        print('Render test startup blocked: MIGRATION_FAILED', file=sys.stderr)
        return 1
    if env.get('AMILYA_RENDER_SYNTHETIC_PROBE'):
        # A separate process, fixed inputs and a durable once-only claim. Never
        # activate provider execution in this public HTTP application's settings.
        try:
            long_run = env.get('AMILYA_RENDER_SYNTHETIC_PROBE') == 'report-haiku-v9'
            probe = subprocess.run([sys.executable, str(root / 'scripts/render_synthetic_probe.py')],
                                   cwd=root, env=env, capture_output=True, timeout=540 if long_run else 240)
            for line in probe.stdout.decode('utf-8', errors='replace').splitlines():
                if re.fullmatch(r'AMILYA_SYNTHETIC_PROBE (?:stage=(?:photo|extract|report|audio|wound) )?status=(?:PASS|FAIL|BLOCKED|ALREADY_CLAIMED)(?: code=[A-Z0-9_]{1,40})?', line):
                    print(line, flush=True)
                elif re.fullmatch(r'AMILYA_PHOTO_COMPARE profile=(?:high|medium) image=(?:shapes|wound) status=(?:PASS|FAIL) fields=[0-4] cost_micros=(?:UNKNOWN|\d{1,9}) latency_ms=(?:UNKNOWN|\d{1,9}) input_tokens=(?:UNKNOWN|\d{1,9}) output_tokens=(?:UNKNOWN|\d{1,9}) cached_tokens=(?:UNKNOWN|\d{1,9})', line):
                    print(line, flush=True)
                elif re.fullmatch(r'AMILYA_REPORT_COMPARE case=[1-4] variant=[AB] status=(?:PASS|FAIL) qa=(?:PASS|FAIL) latency_ms=\d{1,9} text_b64=[A-Za-z0-9+/=]{0,8200}', line) \
                        or re.fullmatch(r'AMILYA_REPORT_COMPARE_KEY case=[1-4] A=(?:current|haiku) B=(?:current|haiku)', line) \
                        or line == 'AMILYA_REPORT_COMPARE status=BLOCKED':
                    # Fixed fictional visits only; generated text is synthetic training output.
                    print(line, flush=True)
        except (subprocess.TimeoutExpired, OSError):
            print('AMILYA_SYNTHETIC_PROBE status=FAIL code=TIMEOUT', flush=True)
    if env.get('AMILYA_RENDER_OWNER_TEST_UNTIL'):
        from render_owner_activation import prepare
        try:
            env.update(prepare(env))
            print('AMILYA_OWNER_TEST status=READY', flush=True)
        except ValueError as exc:
            # Fixed codes only; keep manual use available when activation fails.
            allowed = {'DEADLINE_INVALID', 'TEST_WINDOW_CLOSED', 'TEST_CONTEXT_INVALID',
                'OWNER_ACCOUNT_REQUIRED', 'OWNER_MEMBERSHIP_REQUIRED',
                'OWNER_POLICY_ACCEPTANCE_REQUIRED', 'ACCOUNT_CHECK_UNAVAILABLE',
                'BOUNDED_PROVIDER_KEY_REQUIRED'}
            code = str(exc) if str(exc) in allowed else 'ACTIVATION_UNAVAILABLE'
            print('AMILYA_OWNER_TEST status=BLOCKED code=' + code, flush=True)
    os.chdir(root)
    os.execve(sys.executable, [sys.executable, '-m', 'uvicorn', 'server.main:app',
              '--host', '0.0.0.0', '--port', env['PORT'], '--no-access-log',
              '--proxy-headers', '--forwarded-allow-ips=*'], env)


if __name__ == '__main__':
    raise SystemExit(main())

