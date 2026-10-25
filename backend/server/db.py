from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4
from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, sessionmaker
from server.core import settings


def uid() -> str: return str(uuid4())
def utcnow() -> datetime: return datetime.now(timezone.utc)
def aware(value: datetime) -> datetime: return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


class Base(DeclarativeBase): pass

if settings.database_url.startswith('sqlite:///'):
    path=settings.database_url.removeprefix('sqlite:///')
    if path != ':memory:': Path(path).parent.mkdir(parents=True, exist_ok=True)
kwargs={'connect_args':{'check_same_thread':False,'timeout':30}} if settings.database_url.startswith('sqlite') else {'pool_size':5,'max_overflow':5,'pool_timeout':10,'connect_args':{'connect_timeout':10,'options':'-c statement_timeout=30000'}}
engine=create_engine(settings.database_url,pool_pre_ping=True,**kwargs)
if engine.dialect.name=='sqlite':
    @event.listens_for(engine,'connect')
    def configure_sqlite(connection, _):
        cursor=connection.cursor()
        cursor.execute('PRAGMA foreign_keys=ON')
        cursor.execute('PRAGMA journal_mode=WAL')
        cursor.execute('PRAGMA busy_timeout=30000')
        cursor.close()

SessionLocal=sessionmaker(bind=engine,expire_on_commit=False,autoflush=False)


def get_db():
    with SessionLocal() as db:
        try: yield db
        finally: db.rollback()
