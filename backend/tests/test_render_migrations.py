"""Managed test databases must not require the Cloud Run-specific wcp_app role."""
import importlib.util
from pathlib import Path
import pytest
import os
import subprocess
import sys
from uuid import uuid4

VERSIONS = Path(__file__).parents[1] / 'migrations/versions'

def load(name):
    spec = importlib.util.spec_from_file_location(name, VERSIONS / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

@pytest.mark.parametrize('name,function', [('0006_scoped_privacy_erasure.py','erasure_sql'), ('0007_targeted_transfer_revocation.py','scoped_sql')])
def test_role_is_configurable_and_default_preserved(monkeypatch,name,function):
    module=load(name)
    monkeypatch.delenv('WCP_DB_RUNTIME_ROLE',raising=False)
    assert 'TO "wcp_app";' in getattr(module,function)()
    monkeypatch.setenv('WCP_DB_RUNTIME_ROLE','amilya_repair_test_db_user')
    sql=getattr(module,function)()
    assert 'TO "amilya_repair_test_db_user";' in sql
    assert 'FROM PUBLIC;' in sql
    assert 'TO wcp_app;' not in sql
    if name.startswith('0007'):
        assert "current_setting('wcp.erasure_transfers',true)" in sql

@pytest.mark.parametrize('role',['', 'public; DROP ROLE test', 'role"injection', 'x'*64])
def test_invalid_roles_are_rejected(monkeypatch,role):
    monkeypatch.setenv('WCP_DB_RUNTIME_ROLE',role)
    with pytest.raises(ValueError,match='Invalid runtime database role'):
        load('0006_scoped_privacy_erasure.py').erasure_sql()

def test_fresh_postgres_migrates_without_cloud_run_role():
    url=os.environ.get('WCP_TEST_POSTGRES_URL')
    if not url:
        pytest.skip('Dedicated CI Postgres is not available locally')
    import psycopg
    from psycopg import sql
    from sqlalchemy.engine import make_url
    parsed=make_url(url)
    # This regression creates and removes only a uniquely named database on the
    # local CI container. Never run it against Render or another remote server.
    assert parsed.host in ('127.0.0.1','localhost')
    name='wcp_render_migration_'+uuid4().hex[:12]
    base=parsed.set(drivername='postgresql').render_as_string(hide_password=False)
    with psycopg.connect(base,autocommit=True) as conn:
        conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
        try:
            env=os.environ.copy()
            env.update(DATABASE_URL=parsed.set(database=name).render_as_string(hide_password=False),
                       WCP_DB_RUNTIME_ROLE=parsed.username,ENVIRONMENT='development',
                       WOUNDCOPILOT_ENV_FILE='/tmp/no-amilya-ci-env')
            result=subprocess.run([sys.executable,'-m','alembic','upgrade','head'],
                cwd=Path(__file__).parents[1],env=env,capture_output=True,timeout=120)
            assert result.returncode==0, 'Fresh managed-role database migration failed'
            target=parsed.set(drivername='postgresql',database=name).render_as_string(hide_password=False)
            with psycopg.connect(target) as check:
                assert check.execute('SELECT version_num FROM alembic_version').fetchone()==('0009',)
                assert check.execute("SELECT has_function_privilege(current_user,'public.wcp_erase_work(text,text,text,text)','EXECUTE')").fetchone()==(True,)
        finally:
            conn.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(name)))
