"""Runtime-only allowance for the single enrolled synthetic-test account."""
import os
import time
from uuid import UUID
from .core import settings

APPROVAL_END = 1793843400


def required():
    return (os.environ.get('RENDER') == 'true' and os.environ.get('AMILYA_RENDER_TEST') == 'true'
            and settings.environment == 'production')


def available():
    try:
        valid_id = str(UUID(settings.render_owner_test_user_id)) == settings.render_owner_test_user_id
    except (ValueError, TypeError, AttributeError):
        return False
    return bool(required() and valid_id and settings.provider_execution_enabled
        and 0 < time.time() < settings.render_owner_test_until <= APPROVAL_END
        and settings.public_base_url == 'https://amilya-repair-test.onrender.com'
        and settings.secure_cookies and not settings.allow_signup
        and not settings.clinical_data_enabled and not settings.billing_enabled
        and not settings.test_provider_mode and not settings.auto_create_schema)


def allows(user):
    return bool(available() and user.id == settings.render_owner_test_user_id
                and user.is_active and not user.is_platform_admin)

