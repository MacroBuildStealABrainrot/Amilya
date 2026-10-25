"""Local synthetic route probe: isolated SQLite, mocked inference, no outbound HTTP.

Run from repository root: python scripts/load_probe.py --output work/load-probe.json
This is a closed-loop application-handler benchmark, not a production capacity test.
"""
from __future__ import annotations

import argparse
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import platform
import sys
import tempfile
import threading
import time
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def percentile(values, percent):
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * percent / 100) - 1)], 2) if ordered else None


def run_probe(concurrency, iterations, mock_delay_ms, directory):
    from fastapi import Depends, FastAPI, Request
    from fastapi.testclient import TestClient
    import httpx
    from sqlalchemy import create_engine, event, func, select
    from sqlalchemy.orm import sessionmaker
    from server import clinical, providers
    from server.billing_models import UsageOperation
    from server.clinical_models import ClinicalDocument, DocumentSignature, Encounter, Patient, Wound
    from server.db import Base, get_db
    from server.identity import User, Workspace, current_user, require_csrf

    database_path = directory / f'users-{concurrency}.sqlite'
    engine = create_engine(f'sqlite:///{database_path}', connect_args={'check_same_thread': False, 'timeout': 30})
    @event.listens_for(engine, 'connect')
    def pragmas(connection, _):
        connection.execute('PRAGMA foreign_keys=ON')
        connection.execute('PRAGMA busy_timeout=30000')
    with engine.connect() as connection:
        connection.exec_driver_sql('PRAGMA journal_mode=WAL')
    Base.metadata.create_all(engine)
    sessions = sessionmaker(engine, expire_on_commit=False, autoflush=False)
    with sessions() as db:
        for index in range(concurrency):
            db.add(Workspace(id=f'ws-{index}', name=f'Synthetic load workspace {index}'))
        db.flush()
        for index in range(concurrency):
            db.add(User(id=f'user-{index}', workspace_id=f'ws-{index}', email=f'load-{index}@example.test',
                        display_name=f'Synthetic user {index}', password_hash='unused-synthetic-hash'))
        db.flush()
        for index in range(concurrency):
            db.add(Patient(id=f'patient-{index}', workspace_id=f'ws-{index}', display_name=f'Synthetic patient {index}'))
        db.flush()
        for index in range(concurrency):
            db.add(Wound(id=f'wound-{index}', workspace_id=f'ws-{index}', patient_id=f'patient-{index}',
                         label='Synthetic left heel', location='heel', laterality='left'))
        db.commit()

    def database():
        with sessions() as db:
            try:
                yield db
            finally:
                db.rollback()
    def actor(request: Request, db=Depends(get_db)):
        return db.get(User, request.headers['X-Synthetic-User'])
    actor.__annotations__['request'] = Request  # The Request import is intentionally scoped after environment isolation.
    app = FastAPI()
    app.include_router(clinical.router, prefix='/api/v1')
    app.dependency_overrides[get_db] = database
    app.dependency_overrides[current_user] = actor
    app.dependency_overrides[require_csrf] = actor
    counter_lock = threading.Lock()
    provider_counts = {'mocked_calls': 0}
    async def mock_review(*args, **kwargs):
        with counter_lock:
            provider_counts['mocked_calls'] += 1
        await asyncio.sleep(mock_delay_ms / 1000)
        return {'considerations': ['Synthetic mocked review; clinician source review required.'], 'warnings': [],
                'usage': {'normalized_micros': 0, 'charged_credits': 0, 'mocked': True}}
    async def unexpected_provider(*args, **kwargs):
        raise AssertionError('This probe only allows its explicitly mocked review stage')
    async def deny_external_http(*args, **kwargs):
        raise AssertionError('Outbound HTTP is prohibited by the synthetic load probe')

    barrier = threading.Barrier(concurrency)
    def virtual_user(index):
        timings, errors = [], []
        completed = 0
        headers = {'X-Synthetic-User': f'user-{index}'}
        def call(client, method, route, expected=200, **kwargs):
            start = time.perf_counter()
            response = client.request(method, '/api/v1' + route, headers=headers, **kwargs)
            timings.append((time.perf_counter() - start) * 1000)
            if response.status_code != expected:
                # Do not persist payloads or raw clinical response bodies in benchmark logs.
                errors.append({'method': method, 'route': route.split('/')[1], 'status': response.status_code})
                raise ValueError('Synthetic workflow request failed')
            return response.json()
        with TestClient(app, raise_server_exceptions=False) as client:
            barrier.wait(timeout=60)
            for iteration in range(iterations):
                try:
                    encounter = call(client, 'POST', '/encounters', 201, json={'patient_id': f'patient-{index}',
                        'wound_id': f'wound-{index}', 'title': f'Synthetic probe {iteration}'})
                    path = '/encounters/' + encounter['id']
                    call(client, 'POST', path + '/evidence', 201, json={'kind': 'measurement', 'findings': [
                        {'name': 'wound_measurement', 'value': {'length_cm': 2, 'width_cm': 1, 'depth_cm': .2}}]})
                    current = call(client, 'GET', path)
                    call(client, 'POST', path + '/review', json={'mode': 'fast', 'use_ai': True})
                    draft = call(client, 'POST', path + '/draft', json={'template': 'nursing', 'use_ai': False})
                    saved = call(client, 'POST', path + '/documents', 201, json={'text': draft['text'],
                        'template': 'nursing', 'expected_revision': current['encounter']['revision']})
                    signed = call(client, 'POST', '/documents/' + saved['id'] + '/sign', json={
                        'content_hash': saved['content_hash'], 'acknowledge_review': True,
                        'expected_case_revision': saved['case_revision']})
                    final = call(client, 'GET', path)
                    if signed['status'] != 'signed' or final['encounter']['status'] != 'signed' or signed['text'] != draft['text']:
                        errors.append({'method': 'VERIFY', 'route': 'signed-document', 'status': 'invariant-failed'})
                        continue
                    completed += 1
                except ValueError:
                    continue
        return timings, errors, completed

    try:
        with patch.object(clinical, 'check_rate', lambda *args, **kwargs: None), \
             patch.object(providers, 'clinical_review', mock_review), \
             patch.object(providers, 'extract_text', unexpected_provider), \
             patch.object(providers, 'analyze_media', unexpected_provider), \
             patch.object(providers, 'transcribe', unexpected_provider), \
             patch.object(providers, 'draft_text', unexpected_provider), \
             patch.object(httpx.AsyncClient, 'request', deny_external_http):
            start = time.perf_counter()
            with ThreadPoolExecutor(max_workers=concurrency) as pool:
                outcomes = list(pool.map(virtual_user, range(concurrency)))
            elapsed = time.perf_counter() - start
        timings = [value for values, _, _ in outcomes for value in values]
        errors = [error for _, values, _ in outcomes for error in values]
        with sessions() as db:
            signed_count = db.scalar(select(func.count()).select_from(DocumentSignature))
            usage_count = db.scalar(select(func.count()).select_from(UsageOperation))
            charged = db.scalar(select(func.coalesce(func.sum(UsageOperation.charged_micros), 0)))
        return {'concurrent_users': concurrency, 'iterations_per_user': iterations,
                'attempted_workflows': concurrency * iterations, 'completed_workflows': sum(row[2] for row in outcomes),
                'http_requests': len(timings), 'http_errors': len(errors), 'errors': errors[:25],
                'request_p50_ms': percentile(timings, 50), 'request_p95_ms': percentile(timings, 95),
                'request_max_ms': round(max(timings), 2) if timings else None,
                'elapsed_seconds': round(elapsed, 3), 'requests_per_second': round(len(timings) / elapsed, 2),
                'signed_documents': signed_count, 'mocked_provider_calls': provider_counts['mocked_calls'],
                'mocked_provider_delay_ms': mock_delay_ms, 'live_provider_calls': 0,
                'usage_operations': usage_count, 'charged_micros': charged,
                'verification': 'passed' if not errors and signed_count == concurrency * iterations and usage_count == 0 else 'failed'}
    finally:
        engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--levels', default='1,10,50,100')
    parser.add_argument('--iterations', type=int, default=1)
    parser.add_argument('--mock-delay-ms', type=float, default=20)
    parser.add_argument('--output', type=Path, default=ROOT / 'work' / 'load-probe.json')
    args = parser.parse_args()
    levels = [int(value) for value in args.levels.split(',')]
    if any(not 1 <= value <= 100 for value in levels) or not 1 <= args.iterations <= 10 or not 0 <= args.mock_delay_ms <= 5000:
        parser.error('Use 1–100 users, 1–10 iterations, and 0–5000 ms mocked latency.')
    scratch = ROOT / 'work'
    scratch.mkdir(exist_ok=True)
    report = {'retrieved_at_utc': datetime.now(timezone.utc).isoformat(),
              'kind': 'synthetic in-process clinical handler probe',
              'database': 'isolated temporary SQLite WAL; 30-second busy timeout',
              'python': sys.version.split()[0], 'platform': platform.platform(), 'logical_cpu_count': os.cpu_count(),
              'authentication': 'dependency override with one isolated workspace per virtual user',
              'scope_limits': ['No live provider calls or credit charges', 'No network server, browser, TLS, password hashing, or production authentication',
                               'In-process TestClient overhead included', 'Not a PostgreSQL or production capacity result'],
              'levels': []}
    with tempfile.TemporaryDirectory(prefix='load-probe-', dir=scratch) as directory:
        directory = Path(directory)
        # Imports resolve only an isolated database and a nonexistent env file.
        os.environ['DATABASE_URL'] = f'sqlite:///{directory / "unused-common.sqlite"}'
        os.environ['WOUNDCOPILOT_ENV_FILE'] = str(directory / 'absent.env')
        os.environ['PROVIDER_EXECUTION_ENABLED'] = 'false'
        for concurrency in levels:
            result = run_probe(concurrency, args.iterations, args.mock_delay_ms, directory)
            report['levels'].append(result)
            print(json.dumps(result), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Saved ' + str(args.output.resolve()), flush=True)
    return 0 if all(result['verification'] == 'passed' for result in report['levels']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
