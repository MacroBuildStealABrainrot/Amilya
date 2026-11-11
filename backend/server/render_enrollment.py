"""One-use, time-bounded enrollment for the empty, isolated Render test workspace.

Email is a restricted username, not a claim of verified mailbox ownership.
This never imports credentials, grants platform administration, or activates AI.
"""
import os
import re
import time
from urllib.parse import urlsplit

from sqlalchemy import select

from .core import settings

TEST_ORIGIN = 'https://amilya-repair-test.onrender.com'
MAX_WINDOW_SECONDS = 24 * 60 * 60


def configured():
    email = getattr(settings, 'render_owner_enrollment_email', '').strip().lower()
    deadline = getattr(settings, 'render_owner_enrollment_until', 0)
    if (os.environ.get('RENDER') != 'true' or os.environ.get('AMILYA_RENDER_TEST') != 'true'
            or settings.environment != 'production' or settings.public_base_url != TEST_ORIGIN
            or settings.allow_signup or not settings.secure_cookies
            or any(getattr(settings, key, False) for key in
                   ('clinical_data_enabled', 'billing_enabled', 'provider_execution_enabled', 'test_provider_mode', 'auto_create_schema'))
            or not re.fullmatch(r'[^\s@]{1,64}@[^\s@]{1,180}\.[^\s@]{1,20}', email)
            or type(deadline) is not int or not 0 < deadline - time.time() <= MAX_WINDOW_SECONDS):
        return False
    try:
        database = urlsplit(settings.database_url)
        if (database.scheme != 'postgresql+psycopg'
                or database.path != '/amilya_repair_test_db'
                or not re.fullmatch(r'dpg-[a-z0-9-]+(?:\.[a-z0-9.-]+)?', database.hostname or '')):
            return False
    except ValueError:
        return False
    return True


def is_open(db, email=None):
    if not configured():
        return False
    if email is not None and email.strip().lower() != settings.render_owner_enrollment_email.strip().lower():
        return False
    from .identity import User
    # Only an empty test account database qualifies. The existing unique email
    # constraint also makes concurrent claims of this same username atomic.
    return db.scalar(select(User.id).limit(1)) is None
