from __future__ import annotations

import hashlib, hmac, secrets, time
from datetime import timedelta, datetime
from typing import Any
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError, InvalidHashError, VerificationError
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, JSON, String, UniqueConstraint, select, delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Mapped, mapped_column, Session
from server.core import settings
from server.db import Base, SessionLocal, aware, engine, get_db, uid, utcnow

router=APIRouter(prefix='/auth',tags=['identity'])
ph=PasswordHasher(time_cost=3,memory_cost=65536,parallelism=2)
DUMMY_PASSWORD_HASH=ph.hash('unavailable-account-placeholder')
COOKIE='wcp_session'


class Workspace(Base):
    __tablename__='workspaces'
    id: Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    name: Mapped[str]=mapped_column(String(160))
    audit_sequence: Mapped[int]=mapped_column(Integer,default=0)
    audit_head: Mapped[str]=mapped_column(String(64),default='0'*64)
    created_at: Mapped[datetime]=mapped_column(DateTime(timezone=True),default=utcnow)


class User(Base):
    __tablename__='users'
    id: Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    email: Mapped[str]=mapped_column(String(254),unique=True,index=True)
    display_name: Mapped[str]=mapped_column(String(160))
    password_hash: Mapped[str]=mapped_column(String(256))
    workspace_id: Mapped[str]=mapped_column(ForeignKey('workspaces.id'),index=True)
    is_platform_admin: Mapped[bool]=mapped_column(Boolean,default=False)
    is_active: Mapped[bool]=mapped_column(Boolean,default=True)
    profile: Mapped[dict]=mapped_column(JSON,default=dict)
    created_at: Mapped[datetime]=mapped_column(DateTime(timezone=True),default=utcnow)


class Membership(Base):
    __tablename__='memberships'
    __table_args__=(UniqueConstraint('workspace_id','user_id'),)
    id: Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    workspace_id: Mapped[str]=mapped_column(ForeignKey('workspaces.id'),index=True)
    user_id: Mapped[str]=mapped_column(ForeignKey('users.id'),index=True)
    role: Mapped[str]=mapped_column(String(24),default='clinician')


class LoginSession(Base):
    __tablename__='login_sessions'
    id: Mapped[str]=mapped_column(String(64),primary_key=True)
    user_id: Mapped[str]=mapped_column(ForeignKey('users.id'),index=True)
    workspace_id: Mapped[str]=mapped_column(ForeignKey('workspaces.id'))
    expires_at: Mapped[datetime]=mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime]=mapped_column(DateTime(timezone=True),default=utcnow)


class MobileSession(Base):
    __tablename__='mobile_sessions'
    id: Mapped[str]=mapped_column(String(64),primary_key=True)
    access_id: Mapped[str]=mapped_column(String(64),index=True)
    user_id: Mapped[str]=mapped_column(ForeignKey('users.id'),index=True)
    password_version: Mapped[str]=mapped_column(String(64))
    expires_at: Mapped[datetime]=mapped_column(DateTime(timezone=True))


class LegalAcceptance(Base):
    __tablename__='legal_acceptances'
    __table_args__=(UniqueConstraint('user_id','terms_version','privacy_version'),)
    id: Mapped[str]=mapped_column(String(36),primary_key=True,default=uid)
    user_id: Mapped[str]=mapped_column(ForeignKey('users.id'),index=True)
    terms_version: Mapped[str]=mapped_column(String(40))
    privacy_version: Mapped[str]=mapped_column(String(40))
    accepted_at: Mapped[datetime]=mapped_column(DateTime(timezone=True),default=utcnow)


class RateWindow(Base):
    __tablename__='rate_windows'
    key: Mapped[str]=mapped_column(String(96),primary_key=True)
    hits: Mapped[int]=mapped_column(Integer,default=1)
    expires: Mapped[int]=mapped_column(Integer,index=True)


def check_rate(key: str,limit=60,window_seconds=60):
    now=int(time.time());bucket=now//window_seconds
    name=hashlib.sha256(key.encode()).hexdigest()+':'+str(bucket)
    if engine.dialect.name=='postgresql':
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    with SessionLocal() as db:
        stmt=insert(RateWindow).values(key=name,hits=1,expires=now+window_seconds*2)
        stmt=stmt.on_conflict_do_update(index_elements=['key'],set_={'hits':RateWindow.hits+1}).returning(RateWindow.hits)
        hits=db.execute(stmt).scalar_one()
        if secrets.randbelow(100)==0: db.execute(delete(RateWindow).where(RateWindow.expires<now))
        db.commit()
    if hits>limit: raise HTTPException(429,'Too many requests. Please try again shortly.',headers={'Retry-After':str(window_seconds)})


