"""Sentry monitoring must not forward arbitrary exceptions or clinical metadata."""
from server import sentry_monitoring


def test_sentry_disabled_without_approval(monkeypatch):
    monkeypatch.setenv("AMILYA_SENTRY_DSN", "https://public@example.invalid/1")
    monkeypatch.delenv("AMILYA_SENTRY_APPROVED", raising=False)
    assert sentry_monitoring.configure() is False


def test_sentry_accepts_only_closed_categories():
    assert sentry_monitoring._safe_event({"message": "Patient says sensitive text"}, {}) is None
    assert sentry_monitoring._safe_event({"message": "amilya.server.unknown_code"}, {}) is None
    filtered = sentry_monitoring._safe_event({
        "message": "amilya.report.fallback_without_ai",
        "request": {"headers": {"authorization": "secret"}},
        "user": {"email": "private@example.com"},
        "exception": {"values": [{"value": "sensitive"}]},
        "extra": {"transcript": "sensitive"},
    }, {})
    assert filtered == {
        "message": "Amilya technical failure",
        "level": "warning",
        "fingerprint": ["amilya", "report", "fallback_without_ai"],
        "tags": {"component": "backend", "stage": "report", "code": "fallback_without_ai"},
    }
