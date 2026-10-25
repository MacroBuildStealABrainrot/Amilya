"""Verify the synthetic signed workflow without displaying note text or credentials."""
import argparse
import hashlib
import json
from pathlib import Path
from sqlalchemy import create_engine, text


def snapshot(url_file, qa_file):
    qa = json.loads(qa_file.read_text(encoding='utf-8'))
    # The QA file also contains test login material; only these four identifiers are used.
    keys = {key: qa[key] for key in ('workspace_id', 'encounter_id', 'original_document_id', 'amendment_id')}
    engine = create_engine(url_file.read_text(encoding='utf-8').strip(), hide_parameters=True)
    try:
        with engine.connect() as db:
            encounter = dict(db.execute(text('SELECT id,status,revision FROM encounters WHERE id=:encounter_id AND workspace_id=:workspace_id'), keys).mappings().one())
            documents = []
            for document_id in (keys['original_document_id'], keys['amendment_id']):
                row = dict(db.execute(text('SELECT d.id,d.text,d.content_hash,d.version,d.amends_id,s.content_hash AS signed_hash,s.signed_at FROM clinical_documents d LEFT JOIN document_signatures s ON s.document_id=d.id WHERE d.id=:id AND d.workspace_id=:workspace_id'), {'id':document_id, 'workspace_id':keys['workspace_id']}).mappings().one())
                actual = hashlib.sha256(row.pop('text').encode()).hexdigest()
                assert actual == row['content_hash'], 'Saved exact text no longer matches its hash'
                if row['signed_hash'] is not None: assert actual == row['signed_hash'], 'Signature no longer matches exact text'
                row['signed_at'] = row['signed_at'].isoformat() if row['signed_at'] else None
                documents.append(row)
            assert documents[0]['signed_hash'] is not None, 'Original document lost its signature'
            assert documents[1]['amends_id'] == documents[0]['id'], 'Amendment relationship changed'
            return {'encounter':encounter, 'documents':documents}
    finally: engine.dispose()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url-file', type=Path, required=True)
    parser.add_argument('--qa-file', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--compare', type=Path)
    args = parser.parse_args()
    data = snapshot(args.url_file, args.qa_file)
    if args.compare:
        assert data == json.loads(args.compare.read_text(encoding='utf-8')), 'Persisted workflow differs from the pre-reboot snapshot'
    args.out.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'exact_text_hashes_valid':True, 'original_signature_valid':True, 'amendment_link_valid':True,
                      'matches_pre_reboot_snapshot':bool(args.compare), 'document_count':len(data['documents'])}))