def digest(token): return hashlib.sha256(token.encode()).hexdigest()
def csrf(token): return hmac.new(settings.app_secret.encode(),('csrf:'+token).encode(),hashlib.sha256).hexdigest()
def user_out(user): return {k:getattr(user,k) for k in ('id','email','display_name','workspace_id','is_platform_admin','profile')}


def _session_user(request:Request,db:Session):
    token=request.cookies.get(COOKIE,'')
    session=db.get(LoginSession,digest(token)) if token else None
    if not session or aware(session.expires_at)<=utcnow(): raise HTTPException(401,'Sign in to continue.')
    user=db.get(User,session.user_id)
    membership=db.scalar(select(Membership).where(Membership.user_id==session.user_id,Membership.workspace_id==session.workspace_id))
    if not user or not user.is_active or user.workspace_id!=session.workspace_id or not membership:
        raise HTTPException(401,'This session is no longer active.')
    mobile=db.scalar(select(MobileSession).where(MobileSession.access_id==session.id))
    if mobile and (mobile.password_version!=digest(user.password_hash) or aware(mobile.expires_at)<=utcnow()):
        raise HTTPException(401,'This session is no longer active.')
    return user


def current_user(request:Request,db:Session=Depends(get_db)):
    user=_session_user(request,db)
    if not request.url.path.startswith(('/api/v1/diagnostics','/api/v1/admin')):
        from .diagnostics import bind
        bind(user)
    exempt=request.url.path.endswith(('/auth/session','/auth/logout','/auth/accept-legal'))
    if not exempt:
        accepted=db.scalar(select(LegalAcceptance.id).where(LegalAcceptance.user_id==user.id,LegalAcceptance.terms_version==settings.legal_version,LegalAcceptance.privacy_version==settings.legal_version))
        if not accepted: raise HTTPException(428,'Review and accept the current Terms and Privacy Policy.')
    return user


def require_csrf(request:Request,user:User=Depends(current_user)):
    received=request.headers.get('X-CSRF-Token','')
    expected=csrf(request.cookies.get(COOKIE,''))
    if not received or not received.isascii() or not hmac.compare_digest(received,expected): raise HTTPException(403,'Invalid request verification. Refresh and try again.')
    origin=request.headers.get('origin')
    if origin and origin.rstrip('/')!=settings.public_base_url.rstrip('/'):
        raise HTTPException(403,'Request origin is not permitted.')
    return user


def require_admin(user:User=Depends(current_user)):
    if not user.is_platform_admin: raise HTTPException(403,'Owner access is required.')
    return user


class Strict(BaseModel): model_config=ConfigDict(extra='forbid')
class Signup(Strict):
    email:str=Field(min_length=3,max_length=254)
    password:str=Field(min_length=12,max_length=128)
    display_name:str=Field(min_length=1,max_length=160)
    workspace_name:str=Field(min_length=1,max_length=160)
    accept_terms:bool
    terms_version:str
    privacy_version:str
    @field_validator('email')
    @classmethod
    def email_valid(cls,value):
        import re
        v=value.strip().lower()
        if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',v):raise ValueError('Enter a valid email address')
        return v
class Login(Strict):
    email:str=Field(max_length=254)
    password:str=Field(max_length=128)
class ProfilePatch(Strict):
    display_name:str|None=Field(None,min_length=1,max_length=160)
    profile:dict[str,Any]|None=None
class LegalInput(Strict):
    accept_terms:bool
    terms_version:str
    privacy_version:str


def issue_session(db,user,response,native=False):
    token=secrets.token_urlsafe(48)
    db.add(LoginSession(id=digest(token),user_id=user.id,workspace_id=user.workspace_id,expires_at=utcnow()+timedelta(hours=settings.session_hours)))
    response.set_cookie(COOKIE,token,max_age=settings.session_hours*3600,httponly=True,secure=settings.secure_cookies,samesite='strict',path='/')
    if native:
        refresh=secrets.token_urlsafe(48)
        db.add(MobileSession(id=digest(refresh),access_id=digest(token),user_id=user.id,
            password_version=digest(user.password_hash),expires_at=utcnow()+timedelta(days=90)))
        response.set_cookie('wcp_refresh',refresh,max_age=90*86400,httponly=True,secure=settings.secure_cookies,samesite='strict',path='/api/v1/auth')
    return {'user':user_out(user),'csrf_token':csrf(token)}


