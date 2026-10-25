"""Stuck-credit recovery batch job (development only). Default: REPORT ONLY, changes nothing.

    python -m scripts.release_stale_holds            # report what would be released
    python -m scripts.release_stale_holds --apply    # release holds silent for AI_STALE_RELEASE_QUIET_SECONDS (never charges)

Run only against a development or test database. It refuses to run unless AI_LEASES_ENABLED=true.
"""
import json
import sys

from server import billing
from server.core import settings
from server.db import SessionLocal


def main(argv):
    if not settings.ai_leases_enabled:
        print('AI_LEASES_ENABLED is not true; nothing was checked.')
        return 2
    with SessionLocal() as db:
        print(json.dumps(billing.release_stale_holds(db, dry_run='--apply' not in argv), indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
