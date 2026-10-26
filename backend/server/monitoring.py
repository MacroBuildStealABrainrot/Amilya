"""Opt-in, PHI-free failure signals to PostHog.

Never include paths, free-form messages, identifiers, upstream responses,
exception strings, patient data or source content in outbound events.
Disabled unless both environment variables are configured.
"""
from __future__ import annotations
import json
import os
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from uuid import uuid4

_STAGES = frozenset({"server", "ai_extraction", "transcription", "report", "photo",
                     "recording", "recording_handover"})
_OUTCOMES = frozenset({"failed", "recovered", "crashed"})
_CODES = frozenset({"unexpected", "request_failed", "timeout", "extraction_failed",
                    "fallback_without_ai", "invalid_response", "handover_failed",
                    "upload_failed", "network_failure"})
_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="amilya-diagnostic")

def emit(stage: str, outcome: str, code: str) -> bool:
    """Return False if disabled/invalid. Never block a clinical operation."""
    if stage not in _STAGES or outcome not in _OUTCOMES or code not in _CODES:
        return False
    host = os.environ.get("AMILYA_POSTHOG_HOST", "").rstrip("/")
    token = os.environ.get("AMILYA_POSTHOG_PROJECT_TOKEN", "")
    parts = urlparse(host)
    if not token or parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.path or parts.query:
        return False
    body = json.dumps({
        "api_key": token,
        "event": "amilya_operation_diagnostic",
        "distinct_id": str(uuid4()),
        "properties": {"component": "backend", "stage": stage,
                       "outcome": outcome, "code": code,
                       "$geoip_disable": True},
    }).encode("utf-8")
    def send() -> None:
        try:
            request = Request(host + "/capture/", data=body, headers={"Content-Type": "application/json"}, method="POST")
            with urlopen(request, timeout=3) as response:
                response.read(64)
        except Exception:
            pass  # Diagnostics must never break a clinical request.
    try:
        _pool.submit(send)
        return True
    except RuntimeError:
        return False
