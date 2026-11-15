"""PHI-safe diagnostics tests; no network access or real patient fixtures."""
import json
from unittest.mock import patch

from server import monitoring


def test_monitoring_disabled_without_configuration(monkeypatch):
    monkeypatch.delenv("AMILYA_POSTHOG_HOST", raising=False)
    monkeypatch.delenv("AMILYA_POSTHOG_PROJECT_TOKEN", raising=False)
    assert monitoring.emit("server", "failed", "request_failed") is False


def test_monitoring_rejects_unapproved_fields(monkeypatch):
    monkeypatch.setenv("AMILYA_POSTHOG_HOST", "https://example.posthog.test")
    monkeypatch.setenv("AMILYA_POSTHOG_PROJECT_TOKEN", "test-token")
    assert monitoring.emit("clinical_note", "failed", "request_failed") is False
    assert monitoring.emit("server", "failed", "patient-note-text") is False
    assert monitoring.emit("server", "success", "request_failed") is False


def test_monitoring_never_embeds_dynamic_clinical_values(monkeypatch):
    monkeypatch.setenv("AMILYA_POSTHOG_HOST", "https://example.posthog.test")
    monkeypatch.setenv("AMILYA_POSTHOG_PROJECT_TOKEN", "test-token")
    captured = []
    class ImmediatePool:
        def submit(self, fn):
            fn()
    class Response:
        def __enter__(self): return self
        def __exit__(self, *_): return False
        def read(self, _): return b"ok"
    with patch.object(monitoring, "_pool", ImmediatePool()), patch.object(
        monitoring, "urlopen", side_effect=lambda req, timeout: (captured.append(json.loads(req.data)), Response())[1]
    ):
        assert monitoring.emit("report", "recovered", "fallback_without_ai")
    assert len(captured) == 1
    event = captured[0]
    assert event["event"] == "amilya_operation_diagnostic"
    assert set(event["properties"]) == {"component", "stage", "outcome", "code", "$geoip_disable"}
    assert event["properties"]["code"] == "fallback_without_ai"
