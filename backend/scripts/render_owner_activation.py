"""Validate the already approved, expiring $1 synthetic-test allowance.

Reads only account eligibility and provider key metadata. Never changes accounts,
legal acceptance, subscriptions, credits or provider keys; never returns secrets.
"""
from datetime import datetime
import math
import time
from uuid import UUID

APPROVAL_END = 1793235000  # 2026-10-29 00:50 UTC, before the approved key expires


def key_is_bounded(data, deadline, now):
    if not isinstance(data, dict):
        return False
    try:
        cap, remaining, usage = (data[k] for k in ('limit', 'limit_remaining', 'usage'))
        if any(type(x) not in (int, float) or not math.isfinite(x) for x in (cap, remaining, usage)):
            return False
        expires = datetime.fromisoformat(data['expires_at'].replace('Z', '+00:00'))
        if expires.tzinfo is None:
            return False
        return (0 < cap <= 1 and 0 < remaining <= cap and 0 <= usage < cap
                and data.get('limit_reset', 'missing') is None
                and data.get('is_management_key') is False
                and now < deadline <= expires.timestamp() <= APPROVAL_END + 180)
    except (KeyError, ValueError, TypeError, AttributeError):
        return False


def check_key(env, deadline, now):
    import httpx
    try:
        response = httpx.get('https://openrouter.ai/api/v1/key',
            headers={'Authorization': 'Bearer ' + env['OPENROUTER_API_KEY']}, timeout=15)
        if response.status_code != 200 or not key_is_bounded(response.json().get('data'), deadline, now):
            raise ValueError()
    except Exception:
        raise ValueError('BOUNDED_PROVIDER_KEY_REQUIRED') from None


def prepare(env):
    """Return runtime-only ID binding or fail closed with a fixed error code."""
    now = time.time()
    try:
        deadline = int(env.get('AMILYA_RENDER_OWNER_TEST_UNTIL', '0'))
    except ValueError:
        raise ValueError('DEADLINE_INVALID') from None
    if not now < deadline <= min(now + 86400, APPROVAL_END):
        raise ValueError('TEST_WINDOW_CLOSED')
    if (env.get('PUBLIC_BASE_URL') != 'https://amilya-repair-test.onrender.com'
            or not env.get('OPENROUTER_API_KEY')
            or env.get('OPENROUTER_BASE_URL', 'https://openrouter.ai/api/v1') != 'https://openrouter.ai/api/v1'
            or env.get('UNLIMITED_CREDIT_USER_IDS', '').strip()):
        raise ValueError('TEST_CONTEXT_INVALID')
    import psycopg
    url = 'postgresql://' + env['DATABASE_URL'].split('://', 1)[1]
    try:
        with psycopg.connect(url, connect_timeout=10, options='-c statement_timeout=10000') as conn:
            with conn.cursor() as cursor:
                cursor.execute('SELECT id,email,is_active,is_platform_admin,workspace_id FROM users LIMIT 2')
                users = cursor.fetchall()
                if (len(users) != 1 or users[0][1].strip().lower() != env.get('RENDER_OWNER_ENROLLMENT_EMAIL', '').strip().lower()
                        or not users[0][2] or users[0][3]):
                    raise ValueError('OWNER_ACCOUNT_REQUIRED')
                user_id = str(UUID(users[0][0]))
                cursor.execute('SELECT 1 FROM memberships WHERE user_id=%s AND workspace_id=%s AND role=%s',
                               (user_id, users[0][4], 'owner'))
                if cursor.fetchone() is None:
                    raise ValueError('OWNER_MEMBERSHIP_REQUIRED')
                version = env.get('LEGAL_VERSION', '2026-09-05')
                cursor.execute('SELECT 1 FROM legal_acceptances WHERE user_id=%s AND terms_version=%s AND privacy_version=%s',
                               (user_id, version, version))
                if cursor.fetchone() is None:
                    raise ValueError('OWNER_POLICY_ACCEPTANCE_REQUIRED')
    except ValueError:
        raise
    except Exception:
        raise ValueError('ACCOUNT_CHECK_UNAVAILABLE') from None
    # Honor platform proxies; never print headers, response bodies or labels.
    check_key(env, deadline, now)
    return {'RENDER_OWNER_TEST_USER_ID': user_id, 'RENDER_OWNER_TEST_UNTIL': str(deadline),
            'PROVIDER_EXECUTION_ENABLED': 'true', 'OPENROUTER_REQUIRE_ZDR': 'true'}
