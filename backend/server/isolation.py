"""Server side of the isolated test app boundary.

The isolated test app (Android build type ``isolatedTest``) marks every request with ``X-Amilya-Build: isolated-test``.
Only the isolated Render test service may accept those requests. Any other server (above all the live Cloud Run
service, once it runs this code) refuses them before reading the body, so nothing from that app is stored or processed.

The isolated test service also declares itself in ``/config`` (``isolated_test_server: true``), and only when its
whole safety boundary holds: Render test environment, its own test database, the test address, and clinical data,
billing and sign-up all switched off.
"""
import os
from urllib.parse import urlsplit

from .core import settings

ISOLATED_BUILD_HEADER = 'x-amilya-build'
ISOLATED_BUILD_VALUE = 'isolated-test'
TEST_ORIGIN = 'https://amilya-repair-test.onrender.com'
TEST_DATABASE_PATH = '/amilya_repair_test_db'


def is_isolated_test_server() -> bool:
    if os.environ.get('RENDER') != 'true' or os.environ.get('AMILYA_RENDER_TEST') != 'true':
        return False
    if settings.public_base_url != TEST_ORIGIN or settings.environment != 'production' or not settings.secure_cookies:
        return False
    if settings.clinical_data_enabled or settings.billing_enabled or settings.allow_signup:
        return False
    try:
        return urlsplit(settings.database_url).path == TEST_DATABASE_PATH
    except ValueError:
        return False


def refuses(headers) -> bool:
    """True when a request from the isolated test app reached a server that is not the isolated test server."""
    return (headers.get(ISOLATED_BUILD_HEADER, '').strip().lower() == ISOLATED_BUILD_VALUE
            and not is_isolated_test_server())
