# WoundCoPilot 6.0.0

**Open the deployed synthetic preview:** https://woundcopilot-1039151887397.us-central1.run.app

A complete replacement of the supplied prototype: a clinical documentation application built with React, TypeScript, FastAPI and a relational evidence model, with a separate native Android client. The original archive was preserved and audited separately.

The application opens with an interactive synthetic visit. Account workspaces provide client and wound references, independent evidence entry, structured finding review, exact-text documentation, immutable signatures and explicit amendments. Audio is optional throughout. Manual entry, editing, saving and signing do not require a paid subscription or credits.

Custom report layouts are stored with each document version; layout instructions cannot establish clinical facts. Photo preparation preserves colour while supporting rotation/crop, conservative focus/lighting/glare signals and explicit obstruction review. Audio recording supports a 60-minute/25 MB boundary with a requested 32 kbps encoder rate, measured input activity, encrypted recovery and acknowledged originals retained for up to 24 hours for excerpt review. Timestamped verification processes a source-matched clip of at most 30 seconds; its candidate never silently replaces the original transcript. See [audio verification](docs/AUDIO_VERIFICATION.md) for routing and validation limits.

## Current activation state

Provider keys are configured separately from the source release. AI execution and optional medical transcription are enabled. On September 6, 2026, live synthetic rehearsals exercised OpenRouter extraction, report generation and source comparison, photo assessment, and configured audio transcription. Provider balance and account entitlement remain runtime requirements. New accounts do not automatically receive paid AI access. No provider secrets are included in this repository or archive. Manual documentation remains available.

The interaction update adds restrained press feedback, dialog/task transitions and touch navigation between encounter tasks. Settings → Motion provides Follow device, Subtle motion and Reduce motion. See [interaction review](docs/MOTION_REVIEW.md).

Real clinical data is disabled (`CLINICAL_DATA_ENABLED=false`) pending operator, clinical and privacy approvals. The software tests demonstrate engineering behavior; they do not establish clinical effectiveness, regulatory clearance or compliance certification. See [clinical validation](docs/CLINICAL_VALIDATION.md) and [legal review](docs/LEGAL_REVIEW.md).

## Local setup

Requires Python 3.11 or newer, Node.js 22, and `ffmpeg`/`ffprobe` on PATH for audio processing. PostgreSQL 17 is the deployment database. SQLite is available for local evaluation.

The release includes compiled frontend assets. On Windows, `./Start-Local.ps1` creates a local environment when absent, installs Python dependencies, applies migrations and starts the synthetic evaluation app. It creates no provider keys or paid services. The manual setup below is available for development and other operating systems.

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
Copy-Item .env.example .env
```

Set `APP_SECRET` in `.env` to at least 32 cryptographically random characters. Keep the execution and clinical-data flags false for evaluation. Then:

```powershell
cd frontend
npm.cmd ci
npm.cmd run build
cd ..
.venv\Scripts\python.exe -m alembic upgrade head
.venv\Scripts\python.exe -m uvicorn server.main:app --host 127.0.0.1 --port 8766 --no-access-log
```

Open <http://127.0.0.1:8766>. The synthetic demo needs no account. A newly registered account receives no admin privileges or purchased credits. Create the owner explicitly with `python -m scripts.bootstrap_owner`; the owner must accept the current legal versions on first sign-in. An email match alone never grants administrator access.

Alternatively, set a strong URL-safe `POSTGRES_PASSWORD` and `APP_SECRET` in `.env`, use `docker compose up --build -d database`, run `docker compose run --rm app python -m alembic upgrade head`, then `docker compose up -d app`. The compose port is bound to localhost and data has a named volume. Never run volume-removal commands against records you need to retain.

## Validate

```powershell
.venv\Scripts\python.exe -m pytest -q
cd frontend
npm.cmd run lint
npm.cmd test
npm.cmd run build
```

`python scripts/load_probe.py --help` describes the isolated synthetic workload. Provider tests use injected HTTP transports and never require credentials. Production setup fails closed when database, HTTPS, cookies or provider privacy gates are unsafe.

## Commercial configuration

| Plan | CAD/month | Monthly credits | Approximate standard visits |
|---|---:|---:|---:|
| Essential | 24.99 | 2,500 | 70 |
| Plus | 34.99 | 5,000 | 140 |
| Pro | 49.99 | 12,000 | 340 |
| Power | 75.00 | 18,000 | 510 |

A CAD 8.99 top-up adds 1,000 credits for an active subscriber. Visit counts are estimates from the supplied commercial brief, **not measured clinical capacities**. One credit represents US$0.001 of normalized provider cost; the ledger uses integer microUSD, so fractional credits remain exact. Provider discounts/free allowances do not change the normalization registry. Monthly credits reset at paid billing boundaries; top-ups persist but require an active subscription to use.

Stripe adapters support configured test/live modes, verified prices, Checkout, customer portal, signed webhook handling, idempotent grants, cancellation, failed payments and refund reversal. Stripe live billing is not activated. Business and support messages are stored in the authorized admin inbox; no external email is sent automatically.

## Documentation

- [Architecture](docs/ARCHITECTURE.md), [design constitution](docs/DESIGN.md), [security](docs/SECURITY.md).
- [Deployment](docs/DEPLOYMENT.md), [preflight evidence](docs/DEPLOYMENT_PREFLIGHT.md), [load measurements](docs/LOAD_TEST.md).
- [Observed cloud execution](docs/CLOUD_EXECUTION.md), [frontend review](docs/RELEASE_REVIEW.md), [targeted audio verification](docs/AUDIO_VERIFICATION.md).
- [Privacy implementation](docs/PRIVACY.md), [terms implementation](docs/TERMS.md), [legal launch review](docs/LEGAL_REVIEW.md).
- [Clinical validation scope](docs/CLINICAL_VALIDATION.md), [release validation](docs/VALIDATION.md).
- Task prompts and their SHA-256 registry are in `server/prompts/`.

Temporary raw upload files are not retained as database binaries. Provider replay results are encrypted and expire after 24 hours; run `python -m scripts.maintenance` to remove expired replay/session/rate metadata. Device recovery expires after seven days for drafts and 24 hours for audio. Signed records are never overwritten by cleanup or amendments.
