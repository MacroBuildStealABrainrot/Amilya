from datetime import timedelta
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select, update
from sqlalchemy.orm import sessionmaker
from server import identity, audit, main
from server.core import settings
from server.db import Base, get_db, utcnow

@pytest.fixture
def client(tmp_path, monkeypatch):
    engine = create_engine('sqlite:///' + str(tmp_path/'identity.db'), connect_args={'check_same_thread':False})
    Base.metadata.create_all(engine)
    factory = sessionmaker(engine, expire_on_commit=False)
    def session():
        with factory() as db: yield db
    main.app.dependency_overrides[get_db] = session
    monkeypatch.setattr(identity,'SessionLocal',factory)
    monkeypatch.setattr(settings,'app_secret','synthetic-test-secret-with-thirty-two-characters')
    monkeypatch.setattr(settings,'public_base_url','http://testserver')
    monkeypatch.setattr(settings,'allow_signup',True)
    monkeypatch.setattr(settings,'legal_version','2026-09-05')
    with TestClient(main.app) as c: yield c, factory
    main.app.dependency_overrides.clear(); engine.dispose()

def signup(client, **changes):
    payload={'email':'synthetic@example.test','password':'Synthetic-test-password','display_name':'Synthetic user','workspace_name':'Synthetic workspace',
        'accept_terms':True,'terms_version':'2026-09-05','privacy_version':'2026-09-05'}
    return client.post('/api/v1/auth/signup',json={**payload,**changes})

@pytest.mark.parametrize('key',['', '   ', 'a'*129, 'a'*160+'one', 'a'*160+'two'])
def test_invalid_idempotency_key_rejected_before_endpoint(client,key):
    c,_=client
    response=c.get('/api/v1/config',headers={'Idempotency-Key':key})
    assert response.status_code==422
    assert response.headers['Cache-Control']=='no-store'

def test_signup_cannot_self_promote_and_requires_current_terms(client):
    c,_=client
    assert signup(c,is_platform_admin=True).status_code==422
    assert signup(c,accept_terms=False).status_code==422
    assert signup(c,terms_version='old').status_code==422
    response=signup(c); assert response.status_code==201
    assert response.json()['user']['is_platform_admin'] is False
    assert c.get('/api/v1/admin/overview').status_code==403
    cookie=response.headers['set-cookie'].lower()
    assert 'httponly' in cookie and 'samesite=strict' in cookie

def test_csrf_origin_binding_and_revoked_session(client):
    c,_=client; response=signup(c); token=response.json()['csrf_token']
    assert c.patch('/api/v1/auth/profile',json={'display_name':'Other'}).status_code==403
    assert c.patch('/api/v1/auth/profile',json={'display_name':'Other'},headers={'X-CSRF-Token':token,'Origin':'https://attacker.example'}).status_code==403
    assert c.patch('/api/v1/auth/profile',json={'display_name':'Other'},headers={'X-CSRF-Token':token,'Origin':'http://testserver'}).status_code==200
    old=c.cookies.get(identity.COOKIE)
    assert c.post('/api/v1/auth/logout',headers={'X-CSRF-Token':token}).status_code==200
    c.cookies.set(identity.COOKIE,old)
    assert c.get('/api/v1/auth/session').status_code==401

def test_reconsent_blocks_clinical_access_but_allows_consent(client,monkeypatch):
    c,_=client; response=signup(c);token=response.json()['csrf_token']
    monkeypatch.setattr(settings,'legal_version','next-policy')
    assert c.get('/api/v1/patients').status_code==428
    assert c.get('/api/v1/auth/session').json()['requires_legal_acceptance'] is True
    result=c.post('/api/v1/auth/accept-legal',json={'terms_version':'next-policy','privacy_version':'next-policy','accept_terms':True},headers={'X-CSRF-Token':token})
    assert result.status_code==200
    assert c.get('/api/v1/patients').status_code==200

def test_expiry_and_membership_are_enforced(client):
    c,factory=client; signup(c)
    with factory() as db:
        db.execute(update(identity.LoginSession).values(expires_at=utcnow()-timedelta(seconds=1)));db.commit()
    assert c.get('/api/v1/auth/session').status_code==401
    assert c.post('/api/v1/auth/login',json={'email':'synthetic@example.test','password':'wrong'}).status_code==401
    assert c.post('/api/v1/auth/login',json={'email':'synthetic@example.test','password':'Synthetic-test-password'}).status_code==200
    with factory() as db:
        membership=db.scalar(select(identity.Membership));db.delete(membership);db.commit()
    assert c.get('/api/v1/auth/session').status_code==401

def test_audit_detects_metadata_timestamp_and_identity_tampering(client):
    c,factory=client; result=signup(c); workspace=result.json()['user']['workspace_id']
    with factory() as db:
        assert audit.verify_chain(db,workspace)['valid']
        event=db.scalar(select(audit.AuditEvent));event.created_at=event.created_at+timedelta(seconds=1);db.commit()
        assert not audit.verify_chain(db,workspace)['valid']

def test_security_headers_on_normal_and_oversize_response(client):
    c,_=client
    for response in [c.get('/api/v1/config'),c.post('/api/v1/contact',content=b'x',headers={'Content-Length':str(40*1024**2)})]:
        assert response.headers['x-content-type-options']=='nosniff'
        assert response.headers['cache-control']=='no-store'
        assert "frame-ancestors 'none'" in response.headers['content-security-policy']
    assert response.status_code==413
