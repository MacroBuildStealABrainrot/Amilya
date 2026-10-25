"""Read-only TLS checks through the task-private loopback operator tunnel."""
import argparse
import json
from pathlib import Path
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DBAPIError
from harden_runtime import IMMUTABLE_TABLES


def probe(url_file: Path, expected_user='wcp_owner'):
    url = make_url(url_file.read_text(encoding='utf-8').strip())
    if url.query.get('hostaddr') != '127.0.0.1' or url.port != 15432:
        raise ValueError('This check is limited to the configured loopback operator tunnel')
    results = {}
    engine = create_engine(url, hide_parameters=True)
    try:
        with engine.connect() as db:
            results['connection'] = dict(db.execute(text('SELECT current_user, inet_client_addr()::text AS client_address, ssl, version AS tls_version FROM pg_stat_ssl WHERE pid=pg_backend_pid()')).mappings().one())
            assert results['connection']['ssl'] is True
            assert results['connection']['current_user'] == expected_user
            if expected_user == 'wcp_app':
                results['runtime_privileges'] = dict(db.execute(text("SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, has_schema_privilege(current_user,'public','CREATE') AS can_create_schema_objects FROM pg_roles WHERE rolname=current_user")).mappings().one())
                assert not any(results['runtime_privileges'].values())
                tables = db.execute(text("SELECT tablename, has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'SELECT') AS read, has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'INSERT') AS append, has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'UPDATE') AS update, has_table_privilege(current_user,quote_ident(schemaname)||'.'||quote_ident(tablename),'DELETE') AS delete FROM pg_tables WHERE schemaname='public'")).mappings().all()
                for table in tables:
                    assert table['read'] and table['append'], table['tablename']
                    expected_mutable = table['tablename'] not in IMMUTABLE_TABLES
                    assert table['update'] == expected_mutable and table['delete'] == expected_mutable, table['tablename']
                results['table_privilege_policy_verified'] = True
    finally: engine.dispose()
    for name, (target, expected_error) in {
        'plaintext_rejected': (url.update_query_dict({'sslmode':'disable'}), 'no encryption'),
        'wrong_certificate_identity_rejected': (url.set(host='10.30.0.11'), 'certificate'),
        'wrong_password_rejected': (url.set(password='synthetic-invalid-password'), 'password authentication failed'),
    }.items():
        engine = create_engine(target, hide_parameters=True)
        try:
            with engine.connect() as db: db.execute(text('SELECT 1'))
            results[name] = False
        except DBAPIError as exc: results[name] = expected_error in str(exc.orig).lower()
        finally: engine.dispose()
        assert results[name], name
    print(json.dumps(results, indent=2))
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url-file', type=Path, required=True)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--expected-user', choices=['wcp_owner','wcp_app'], default='wcp_owner')
    args = parser.parse_args()
    try:
        result = probe(args.url_file, args.expected_user)
    except DBAPIError as exc:
        raise SystemExit(f'Database probe failed: {type(exc.orig).__name__}; inspect connection/configuration privately') from None
    if args.out: args.out.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
