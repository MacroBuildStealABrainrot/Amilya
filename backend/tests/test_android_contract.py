"""The Android app recognises these server answers by their wording. Changing them silently breaks the phone's
automatic recovery (offline start -> 'You do not have permission', and waiting on a request that is still running)."""
import inspect

from server import identity, providers


def test_csrf_rejection_wording_matches_the_phone_recovery_rule():
    # Android: isCsrfRejection(status, message) == status 403 && message contains "request verification".
    source = inspect.getsource(identity.require_csrf)
    assert "raise HTTPException(403,'Invalid request verification. Refresh and try again.')" in source


def test_still_processing_wording_matches_the_phone_wait_rule():
    # Android treats 409 + "already processing or awaiting reconciliation" as "still running, wait and retry the same key".
    assert 'already processing or awaiting reconciliation' in inspect.getsource(providers.metered_call)
