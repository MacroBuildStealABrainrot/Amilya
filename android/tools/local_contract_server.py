"""Run the existing backend against a disposable database with providers disabled."""
import os
from pathlib import Path
import secrets
import sys

root = Path(__file__).resolve().parents[2]
# The backend lives in the 'backend' folder of this project. WCP_BACKEND_DIR can point somewhere else.
candidates = [Path(os.environ['WCP_BACKEND_DIR'])] if os.environ.get('WCP_BACKEND_DIR') else [root / 'backend', root / 'woundcopilot-next']
backend = next((c for c in candidates if (c / 'server' / 'main.py').is_file()), None)
if backend is None:
    raise SystemExit('Backend folder not found. Expected ' + str(root / 'backend') + ' (with server/main.py).')
sys.path.insert(0, str(backend))
os.chdir(backend)

database = root / 'sandbox' / 'work' / 'level1-test.db'
database.parent.mkdir(parents=True, exist_ok=True)
database.unlink(missing_ok=True)  # always start empty; this file holds fake test data only
os.environ.update(
    WOUNDCOPILOT_ENV_FILE=str(root / 'nonexistent-android-test.env'),
    DATABASE_URL='sqlite:///' + database.as_posix(),
    APP_SECRET=secrets.token_urlsafe(48),
    ENVIRONMENT='development', AUTO_CREATE_SCHEMA='true',
    PROVIDER_EXECUTION_ENABLED='false', BILLING_ENABLED='false', CLINICAL_DATA_ENABLED='false',
    OPENROUTER_API_KEY='', GROQ_API_KEY='', DEEPGRAM_API_KEY='',
    CLOUDFLARE_API_TOKEN='', STRIPE_SECRET_KEY='',
    ALLOW_SIGNUP='true', SECURE_COOKIES='false',
    TRUSTED_HOSTS='127.0.0.1,localhost', PUBLIC_BASE_URL='http://127.0.0.1:18766',
)
import uvicorn
uvicorn.run('server.main:app', host='127.0.0.1', port=18766, log_level='warning')
