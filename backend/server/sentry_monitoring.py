"""Sentry is opt-in and only sends closed, technical error categories.

Never capture an exception object, traceback locals, user context, HTTP request,
clinical data, text/audio/image, or arbitrary error message.
"""
from __future__ import annotations
import os

_client = None

def configure() -> bool:
    global _client
    dsn = os.getenv("AMILYA_SENTRY_DSN", "")
    if not dsn or os.getenv("AMILYA_SENTRY_APPROVED", "") != "true":
        return False
    import sentry_sdk
    sentry_sdk.init(
        dsn=dsn,
        default_integrations=False,
        auto_enabling_integrations=False,
        send_default_pii=False,
        include_local_variables=False,
        include_source_context=False,
        attach_stacktrace=False,
        traces_sample_rate=0.0,
        max_breadcrumbs=0,
        before_send=lambda event, hint: None,  # fail-closed: no SDK error payloads
    )
    _client = sentry_sdk
    return True

_ALLOWED = {
    ("server", "request_failed"),
    ("ai_extraction", "extraction_failed"),
    ("report", "fallback_without_ai"),
    ("transcription", "network_failure"),
}

def report(stage: str, code: str) -> bool:
    if _client is None or (stage, code) not in _ALLOWED:
        return False
    # The Sentry event contains only an allowlisted category, never clinical data.
    # This release keeps before_send fail-closed, so events are NOT sent until a
    # separately reviewed payload policy is approved.
    _client.capture_message("amilya." + stage + "." + code, level="error")
    return True