@router.post('/signup',status_code=201)
def signup(payload:Signup,request:Request,response:Response,db:Session=Depends(get_db)):
    if not settings.allow_signup: raise HTTPException(403,'Signup is currently closed.')
    check_rate('signup:'+str(request.client.host),5,3600)
    if not payload.accept_terms or payload.terms_version!=settings.legal_version or payload.privacy_version!=settings.legal_version:
        raise HTTPException(422,'Accept the current Terms and Privacy Policy to create an account.')
    workspace=Workspace(name=payload.workspace_name.strip());db.add(workspace);db.flush()
    user=User(email=payload.email,display_name=payload.display_name.strip(),password_hash=ph.hash(payload.password),workspace_id=workspace.id,is_platform_admin=False)
    try:
        db.add(user);db.flush()
        db.add(Membership(user_id=user.id,workspace_id=workspace.id,role='owner'))
        db.add(LegalAcceptance(user_id=user.id,terms_version=payload.terms_version,privacy_version=payload.privacy_version))
        result=issue_session(db,user,response,native=request.headers.get('X-WCP-Client')=='android')
        from server.audit import write_audit
        write_audit(db,user,'account.created',user.id)
        db.commit()
    except IntegrityError:
        db.rollback();raise HTTPException(409,'An account with this email already exists. Sign in instead.')
    return result


@router.post('/login')
def login(payload:Login,request:Request,response:Response,db:Session=Depends(get_db)):
    check_rate('login:'+str(request.client.host),30,300)
    check_rate('login-account:'+payload.email.lower().strip(),10,300)
    user=db.scalar(select(User).where(User.email==payload.email.lower().strip()))
    # Equal password verification cost for missing users limits account enumeration timing.
    candidate=user.password_hash if user else DUMMY_PASSWORD_HASH
    try: valid=ph.verify(candidate,payload.password)
    except (VerifyMismatchError,InvalidHashError,VerificationError):valid=False
    if not user or not user.is_active or not valid:raise HTTPException(401,'Email or password was not recognized.')
    result=issue_session(db,user,response,native=request.headers.get('X-WCP-Client')=='android')
    from server.audit import write_audit
    write_audit(db,user,'session.created',user.id);db.commit()
    return result


@router.get('/session')
def session_info(request:Request,response:Response,user:User=Depends(current_user),db:Session=Depends(get_db)):
    acceptance=db.scalar(select(LegalAcceptance.id).where(LegalAcceptance.user_id==user.id,LegalAcceptance.terms_version==settings.legal_version,LegalAcceptance.privacy_version==settings.legal_version))
    access_id=digest(request.cookies[COOKIE])
    if request.headers.get('X-WCP-Client')=='android' and not db.scalar(select(MobileSession.id).where(MobileSession.access_id==access_id)):
        token=secrets.token_urlsafe(48)
        db.add(MobileSession(id=digest(token),access_id=access_id,user_id=user.id,password_version=digest(user.password_hash),expires_at=utcnow()+timedelta(days=90)))
        db.commit()
        response.set_cookie('wcp_refresh',token,max_age=90*86400,httponly=True,secure=settings.secure_cookies,samesite='strict',path='/api/v1/auth')
    return {'user':user_out(user),'csrf_token':csrf(request.cookies[COOKIE]),'requires_legal_acceptance':not bool(acceptance)}


