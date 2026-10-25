"""Every server address the Android app calls must exist with the same HTTP method (a mismatch is an HTTP 405 or 404)."""
import re
from pathlib import Path

import pytest

ANDROID = Path(__file__).resolve().parents[2] / 'android' / 'app' / 'src' / 'main' / 'java'
CALL = re.compile(r'api\.call\("((?:[^"$]|\$\{[^}]*\}|\$)+)"(?:\s*,\s*"([A-Z]+)")?')
UPLOAD = re.compile(r'api\.(?:multipart|multipartBytes)\("([^"]+)"')
MUTATION = re.compile(r'mutation\([^,]+,\s*"([^"]+)"')
TEMPLATE = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')


def android_calls():
    found = set()
    if not ANDROID.is_dir():
        pytest.skip('Android sources are not next to the backend in this checkout')
    for file in ANDROID.rglob('*.kt'):
        text = file.read_text(encoding='utf-8')
        for path, method in CALL.findall(text):
            found.add((method or 'GET', path))
        for path in UPLOAD.findall(text) + MUTATION.findall(text):
            found.add(('POST', path))
    return found


def normalize(path):
    path = path.split('?', 1)[0]
    path = TEMPLATE.sub('{x}', path)
    return path


def server_routes():
    import server.main  # noqa: F401  (registers every router exactly as production does)
    from server import (identity, diagnostics, clinical, billing, contact, providers, audio_verification,
                        quick_workflows, transfers, work_privacy, audio_parts, photo_collection)
    routes = set()
    modules = (identity, diagnostics, clinical, billing, contact, providers, audio_verification,
               quick_workflows, transfers, work_privacy, audio_parts, photo_collection)
    for module in modules:
        for route in module.router.routes:
            for method in getattr(route, 'methods', None) or []:
                routes.add((method, '/api/v1' + re.sub(r'\{[^}]+\}', '{x}', route.path)))
    for path in ('/api/v1/health', '/api/v1/config'):
        routes.add(('GET', path))
    return routes


def matches(method, path, routes):
    pattern = re.compile('^/api/v1' + re.escape(path).replace(re.escape('{x}'), '[^/]+') + '$')
    for route_method, route_path in routes:
        candidate = route_path.replace('{x}', 'X')
        if route_method == method and pattern.match(candidate):
            return True
    return False


def test_every_android_call_has_a_matching_server_route():
    routes = server_routes()
    calls = android_calls()
    assert len(calls) >= 40, 'the scan found too few app calls; the pattern is probably broken'
    missing, checked = [], 0
    for method, raw in sorted(calls):
        path = normalize(raw)
        if path.startswith('/test/') or path.endswith('/') or '{x}' == path.strip('/'):
            continue  # instrumentation-only fake-server helpers, or a prefix completed elsewhere
        if path.startswith('{x}'):
            continue  # '$path/...' built from a variable prefix; covered by the explicit encounter routes
        # A trailing variable segment may be a fixed word (e.g. transcribe-$captureKind).
        checked += 1
        if not matches(method, path, routes) and not matches(method, re.sub(r'-\{x\}$', '-message', path), routes):
            missing.append(f'{method} {raw}')
    assert checked >= 35
    assert not missing, 'Android calls with no server route: ' + ', '.join(missing)


def test_the_checker_notices_a_wrong_method():
    routes = server_routes()
    assert matches('POST', '/encounters/{x}/quick-report', routes)
    assert not matches('GET', '/encounters/{x}/quick-report', routes)
    assert not matches('POST', '/encounters/{x}/no-such-route', routes)


def test_a_write_to_a_missing_route_is_a_clear_404_not_a_405():
    from fastapi.testclient import TestClient
    from server.db import get_db
    from server.main import app

    class NoDatabase:  # keeps this test from creating the local development database file
        def get(self, *args, **kwargs):
            return None
    app.dependency_overrides[get_db] = lambda: NoDatabase()
    try:
        client = TestClient(app)
        response = client.post('/api/v1/encounters/x/feature-from-a-newer-app', json={})
        assert response.status_code == 404
        assert response.headers.get('X-WCP-Error') == 'ROUTE_NOT_DEPLOYED'
        assert 'newer than the server' in response.json()['detail']
        # Existing routes are unaffected: an unauthenticated write still reaches its real route (401, not 404).
        assert client.post('/api/v1/quick/start', json={}).status_code == 401
        assert client.put('/api/v1/encounters/x/photo-layout', json={}).status_code == 401
    finally:
        app.dependency_overrides.pop(get_db, None)
