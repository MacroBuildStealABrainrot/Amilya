"""Apply and verify PostgreSQL append-only runtime permissions through the private tunnel.

All attempted application-role writes are rolled back, including an unexpected success.
The owner retains migration/recovery privileges. No clinical content is printed.
"""
import argparse
import hashlib
import json
from datetime import timezone
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DBAPIError

IMMUTABLE_TABLES = (
    'clinical_evidence', 'fact_revisions', 'clinical_documents',
    'document_signatures', 'clinical_reviews', 'clinical_timeline',
    'audit_events', 'credit_ledger',
)


def engine_for(path):
    url = make_url(path.read_text(encoding='utf-8').strip())
    if url.query.get('hostaddr') != '127.0.0.1' or url.port != 15432:
        raise ValueError('Only the configured private operator tunnel is permitted')
    return create_engine(url, hide_parameters=True, connect_args={'connect_timeout': 10})


def verify_audit(db, workspace_id):
    previous = '0' * 64
    sequence = 0
    for row in db.execute(text('SELECT * FROM audit_events WHERE workspace_id=:workspace ORDER BY sequence'), {'workspace': workspace_id}).mappings():
        record = {k: row[k] for k in ('workspace_id', 'user_id', 'sequence', 'action', 'resource_id', 'detail', 'previous_hash')}
        assert row['hash_version'] in (1, 2), 'Unknown audit hash version'
        if row['hash_version'] == 2:
            created = row['created_at']
            created = created.replace(tzinfo=timezone.utc) if created.tzinfo is None else created.astimezone(timezone.utc)
            record.update(id=row['id'], hash_version=2, created_at=created.isoformat(timespec='microseconds'))
        calculated = hashlib.sha256(json.dumps(record, sort_keys=True, separators=(',', ':'), default=str).encode()).hexdigest()
        assert row['sequence'] == sequence + 1 and row['previous_hash'] == previous and calculated == row['event_hash'], 'Audit chain mismatch'
        previous, sequence = row['event_hash'], row['sequence']
    head = db.execute(text('SELECT audit_head,audit_sequence FROM workspaces WHERE id=:workspace'), {'workspace': workspace_id}).one()
    assert head.audit_head == previous and head.audit_sequence == sequence
    return {'valid': True, 'events': sequence, 'head': previous}


def run(owner_file, runtime_file, qa_file, apply=False):
    # The input may also contain login credentials; retain only the nonsecret workspace ID.
    workspace_id = json.loads(qa_file.read_text(encoding='utf-8'))['workspace_id']
    owner, runtime = engine_for(owner_file), engine_for(runtime_file)
    result = {'append_only_tables': list(IMMUTABLE_TABLES), 'denied_operations': {}}
    try:
        with owner.begin() as db:
            assert db.scalar(text('SELECT current_user')) == 'wcp_owner'
            if apply:
                for table in IMMUTABLE_TABLES:
                    db.execute(text(f'REVOKE UPDATE, DELETE ON TABLE public.{table} FROM wcp_app'))
        with runtime.connect() as db:
            assert db.scalar(text('SELECT current_user')) == 'wcp_app'
            for table in IMMUTABLE_TABLES:
                privileges = db.execute(text("SELECT has_table_privilege(current_user,:table,'SELECT') AS read, has_table_privilege(current_user,:table,'INSERT') AS append, has_table_privilege(current_user,:table,'UPDATE') AS update, has_table_privilege(current_user,:table,'DELETE') AS delete, has_table_privilege(current_user,:table,'TRUNCATE') AS truncate"), {'table': 'public.' + table}).mappings().one()
                assert privileges['read'] and privileges['append'] and not any(privileges[k] for k in ('update', 'delete', 'truncate'))
            result['audit_chain'] = verify_audit(db, workspace_id)
            db.rollback()
            for table in IMMUTABLE_TABLES:
                result['denied_operations'][table] = []
                for operation, sql in (
                    ('UPDATE', f'UPDATE public.{table} SET id=id WHERE id IN (SELECT id FROM public.{table} LIMIT 1)'),
                    ('DELETE', f'DELETE FROM public.{table} WHERE id IN (SELECT id FROM public.{table} LIMIT 1)'),
                ):
                    denied = False
                    transaction = db.begin()
                    try:
                        db.execute(text(sql))
                    except DBAPIError as exc:
                        denied = getattr(exc.orig, 'sqlstate', None) == '42501'
                    finally:
                        transaction.rollback()
                    assert denied, f'{table} {operation} was not rejected by PostgreSQL privilege enforcement'
                    result['denied_operations'][table].append(operation)
        return result
    finally:
        owner.dispose()
        runtime.dispose()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner-url-file', type=Path, required=True)
    parser.add_argument('--runtime-url-file', type=Path, required=True)
    parser.add_argument('--qa-file', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.owner_url_file, args.runtime_url_file, args.qa_file, args.apply)
    except DBAPIError as exc:
        raise SystemExit(f'Database hardening probe failed: {type(exc.orig).__name__}; inspect connection/configuration privately') from None
    args.out.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