@router.post('/refresh')
def refresh_mobile(request:Request,response:Response,db:Session=Depends(get_db)):
    # A native-only, origin-bound exchange. Neither an expired access cookie nor
    # a refresh token alone is enough to revive a revoked or changed account.
    origin=request.headers.get('origin')
    if request.headers.get('X-WCP-Client')!='android' or (origin and origin.rstrip('/')!=settings.public_base_url.rstrip('/')):
        raise HTTPException(403,'Request origin is not permitted.')
    refresh=request.cookies.get('wcp_refresh','')
    mobile=db.scalar(select(MobileSession).where(MobileSession.id==digest(refresh)).with_for_update()) if refresh else None
    access=db.get(LoginSession,mobile.access_id) if mobile else None
    user=db.get(User,mobile.user_id) if mobile else None
    membership=db.scalar(select(Membership.id).where(Membership.user_id==user.id,Membership.workspace_id==user.workspace_id)) if user else None
    if not mobile or not access or not user or not user.is_active or not membership or access.workspace_id!=user.workspace_id or mobile.password_version!=digest(user.password_hash) or aware(mobile.expires_at)<=utcnow():
        raise HTTPException(401,'Sign in to continue.')
    # Keep the opaque access token stable: a lost refresh response cannot strand
    # the device or cause parallel uploads to invalidate one another.
    token=request.cookies.get(COOKIE,'')
    if not token or digest(token)!=mobile.access_id:
        raise HTTPException(401,'Sign in to continue.')
    access.expires_at=utcnow()+timedelta(hours=settings.session_hours)
    mobile.expires_at=utcnow()+timedelta(days=90)
    db.commit()
    response.set_cookie(COOKIE,token,max_age=settings.session_hours*3600,httponly=True,secure=settings.secure_cookies,samesite='strict',path='/')
    return {'user':user_out(user),'csrf_token':csrf(token)}


@router.post('/logout',dependencies=[Depends(require_csrf)])
def logout(request:Request,response:Response,db:Session=Depends(get_db),user:User=Depends(current_user)):
    db.execute(delete(MobileSession).where(MobileSession.access_id==digest(request.cookies.get(COOKIE,''))))
    db.execute(delete(LoginSession).where(LoginSession.id==digest(request.cookies.get(COOKIE,''))))
    from server.audit import write_audit
    write_audit(db,user,'session.revoked',user.id);db.commit();response.delete_cookie(COOKIE,path='/')
    response.delete_cookie('wcp_refresh',path='/api/v1/auth')
    return {'status':'signed_out'}


@router.patch('/profile',dependencies=[Depends(require_csrf)])
def update_profile(payload:ProfilePatch,user:User=Depends(current_user),db:Session=Depends(get_db)):
    if payload.display_name is not None:user.display_name=payload.display_name.strip()
    if payload.profile is not None:
        allowed={'profession','care_setting','theme','review_mode','onboarding_complete','recovery_enabled'}
        if set(payload.profile)-allowed:raise HTTPException(422,'Unknown profile setting.')
        if len(str(payload.profile))>4000:raise HTTPException(422,'Profile settings are too large.')
        user.profile={**user.profile,**payload.profile}
    from server.audit import write_audit
    write_audit(db,user,'profile.updated',user.id);db.commit();return user_out(user)


@router.post('/accept-legal',dependencies=[Depends(require_csrf)])
def accept_legal(payload:LegalInput,user:User=Depends(current_user),db:Session=Depends(get_db)):
    if not payload.accept_terms or payload.terms_version!=settings.legal_version or payload.privacy_version!=settings.legal_version:raise HTTPException(422,'Accept the current policy versions.')
    existing=db.scalar(select(LegalAcceptance.id).where(LegalAcceptance.user_id==user.id,LegalAcceptance.terms_version==payload.terms_version,LegalAcceptance.privacy_version==payload.privacy_version))
    if not existing:db.add(LegalAcceptance(user_id=user.id,terms_version=payload.terms_version,privacy_version=payload.privacy_version))
    db.commit();return {'status':'accepted'}


def bootstrap_owner(email,password,workspace_name='WoundCoPilot Owner'):
    if len(password)<16:raise ValueError('Owner password must have at least 16 characters')
    with SessionLocal() as db:
        if db.scalar(select(User.id).where(User.email==email.lower())):raise ValueError('Existing accounts cannot be promoted by bootstrap')
        workspace=Workspace(name=workspace_name);db.add(workspace);db.flush()
        user=User(email=email.lower(),display_name='WoundCoPilot Owner',password_hash=ph.hash(password),workspace_id=workspace.id,is_platform_admin=True)
        db.add(user);db.flush();db.add(Membership(user_id=user.id,workspace_id=workspace.id,role='owner'))
        # Bootstrap account still accepts policies on first login; no fabricated owner acceptance.
        from server.audit import write_audit
        write_audit(db,user,'owner.bootstrapped',user.id);db.commit();return user.id
