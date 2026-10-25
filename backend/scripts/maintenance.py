"""Remove expired temporary replay/session/rate metadata; never delete clinical records.

Run as a scheduled job with the same database settings. Reports counts only.
"""
from datetime import timedelta
import json, time
from sqlalchemy import delete, select
from server.db import SessionLocal, utcnow
from server.identity import LoginSession, MobileSession, RateWindow
from server.provider_models import ProviderResult

def clean():
    with SessionLocal() as db:
        counts = {
            'expired_mobile_sessions': db.execute(delete(MobileSession).where(MobileSession.expires_at < utcnow())).rowcount,
            'provider_replays': db.execute(delete(ProviderResult).where(ProviderResult.created_at < utcnow() - timedelta(hours=24))).rowcount,
            'expired_sessions': db.execute(delete(LoginSession).where(LoginSession.expires_at < utcnow(), LoginSession.id.not_in(select(MobileSession.access_id).where(MobileSession.expires_at>utcnow())))).rowcount,
            'expired_rate_windows': db.execute(delete(RateWindow).where(RateWindow.expires < int(time.time()))).rowcount,
        }
        db.commit()
        return counts

if __name__ == '__main__': print(json.dumps(clean()))
