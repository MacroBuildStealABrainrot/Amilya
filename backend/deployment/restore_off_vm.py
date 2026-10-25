"""COS-native isolated restore drill using only Python's standard library and existing PG image.

The recovery private key stays on the operator's computer; this consumes the privately
staged decrypted dump. Output contains check results, never clinical text or credentials.
"""
import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

TARGET = 'wcp_offvm_restore_20260905'
PREFIX = ['docker', 'exec', '--user', 'postgres', 'wcp-postgres']


def command(args, **kwargs):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, **kwargs)
    if result.returncode:
        raise RuntimeError('Restore command failed; inspect privately without publishing clinical content')
    return result.stdout


def query(sql):
    raw = command(PREFIX + ['psql', '--no-psqlrc', '-U', 'wcp_owner', '-d', TARGET, '-Atc', sql])
    return json.loads(raw)


def restore(dump, identifiers, expected_migration):
    if not str(dump.resolve()).startswith('/tmp/wcp-offvm-restore-20260905/'):
        raise ValueError('Only the dedicated private staging directory is accepted')
    with dump.open('rb') as source:
        assert source.read(5) == b'PGDMP', 'Not a PostgreSQL custom archive'
    qa = json.loads(identifiers.read_text())
    keys = {k: str(UUID(qa[k])) for k in ('workspace_id', 'encounter_id', 'original_document_id', 'amendment_id')}
    command(PREFIX + ['createdb', '-U', 'wcp_owner', TARGET])
    try:
        with dump.open('rb') as source:
            command(['docker', 'exec', '-i', '--user', 'postgres', 'wcp-postgres', 'pg_restore',
                     '-U', 'wcp_owner', '--exit-on-error', '-d', TARGET], stdin=source)
        migration = query('SELECT to_json(version_num) FROM alembic_version')
        assert migration == expected_migration
        documents = []
        for document_id in (keys['original_document_id'], keys['amendment_id']):
            row = query(f"SELECT row_to_json(d) FROM (SELECT d.id,d.text,d.content_hash,d.amends_id,s.content_hash AS signed_hash FROM clinical_documents d LEFT JOIN document_signatures s ON s.document_id=d.id WHERE d.id='{document_id}' AND d.workspace_id='{keys['workspace_id']}') d")
            actual = hashlib.sha256(row.pop('text').encode()).hexdigest()
            assert actual == row['content_hash']
            if row['signed_hash'] is not None:
                assert actual == row['signed_hash']
            documents.append(row)
        assert documents[0]['signed_hash'] is not None
        assert documents[1]['amends_id'] == documents[0]['id']
        previous, sequence = '0' * 64, 0
        events = query(f"SELECT COALESCE(json_agg(a ORDER BY sequence),'[]'::json) FROM audit_events a WHERE workspace_id='{keys['workspace_id']}'")
        for event in events:
            record = {k: event[k] for k in ('workspace_id', 'user_id', 'sequence', 'action', 'resource_id', 'detail', 'previous_hash')}
            assert event['hash_version'] in (1, 2)
            if event['hash_version'] == 2:
                timestamp = datetime.fromisoformat(event['created_at']).astimezone(timezone.utc)
                record.update(id=event['id'], hash_version=2, created_at=timestamp.isoformat(timespec='microseconds'))
            calculated = hashlib.sha256(json.dumps(record, sort_keys=True, separators=(',', ':'), default=str).encode()).hexdigest()
            assert event['sequence'] == sequence+1 and event['previous_hash'] == previous and calculated == event['event_hash']
            previous, sequence = event['event_hash'], event['sequence']
        head = query(f"SELECT row_to_json(w) FROM (SELECT audit_head,audit_sequence FROM workspaces WHERE id='{keys['workspace_id']}') w")
        assert head == {'audit_head': previous, 'audit_sequence': sequence}
        return {'restored_migration': migration, 'exact_text_hashes_valid': True,
                'original_signature_valid': True, 'amendment_link_valid': True,
                'audit_chain_valid': True, 'audit_events': sequence,
                'restored_public_tables': query("SELECT to_json(count(*)) FROM pg_tables WHERE schemaname='public'")}
    finally:
        # This target was created successfully by this exact invocation; source DB is untouched.
        command(PREFIX + ['dropdb', '-U', 'wcp_owner', TARGET])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dump', type=Path, required=True)
    parser.add_argument('--identifiers', type=Path, required=True)
    parser.add_argument('--expected-migration', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = restore(args.dump, args.identifiers, args.expected_migration)
    result['isolated_restore_database_removed'] = True
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
