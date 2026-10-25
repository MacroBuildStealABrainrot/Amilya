from datetime import timedelta
from sqlalchemy import update, delete, select
from server import identity
from server.db import utcnow
from test_identity import client, signup

def native(c):
    c.headers['X-WCP-Client']='android'
    return signup(c)

def test_native_refresh_after_expiry_preserves_identity(client):
    c,factory=client;original=native(c).json()
    with factory() as db:
        db.execute(update(identity.LoginSession).values(expires_at=utcnow()-timedelta(seconds=1)));db.commit()
    assert c.get('/api/v1/auth/session').status_code==401
    r=c.post('/api/v1/auth/refresh');assert r.status_code==200
    assert r.json()['user']['id']==original['user']['id']
    assert c.get('/api/v1/auth/session').status_code==200

def test_revocation_cannot_be_refreshed(client):
    c,factory=client;native(c)
    with factory() as db:db.execute(delete(identity.LoginSession));db.commit()
    assert c.post('/api/v1/auth/refresh').status_code==401

def test_password_change_invalidates_native_access_and_refresh(client):
    c,factory=client;native(c)
    with factory() as db:db.execute(update(identity.User).values(password_hash=identity.ph.hash('New-synthetic-password')));db.commit()
    assert c.get('/api/v1/auth/session').status_code==401
    assert c.post('/api/v1/auth/refresh').status_code==401

def test_disable_and_cross_origin_refresh(client):
    c,factory=client;native(c)
    assert c.post('/api/v1/auth/refresh',headers={'Origin':'https://evil.example'}).status_code==403
    with factory() as db:db.execute(update(identity.User).values(is_active=False));db.commit()
    assert c.post('/api/v1/auth/refresh').status_code==401

def test_existing_valid_android_session_enrolls_without_password(client):
    c,factory=client;signup(c)
    assert not c.cookies.get('wcp_refresh')
    assert c.get('/api/v1/auth/session',headers={'X-WCP-Client':'android'}).status_code==200
    assert c.cookies.get('wcp_refresh')

def test_logout_removes_both_secrets_and_server_refresh(client):
    c,factory=client;result=native(c).json()
    assert c.post('/api/v1/auth/logout',headers={'X-CSRF-Token':result['csrf_token']}).status_code==200
    assert not c.cookies.get('wcp_session') and not c.cookies.get('wcp_refresh')
    with factory() as db:assert db.scalar(select(identity.MobileSession)) is None
