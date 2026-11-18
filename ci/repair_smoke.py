"""Bounded synthetic feature checks; responses, cookies and source text stay in memory."""
import io
import json
import os
from pathlib import Path
import uuid

import httpx
from PIL import Image, ImageDraw

from repair_deploy import REPAIR_URL, Stop


SOURCE = 'Synthetic test only. Pain score is 2 out of 10. The dressing was changed.'


def checked(response):
    if not 200 <= response.status_code < 300:
        category = response.headers.get('X-WCP-Provider-Error', 'unknown')
        # Only the server-owned closed vocabulary is safe, not arbitrary header values.
        allowed = {'unknown','authentication','permission_denied','payment_required','rate_limit_exceeded',
            'provider_overloaded','provider_unavailable','timeout','server','content_policy_violation','refusal',
            'invalid_image','image_too_large','image_too_small','unsupported_image_format','image_not_found',
            'image_download_failed','context_length_exceeded','max_tokens_exceeded','token_limit_exceeded',
            'string_too_long','invalid_request','invalid_prompt','not_found','precondition_failed',
            'payload_too_large','unprocessable','unmapped'}
        if category not in allowed:
            category = 'unknown'
        raise Stop(f'Synthetic API check failed: HTTP {response.status_code}; category {category}.')
    try:
        result = response.json()
    except ValueError:
        raise Stop('Synthetic API returned unreadable JSON.') from None
    if not isinstance(result, dict):
        raise Stop('Synthetic API returned an unexpected shape.')
    return result


def synthetic_photo():
    image = Image.new('RGB', (640, 480), 'white')
    draw = ImageDraw.Draw(image)
    draw.rectangle((80, 100, 280, 320), fill='blue')
    draw.ellipse((350, 100, 560, 320), fill='green')
    draw.text((100, 390), 'NON CLINICAL SOFTWARE TEST', fill='black')
    output = io.BytesIO()
    image.save(output, format='JPEG')
    return output.getvalue()


def features(client, email, password, workspace):
    login = checked(client.post('/api/v1/auth/login', json={'email':email,'password':password}))
    user = login.get('user', {})
    if user.get('workspace_id') != workspace or user.get('is_platform_admin') is not False:
        raise Stop('Smoke account is not the dedicated non-admin test workspace.')
    client.headers['X-CSRF-Token'] = login.get('csrf_token', '')
    session = checked(client.get('/api/v1/auth/session'))
    if session.get('requires_legal_acceptance') is not False:
        raise Stop('Test account requires owner-managed legal acceptance; automation will not accept terms.')
    if not client.headers['X-CSRF-Token']:
        raise Stop('Smoke session did not provide a security token.')

    def post(path, **kwargs):
        return checked(client.post(path, headers={'Idempotency-Key': str(uuid.uuid4())}, **kwargs))

    def start(mode):
        case = post('/api/v1/quick/start', json={'mode':mode, 'request_id':str(uuid.uuid4())})
        identifier = case.get('encounter', {}).get('id') or case.get('id')
        try:
            uuid.UUID(identifier)
        except (ValueError, TypeError, AttributeError):
            raise Stop('Synthetic encounter was not created.') from None
        return '/api/v1/encounters/' + identifier

    case = start('document')
    audio_path = Path(os.environ['AMILYA_SMOKE_AUDIO'])
    audio = post(case + '/upload', data={'kind':'audio','consent':'true'},
        files={'file':('synthetic.wav', audio_path.read_bytes(), 'audio/wav')})
    text = audio.get('evidence', {}).get('text', '')
    if not isinstance(text, str) or not text.strip():
        raise Stop('Synthetic transcription was empty.')
    document = post(case + '/upload', data={'kind':'document'},
        files={'file':('synthetic.txt', SOURCE.encode(), 'text/plain')})
    if not document.get('facts'):
        raise Stop('Synthetic extraction did not produce checked evidence.')
    report = post(case + '/quick-report', json={'template':'nursing','use_ai':True,'preferred_length':'concise'})
    if not isinstance(report.get('text'), str) or not report['text'].strip() or report.get('qa', {}).get('passed') is not True or report.get('recovery'):
        raise Stop('Synthetic report did not pass its AI check.')
    photo_case = start('photo')
    photo = post(photo_case + '/upload', data={'kind':'photo',
        'photo_review':json.dumps({'algorithm_version':'manual-review-v1','obstruction':'clear'})},
        files={'file':('synthetic-shapes.jpg', synthetic_photo(), 'image/jpeg')})
    quality = photo.get('photo_quality', {})
    if quality.get('status') != 'insufficient' or quality.get('retake_required') is not True or photo.get('facts') != []:
        raise Stop('Nonclinical image did not produce a safe no-findings decision.')
    # No cleanup/deletion endpoint is called. Temporary test encounters follow
    # the app's normal retention; the dedicated workspace contains no patient data.
    checked(client.post('/api/v1/auth/logout', json={}))


def run(url, full=True):
    expected_suffix = '---woundcopilot-rhd7rrcqdq-uc.a.run.app'
    if url != REPAIR_URL and not (url.startswith('https://ci-') and url.endswith(expected_suffix)):
        raise Stop('Smoke destination is outside the reviewed repair service.')
    try:
        with httpx.Client(base_url=url, timeout=180, follow_redirects=False,
                headers={'Origin':REPAIR_URL,'X-WCP-Client':'android'}) as client:
            health = checked(client.get('/api/v1/health'))
            if health.get('status') != 'ok' or health.get('clinical_data_enabled') is not False:
                raise Stop('Repair health or synthetic-only mode check failed.')
            if full:
                features(client, os.environ['AMILYA_SMOKE_EMAIL'], os.environ['AMILYA_SMOKE_PASSWORD'],
                         os.environ['AMILYA_SMOKE_WORKSPACE_ID'])
    except httpx.HTTPError:
        raise Stop('Synthetic API transport check failed; no request was automatically retried.') from None
