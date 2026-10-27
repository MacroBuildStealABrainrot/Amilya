"""Bounded provider gateway. Source material is data, never executable authority.

Every attempt reserves and settles in its own short transaction. No network call
holds a business write lock. Encrypted replay results prevent duplicate inference.
"""
from __future__ import annotations

import asyncio
import base64
from contextvars import ContextVar
from decimal import Decimal, ROUND_CEILING
from hashlib import sha256
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import io
import json
import math
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import time
import uuid
from typing import get_args

from cryptography.fernet import Fernet, InvalidToken
from fastapi import APIRouter, Depends, HTTPException
import httpx
from PIL import Image, ImageOps, UnidentifiedImageError
from sqlalchemy import select

from .core import settings
from .db import SessionLocal
from .identity import User, check_rate, require_admin
from .provider_models import ProviderResult
from .billing_models import UsageOperation
from .schemas import Name, Measurement, Location, Tissue, Pain, Observation, validated_value
from . import billing
from .kosmo import select_route

router = APIRouter()
request_operation: ContextVar[str | None] = ContextVar('request_operation', default=None)
document_operations: ContextVar[list | None] = ContextVar('document_operations',default=None)
PROMPTS = Path(__file__).parent / 'prompts'
PRICE_VERSION = 'normalized-2026-09-05'
# USD per million tokens: durable list-price normalization, independent of discounts.
MODEL_PRICES = {
    'meta/muse-spark-1.3': ('1.25', '4.25', '.15'),
    'openai/gpt-5.6-luna': ('.20', '1.20', '.02'),
    'openai/gpt-5.6-terra': ('2', '12', '.2'),
    'openai/gpt-5.6-sol': ('4', '20', '.4'),
    'anthropic/claude-fable-5.1': ('10', '50', '.25'),
}
SPEECH_PRICES = {'cloudflare': Decimal('510'), 'groq': Decimal('40000') / 60,
                 'deepgram': Decimal('7700')}  # microUSD per minute, medical reserve uses configurable cap
GROQ_FULL_WHISPER_MICROS_PER_MINUTE = Decimal('111000') / 60
PHOTO_NAMES = {'wound_edges', 'periwound', 'wound_tissue', 'exudate'}
PHOTO_RETAKE_MESSAGES = {
    'blur': 'Hold the camera steady and retake the photo.',
    'lighting': 'Improve the lighting and retake the photo.',
    'distance': 'Move closer and retake the photo.',
    'framing': 'Keep the entire wound in the frame.',
    'obstruction': 'The wound is obscured. Retake when it is visible.',
    'no_wound': 'No wound is clear in this photo. Retake the photo.',
    'uncertain': "Photo isn't clear enough for assessment. Retake the photo.",
}
MAX_RATE_LIMIT_ATTEMPTS = 2
MAX_RATE_LIMIT_WAIT_SECONDS = 4.0
REPORT_STAGE_TIMEOUT_SECONDS = 60
TASK_FILES = {'extract': '20_clinical_fact_extraction', 'document': '25_document_extraction',
              'image': '40_wound_image_assessment', 'review': '50_clinical_review',
              'holistic': '55_holistic_wound_review', 'escalation': '60_targeted_escalation',
              'challenge': '65_independent_challenge', 'reconcile': '70_reconciliation',
              'draft': '80_documentation_generation', 'qa': '85_documentation_qa',
              'assistant': '90_contextual_clinical_assistant',
              'quick_draft': '81_quick_documentation', 'quick_qa': '86_quick_documentation_qa',
              'consult': '91_wound_consultant', 'quick_refine': '82_quick_refinement',
              'quick_repair': '83_quick_report_repair', 'improve_report': '84_improve_report', 'improve_assessment': '92_improve_assessment'}

# Each independent workflow keeps its own prompt and metered operation while
# using the same checked response shape and Kosmo effort policy as its task type.
TASK_FAMILIES = {'quick_draft': 'draft', 'quick_qa': 'qa', 'consult': 'assistant', 'quick_refine': 'draft',
                 'quick_repair': 'draft', 'improve_report': 'draft', 'improve_assessment': 'assistant'}


def fingerprint(value) -> str:
    return sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def cipher() -> Fernet:
    if len(settings.app_secret) < 32:
        raise HTTPException(503, 'Secure application storage must be configured before AI processing')
    return Fernet(base64.urlsafe_b64encode(sha256(('provider-replay:' + settings.app_secret).encode()).digest()))


def privacy_guard(provider: str):
    if not settings.provider_execution_enabled:
        raise HTTPException(503, 'Live AI is paused until the owner activates provider execution')
    if settings.clinical_data_enabled:
        approved = getattr(settings, provider + '_phi_approved', False)
        if provider == 'groq': approved = approved and settings.groq_zdr_enabled
        if not approved:
            raise HTTPException(503, 'This provider is awaiting clinical-data privacy approval')
    if provider == 'openrouter' and not settings.openrouter_require_zdr:
        raise HTTPException(503, 'Zero-retention routing must be enabled')


def prompt(task: str) -> str:
    boundary = (PROMPTS / '00_healthcare_boundary.system.md').read_text(encoding='utf-8')
    role = (PROMPTS / (TASK_FILES[task] + '.system.md')).read_text(encoding='utf-8')
    from .documentation import DOCUMENT_TASKS
    rules = (PROMPTS / '06_documentation_rules.system.md').read_text(encoding='utf-8') if task in DOCUMENT_TASKS | {'draft', 'qa', 'quick_qa'} else ''
    return boundary + '\n\n' + rules + '\n\n' + role


def scope_guard(text: str):
    # Explicit jailbreak attempts cannot become instructions to the model. Clinical
    # discussion of these words is still usable through manual evidence entry.
    attack = re.search(r'(?is)(ignore|override|disregard).{0,35}(system|previous|instruction|safety)|reveal.{0,30}(prompt|secret|api.key)|(?:set|make).{0,20}(?:admin|unlimited)|(?:write|create).{0,20}(?:ransomware|malware)', text)
    if attack:
        raise HTTPException(422, 'This request contains instructions outside the clinical evidence workflow')


def normalized_tokens(model: str, usage: dict) -> tuple[int, dict]:
    prices = MODEL_PRICES.get(model)
    if not prices: raise HTTPException(503, 'Model needs a reviewed price registry entry')
    if not any(k in usage for k in ('prompt_tokens', 'input_tokens')) or not any(k in usage for k in ('completion_tokens', 'output_tokens')):
        raise ValueError('Provider token usage is missing; cost is unknown')
    def integer(value):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0 or int(value) != value: raise ValueError('Invalid provider usage count')
        return int(value)
    input_tokens = integer(usage.get('prompt_tokens', usage.get('input_tokens', 0)))
    output_tokens = integer(usage.get('completion_tokens', usage.get('output_tokens', 0)))
    details = usage.get('prompt_tokens_details') or {}
    cached = integer(details.get('cached_tokens', 0))
    if cached > input_tokens: raise ValueError('Cached tokens exceed input tokens')
    cost = (Decimal(input_tokens - cached) * Decimal(prices[0]) + Decimal(output_tokens) * Decimal(prices[1]) + Decimal(cached) * Decimal(prices[2]))
    return int(cost.to_integral_value(rounding=ROUND_CEILING)), {'input_tokens': input_tokens, 'output_tokens': output_tokens,
            'cached_tokens': cached, 'total_tokens': input_tokens + output_tokens, 'price_registry_version': PRICE_VERSION}


def actual_cost(usage: dict) -> int | None:
    value = usage.get('cost')
    if not isinstance(value, (float, int)) or isinstance(value, bool) or not math.isfinite(value) or value < 0: return None
    return int((Decimal(str(value)) * 1_000_000).to_integral_value(rounding=ROUND_CEILING))


def photo_quality(value):
    """Bounded model quality decision, with application-owned retake language."""
    if not isinstance(value, dict) or set(value) != {'status', 'reason'}:
        raise ValueError('Missing or invalid photo quality decision')
    status, reason = value['status'], value['reason']
    if status not in ('sufficient', 'insufficient') or reason not in ('none', *PHOTO_RETAKE_MESSAGES):
        raise ValueError('Unknown photo quality decision')
    if (status == 'sufficient') != (reason == 'none'):
        raise ValueError('Contradictory photo quality decision')
    return {'status': status, 'reason': reason, 'retake_required': status == 'insufficient',
            'message': PHOTO_RETAKE_MESSAGES.get(reason, '')}


def result_schema(task: str) -> dict:
    from .documentation import DOCUMENT_TASKS, response_schema
    if task in DOCUMENT_TASKS: return response_schema()
    task = TASK_FAMILIES.get(task, task)
    if task in {'extract', 'document', 'image'}:
        # Give the model the same value contracts enforced when accepting results.
        # A free-form object here allowed plausible but unusable field names.
        special = {'wound_measurement': Measurement, 'wound_location': Location,
                   'wound_tissue': Tissue, 'pain': Pain}
        allowed = PHOTO_NAMES if task == 'image' else set(get_args(Name))
        variants = []
        for model in (Measurement, Location, Tissue, Pain, Observation):
            names = sorted(name for name in allowed if special.get(name, Observation) is model)
            if names:
                variants.append({'type': 'object', 'additionalProperties': False,
                    'properties': {'name': {'type': 'string', 'enum': names},
                                   'value': model.model_json_schema(), 'quote': {'type': 'string'}},
                    'required': ['name', 'value', 'quote']})
        properties = {'facts': {'type': 'array', 'maxItems': 100, 'items': {'anyOf': variants}},
                      'warnings': {'type': 'array', 'items': {'type': 'string'}}}
        if task == 'image':
            properties['quality'] = {'type': 'object', 'additionalProperties': False,
                'properties': {'status': {'type': 'string', 'enum': ['sufficient', 'insufficient']},
                               'reason': {'type': 'string', 'enum': ['none', *PHOTO_RETAKE_MESSAGES]}},
                'required': ['status', 'reason']}
    elif task == 'draft': properties = {'text': {'type': 'string'}}
    elif task == 'qa': properties = {'passed': {'type': 'boolean'}, 'discrepancies': {'type': 'array', 'items': {'type': 'string'}, 'maxItems': 20}}
    elif task == 'assistant': properties = {'answer': {'type': 'string'}, 'considerations': {'type': 'array', 'items': {'type': 'string'}, 'maxItems': 12}}
    else: properties = {'considerations': {'type': 'array', 'maxItems': 16, 'items': {'type': 'string'}}, 'warnings': {'type': 'array', 'items': {'type': 'string'}}}
    return {'type': 'object', 'additionalProperties': False, 'properties': properties, 'required': list(properties)}


def parse_provider_json(value):
    def pairs(items):
        result = {}
        for key, item in items:
            if key in result: raise ValueError('Duplicate provider JSON field')
            result[key] = item
        return result
    def invalid_constant(value):
        raise ValueError('Non-finite provider JSON value')
    return json.loads(value, object_pairs_hook=pairs, parse_constant=invalid_constant)


def validate_result(data: dict, task: str, source: str | None = None) -> dict:
    from .documentation import DOCUMENT_TASKS, validate_response
    if task in DOCUMENT_TASKS: return validate_response(data)
    task = TASK_FAMILIES.get(task, task)
    if not isinstance(data, dict): raise ValueError('Expected object')
    if task in {'extract', 'document', 'image'}:
        fields = {'facts', 'warnings', 'quality'} if task == 'image' else {'facts', 'warnings'}
        if set(data) - fields or not isinstance(data.get('facts'), list) or len(data['facts']) > 100: raise ValueError('Invalid facts')
        quality = photo_quality(data.get('quality')) if task == 'image' else None
        extra = data.get('warnings', [])
        if not isinstance(extra, list) or any(not isinstance(x, str) or len(x) > 1000 for x in extra): raise ValueError('Invalid warnings')
        if quality and quality['retake_required']:
            # Insufficient visual evidence cannot create findings, even if a model
            # contradicts its own quality decision with plausible-looking facts.
            return {'facts': [], 'warnings': [quality['message'], *extra[:15]], 'quality': quality}
        facts, warnings = [], []
        for item in data['facts']:
            if not isinstance(item, dict) or set(item) - {'name', 'value', 'quote'}: raise ValueError('Invalid finding')
            name, value, quote = item.get('name'), item.get('value'), item.get('quote')
            if name not in get_args(Name): raise ValueError('Unknown field')
            if task == 'image' and name not in PHOTO_NAMES:
                warnings.append('A finding outside visible photo evidence was excluded.')
                continue
            if not isinstance(quote, str) or len(quote) > 4000: raise ValueError('Invalid supporting quote')
            if source is not None and (not quote.strip() or quote not in source):
                warnings.append('A suggestion without an exact source quotation was excluded.')
                continue
            facts.append({'name': name, 'value': validated_value(name, value), 'quote': quote})
        result = {'facts': facts, 'warnings': warnings + extra[:16]}
        if quality:
            # A "sufficient" image that yielded no assessable observations must
            # not be shown as a completed wound assessment.
            if not facts:
                quality = photo_quality({'status': 'insufficient', 'reason': 'uncertain'})
                result['warnings'].insert(0, quality['message'])
            result['quality'] = quality
        return result
    if task == 'draft':
        if set(data) != {'text'} or not isinstance(data['text'], str) or not data['text'].strip() or len(data['text']) > 30000: raise ValueError('Invalid draft')
        return data
    if task == 'qa':
        if set(data) != {'passed', 'discrepancies'} or not isinstance(data['passed'], bool) or not isinstance(data['discrepancies'], list): raise ValueError('Invalid QA result')
        if any(not isinstance(x, str) or len(x) > 2000 for x in data['discrepancies']) or len(data['discrepancies']) > 20: raise ValueError('Invalid discrepancies')
        if data['passed'] and data['discrepancies']: raise ValueError('Inconsistent QA result')
        return data
    if task == 'assistant':
        if set(data) != {'answer', 'considerations'} or not isinstance(data['answer'], str) or not data['answer'].strip() or len(data['answer']) > 10000: raise ValueError('Invalid assistant answer')
        if not isinstance(data['considerations'], list) or len(data['considerations']) > 12 or any(not isinstance(x, str) or len(x) > 2000 for x in data['considerations']): raise ValueError('Invalid assistant considerations')
        return data
    if set(data) - {'considerations', 'warnings'}: raise ValueError('Invalid review')
    values = data.get('considerations')
    if not isinstance(values, list) or len(values) > 16 or any(not isinstance(x, str) or len(x) > 3000 for x in values): raise ValueError('Invalid considerations')
    warnings = data.get('warnings', [])
    if not isinstance(warnings, list) or len(warnings) > 16 or any(not isinstance(x, str) or len(x) > 1000 for x in warnings):
        raise ValueError('Invalid review warnings')
    return {'considerations': values, 'warnings': warnings}


def quota_rejection(response):
    """Inspect a bounded error body transiently; never return/log its contents."""
    if len(response.content) > 16384:
        return None  # Cannot safely classify a large, potentially echoed body.
    try:
        body = parse_provider_json(response.text)
    except (ValueError, UnicodeError):
        return None
    if not isinstance(body, dict):
        return None
    detail = json.dumps(body.get('error', {}), ensure_ascii=False)[:16384]
    return bool(re.search(r'(?i)quota|credits?|billing|payment|insufficient|spending|budget|daily|per[-_ ]day|monthly|hard[-_ ]limit', detail))


def retry_after_delay(response):
    """Parse a bounded upstream hint; never echo an untrusted header verbatim."""
    hint = response.headers.get('Retry-After')
    if hint is None or len(hint) > 128:
        return None
    try:
        delay = float(hint)
    except ValueError:
        try:
            timestamp = parsedate_to_datetime(hint)
            if timestamp.tzinfo is None:
                return None
            delay = (timestamp - datetime.now(timezone.utc)).total_seconds()
        except (ValueError, TypeError, OverflowError):
            return None
    # The upper bound also keeps native clients' milliseconds arithmetic safe.
    if not math.isfinite(delay) or not 0 <= delay <= 2_147_483_647:
        return None
    return delay


def retry_after_headers(usage):
    deadline = usage.get('retry_not_before')
    if type(deadline) not in (int, float) or not math.isfinite(deadline):
        return None
    remaining = math.ceil(deadline - time.time())
    return {'Retry-After': str(remaining)} if 0 < remaining <= 2_147_483_647 else None


def rate_limit_retry_delay(response):
    """One short retry for pre-generation capacity rejection, respecting its hint.

    Never retry HTTP200 error envelopes or responses with generation/usage fields:
    their execution and cost may be ambiguous. Long retry hints are surfaced to
    the client, not shortened into an aggressive retry.
    """
    if response.status_code != 429 or quota_rejection(response) is not False:
        return None
    try:
        body = parse_provider_json(response.text)
    except (ValueError, UnicodeError):
        return None
    if not isinstance(body, dict) or any(key in body for key in ('usage', 'choices', 'id')):
        return None
    if response.headers.get('Retry-After') is None:
        return 1.0 + secrets.randbelow(251) / 1000
    delay = retry_after_delay(response)
    if delay is None or delay > MAX_RATE_LIMIT_WAIT_SECONDS:
        return None
    return max(.25, delay)


def image_gateway_retry_delay(response):
    """Retry one explicit gateway failure without evidence of generation.

    Do not retry ambiguous transport failures, successful response envelopes,
    quota failures, or responses carrying generated output or usage.
    """
    if response.status_code not in {502, 503, 504}:
        return None
    try:
        body = parse_provider_json(response.text)
    except (ValueError, UnicodeError):
        return None
    if (not isinstance(body, dict) or 'error' not in body
            or any(key in body for key in ('usage', 'choices', 'id'))
            or quota_rejection(response) is not False):
        return None
    if response.headers.get('Retry-After') is None:
        return 1.0 + secrets.randbelow(251) / 1000
    delay = retry_after_delay(response)
    if delay is None or delay > MAX_RATE_LIMIT_WAIT_SECONDS:
        return None
    return max(.25, delay)


async def rate_limit_backoff(seconds):
    await asyncio.sleep(seconds)


async def metered_call(*, user: User, encounter_id: str, task: str, provider: str, model: str, payload_hash: str,
                       estimate: int, perform, measure, validate, prior_attempts=None) -> dict:
    privacy_guard(provider)
    check_rate('provider:' + user.id, limit=60)
    crypt = cipher()
    seed = request_operation.get() or str(uuid.uuid4())
    operation = fingerprint([user.id, encounter_id, seed, task, provider, model, payload_hash])
    if task=='transcription':
        base_operation=operation
        # Resume successful checkpoints; retry only settled, waived failures.
        # Unknown/in-flight operations never qualify for another request.
        with SessionLocal() as retry_db:
            for retry_number in range(3):
                previous=retry_db.scalar(select(UsageOperation).where(UsageOperation.workspace_id==user.workspace_id,UsageOperation.operation_id==operation))
                if not previous or previous.status!='settled' or previous.usage.get('charge_waived') is not True:break
                operation=fingerprint([base_operation,'retry',retry_number+1])
    with SessionLocal() as ledger_db:
        old = ledger_db.scalar(select(ProviderResult).where(ProviderResult.workspace_id == user.workspace_id, ProviderResult.operation_id == operation))
        if old:
            if old.user_id != user.id or old.request_hash != payload_hash: raise HTTPException(409, 'Request identity mismatch')
            if document_operations.get() is not None:document_operations.get().append(operation)
            try: return json.loads(crypt.decrypt(old.ciphertext, ttl=86400))
            except InvalidToken: raise HTTPException(409, 'This completed request has expired. Review the case before creating a new request.')
        reservation = billing.reserve(ledger_db, user, operation, estimate, encounter_id, task)
        if not reservation['created']:
            previous = ledger_db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == user.workspace_id,
                UsageOperation.operation_id == operation, UsageOperation.user_id == user.id))
            if previous and previous.status == 'settled' and previous.usage.get('charge_waived') is True:
                attempts = previous.usage.get('attempts', [])
                if 1 <= len(attempts) <= MAX_RATE_LIMIT_ATTEMPTS and attempts[-1].get('status') == 'failed':
                    code = attempts[-1].get('error_code')
                    if code in {'502', '503', '504'}:
                        # Replay the settled failure so a configured fallback can
                        # reach its cached success; never perform or charge again.
                        raise HTTPException(int(code), 'This provider attempt previously failed. No new request or charge was made; a configured fallback may supply its saved result.',
                            headers=retry_after_headers(previous.usage))
            raise HTTPException(409, 'This AI request is already processing or awaiting reconciliation. Refresh the case before retrying.')
    if document_operations.get() is not None:document_operations.get().append(operation)
    started = time.monotonic()
    usage, normalized, billed = {}, 0, None
    result, error = None, None
    try:
        raw = await perform()
        normalized, usage, billed = measure(raw)
        result = validate(raw)
    except httpx.TimeoutException:
        error = HTTPException(504, 'The provider timed out. Refresh the case, then start a new attempt. Final provider cost may require reconciliation.')
    except httpx.HTTPStatusError as exc:
        code = exc.response.status_code
        # Classify upstream errors without storing or returning provider bodies,
        # which can echo source material or credentials.
        reason = 'upstream_error'
        detail = 'The AI provider could not complete the request. Manual charting remains available.'
        if code == 404:
            reason = 'route_unavailable'
            detail = 'No AI endpoint matches the configured model, privacy policy and request parameters. The owner must review provider routing; manual charting remains available.'
        elif code == 402:
            reason = 'provider_credits'
            detail = 'The AI provider has insufficient credits or a spending limit. The owner must check the provider balance; manual charting remains available.'
        elif code in {401, 403}:
            reason = 'provider_access'
            detail = 'The AI provider denied account or API-key access. The owner must check provider access; manual charting remains available.'
        elif code == 429:
            reason = 'provider_credits' if quota_rejection(exc.response) is True else 'provider_rate_limit'
            detail = ('Analysis is unavailable. The owner must check the provider balance or account limit.'
                      if reason == 'provider_credits' else 'Analysis is temporarily unavailable. Please try again shortly.')
            delay = retry_after_delay(exc.response)
            if delay is not None:
                usage['retry_not_before'] = time.time() + math.ceil(delay)
        elif code == 400:
            reason = 'request_incompatible'
            detail = 'The AI provider rejected the request format or model parameters. The owner must review the integration; manual charting remains available.'
        usage.update({'upstream_status': exc.response.extensions.get('wcp_transport_status', code), 'failure_reason': reason})
        error = HTTPException(503 if code in {401, 402, 403, 404, 429} else 502, detail,
                              headers={**(retry_after_headers(usage) or {}), 'X-WCP-Upstream-Status': str(usage['upstream_status'])})
    except (ValueError, KeyError, TypeError, IndexError):
        error = HTTPException(502, 'The provider returned an invalid clinical response. No unsupported findings were accepted.')
    except httpx.HTTPError:
        error = HTTPException(503, 'The provider connection failed. Your source remains available for retry.')
    except HTTPException as exc: error = exc
    except Exception:
        error = HTTPException(502, 'The provider response could not be processed safely')
    earlier = list(prior_attempts or [])
    if earlier:
        # A rate-limit rejection usually has no generation, but vendor cost for
        # that attempt was not supplied. Do not label the total measured zero.
        billed = None
    usage.update({'latency_ms': int((time.monotonic() - started) * 1000), 'attempt_count': len(earlier) + 1, 'price_registry_version': PRICE_VERSION,
                  'attempts': [*earlier, {'provider': provider, 'model': model, 'status': 'failed' if error else 'completed',
                                'error_code': str(error.status_code) if error else '', 'normalized_micros': normalized}]})
    with SessionLocal() as ledger_db:
        # Unknown vendor costs are NULL, never represented as measured zero.
        settled = billing.settle(ledger_db, user, operation, normalized, provider, model, usage, actual_cost_micros=billed, commit=False, waive_charge=bool(error))
        if result is not None:
            result['usage'] = {**usage, 'normalized_micros': normalized, 'charged_credits': settled['charged_micros'] / 1000,
                               'actual_cost_micros': billed, 'operation_id': operation, 'provider': provider, 'model': model}
            ledger_db.add(ProviderResult(workspace_id=user.workspace_id, user_id=user.id, operation_id=operation,
                request_hash=payload_hash, ciphertext=crypt.encrypt(json.dumps(result, ensure_ascii=False).encode())))
        ledger_db.commit()
    if error: raise error
    return result


async def structured(task, content, model, *, user, encounter_id, source=None, max_tokens=1800, routing=None):
    if not settings.openrouter_api_key: raise HTTPException(503, 'AI is not configured; manual evidence and documentation are available')
    if model not in MODEL_PRICES: raise HTTPException(503, 'Model configuration needs review')
    system = prompt(task) + '\nReturn only JSON matching the response schema. Do not add diagnostic or treatment certainty beyond the source.'
    payload = {'model': model, 'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': content}],
        ('max_completion_tokens' if model.startswith('openai/') else 'max_tokens'): max_tokens,
        'response_format': {'type': 'json_schema', 'json_schema': {'name': 'clinical_' + task, 'strict': False, 'schema': result_schema(task)}},
        'provider': {'zdr': True, 'data_collection': 'deny', 'require_parameters': True}, 'stream': False}
    if routing:
        payload['reasoning'] = {'effort': routing['effort_level'], 'exclude': True}
    # Conservatively reserve for text plus media and bounded output. Normalization
    # uses actual returned token counts, including provider-reported image tokens.
    input_estimate = len(system) // 2 + (len(content) // 2 if isinstance(content, str) else 12000)
    prices = MODEL_PRICES[model]
    estimate = math.ceil(input_estimate * float(prices[0]) + max_tokens * float(prices[1]))
    prior_attempts = []
    async def perform_attempts():
        async with httpx.AsyncClient(timeout=settings.provider_timeout_seconds, follow_redirects=False) as client:
            for attempt in range(MAX_RATE_LIMIT_ATTEMPTS):
                response = await client.post(settings.openrouter_base_url.rstrip('/') + '/chat/completions',
                    headers={'Authorization': 'Bearer ' + settings.openrouter_api_key, 'HTTP-Referer': settings.public_base_url, 'X-Title': 'WoundCoPilot'}, json=payload)
                delay = rate_limit_retry_delay(response) if attempt == 0 else None
                if delay is None and attempt == 0 and task == 'image':
                    delay = image_gateway_retry_delay(response)
                if delay is None:
                    break
                prior_attempts.append({'provider': 'openrouter', 'model': model, 'status': 'failed',
                                       'error_code': str(response.status_code), 'normalized_micros': 0})
                await rate_limit_backoff(delay)
            response.raise_for_status()
            raw = parse_provider_json(response.text)
            # Some gateways return HTTP 200 with an error object. Normalize it
            # through the same safe failure/waived-charge path as HTTP failures.
            if isinstance(raw, dict) and 'error' in raw:
                info = raw['error']
                code = info.get('code') if isinstance(info, dict) else None
                if isinstance(code, str) and code.isascii() and code.isdigit(): code = int(code)
                if type(code) is not int or not 400 <= code <= 599: code = 502
                delay = retry_after_delay(response)
                synthetic = httpx.Response(code, request=response.request, extensions={'wcp_transport_status': response.status_code},
                    headers={'Retry-After': str(math.ceil(delay))} if delay is not None else {},
                    json={'error': {'type': 'insufficient_quota' if quota_rejection(response) is True else 'upstream_error'}})
                raise httpx.HTTPStatusError('Provider error envelope', request=response.request, response=synthetic)
            return raw
    async def perform():
        if task not in {'image', 'quick_draft', 'quick_refine', 'quick_qa', 'quick_repair', 'improve_report'}:
            return await perform_attempts()
        # A report has at most four stages. Bound the whole network portion of
        # each stage, including the single 429 retry/backoff, rather than only
        # socket inactivity. Keep this inside metering so timeout settles safely.
        try:
            return await asyncio.wait_for(perform_attempts(),
                timeout=min(REPORT_STAGE_TIMEOUT_SECONDS, settings.provider_timeout_seconds))
        except TimeoutError:
            raise httpx.TimeoutException('The AI stage exceeded its bounded wait') from None
    def measure(raw):
        vendor = raw.get('usage') or {}
        amount, safe = normalized_tokens(model, vendor)
        if routing: safe['routing'] = routing
        return amount, safe, actual_cost(vendor)
    def validate(raw):
        choice = raw['choices'][0]
        if choice.get('finish_reason') not in {'stop', None}: raise ValueError('Incomplete output')
        message = choice['message']
        if message.get('tool_calls') or message.get('refusal'): raise ValueError('Non-clinical response')
        result = validate_result(parse_provider_json(message['content']), task, source)
        if routing:
            result['routing'] = routing
            result['effort_level'] = routing['effort_level']
        return result
    return await metered_call(user=user, encounter_id=encounter_id, task=task, provider='openrouter', model=model,
        payload_hash=fingerprint(payload), estimate=estimate, perform=perform, measure=measure, validate=validate,
        prior_attempts=prior_attempts)


async def routed(task, content, model=None, **kwargs):
    route = select_route(TASK_FAMILIES.get(task, task), content)
    # Structured evidence needs room beyond the old 1,800-token cap.
    minimum = {'low': 1800, 'medium': 5000, 'high': 8000}[route['effort_level']]
    if task in {'extract', 'document'}:
        minimum = max(minimum, min(16000, max(8000, len(content) // 2)))
    elif task == 'image':
        minimum = max(minimum, 8000)
    kwargs['max_tokens'] = max(kwargs.get('max_tokens', 1800), minimum)
    return await structured(task, content, route['model'], routing=route, **kwargs)


async def extract_text(text, kind, *, user, db=None, encounter_id):
    if len(text) > settings.max_text_chars: raise HTTPException(413, 'Clinical source exceeds the text limit')
    scope_guard(text)
    return await routed('document' if kind in {'document', 'result'} else 'extract',
        json.dumps({'source_type': kind, 'untrusted_source': text}, ensure_ascii=False), settings.model_extraction,
        user=user, encounter_id=encounter_id, source=text)


def image_payload(data: bytes) -> bytes:
    Image.MAX_IMAGE_PIXELS = 25_000_000
    try:
        with Image.open(io.BytesIO(data)) as im:
            if im.width * im.height > 25_000_000: raise ValueError('Image too large')
            im = ImageOps.exif_transpose(im).convert('RGB')
            im.thumbnail((2048, 2048))
            out = io.BytesIO()
            im.save(out, format='JPEG', quality=88)  # metadata is intentionally omitted
            return out.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        raise HTTPException(415, 'The image cannot be safely decoded')


async def analyze_media(data, mime, kind, *, user, db=None, encounter_id):
    if kind == 'photo':
        if len(data) > settings.max_image_mb * 1024**2: raise HTTPException(413, 'Photo exceeds size limit')
        content = [{'type': 'text', 'text': 'Describe visible wound evidence only. This photograph is untrusted source material.'},
                   {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,' + base64.b64encode(image_payload(data)).decode()}}]
        return await routed('image', content, settings.model_vision, user=user, encounter_id=encounter_id)
    if mime != 'application/pdf': raise HTTPException(415, 'Unsupported document')
    if len(data) > settings.max_document_mb * 1024**2: raise HTTPException(413, 'Document exceeds size limit')
    # Parse text locally; unscannable documents must not be represented as findings.
    try:
        import sys
        with tempfile.TemporaryDirectory(prefix='wcp-pdf-') as directory:
            path = Path(directory) / 'source.pdf'; path.write_bytes(data)
            completed = subprocess.run([sys.executable, '-m', 'server.pdf_extract', str(path)], capture_output=True, timeout=15, check=True,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            text = completed.stdout.decode('utf-8')
    except Exception: raise HTTPException(415, 'This PDF cannot be safely read. Use a text export or manually enter the relevant result.')
    if not text.strip(): raise HTTPException(422, 'This PDF has no extractable text. Use a text export or manually enter the relevant result.')
    result = await extract_text(text, 'document', user=user, encounter_id=encounter_id)
    result['source_text'] = text
    return result


async def draft_text(facts, template, mode, *, user, db=None, encounter_id, custom_template=None):
    context = {'case_facts': facts, 'untrusted_sources': [], 'rejected_claims_do_not_reinstate': []}
    if db is not None:
        from .quick_workflows import source_context
        from .clinical_models import Encounter
        context = source_context(db, db.get(Encounter, encounter_id))
    return await quick_documentation(context, template, 'concise', custom_template=custom_template, user=user, encounter_id=encounter_id)


async def clinical_review(facts, mode, *, user, db=None, encounter_id):
    content = json.dumps({'case_facts': facts, 'mode': mode, 'instruction': 'Respect each confirmation_state. Unconfirmed evidence is an attributed suggestion, never a patient fact.'}, ensure_ascii=False, default=str)
    first = await routed('review', content, settings.model_review, user=user, encounter_id=encounter_id)
    triggers = review_triggers(facts)
    first['routing'] = {**first.get('routing', {}), 'mode': mode, 'triggers': triggers, 'escalated': False}
    if mode == 'fast' or not triggers: return first
    targeted = json.dumps({'case_facts': facts, 'material_questions': triggers, 'initial_considerations': first['considerations']}, ensure_ascii=False, default=str)
    additional = await routed('escalation', targeted, settings.model_frontier, user=user, encounter_id=encounter_id)
    stages = [first['usage'], additional['usage']]
    opinions = [first['considerations'], additional['considerations']]
    if mode == 'deep' and any('conflict' in x or 'vascular' in x for x in triggers):
        challenged = await routed('challenge', targeted, settings.model_challenger, user=user, encounter_id=encounter_id)
        opinions.append(challenged['considerations']); stages.append(challenged['usage'])
    reconciled = await routed('reconcile', json.dumps({'case_facts': facts, 'material_questions': triggers, 'interpretations': opinions}, ensure_ascii=False, default=str),
        settings.model_review, user=user, encounter_id=encounter_id)
    reconciled['usage']['stages'] = stages
    reconciled['routing'] = {**reconciled.get('routing', {}), 'mode': mode, 'triggers': triggers, 'escalated': True}
    return reconciled


async def clinical_assistant(facts, question, *, user, db=None, encounter_id):
    scope_guard(question)
    if not 1 <= len(question.strip()) <= 2000: raise HTTPException(422, 'Ask a brief question about the current case')
    if re.search(r'(?i)\b(poem|song|recipe|vacation|holiday|stock price|investment|video game|javascript|python code|sports score)\b', question):
        raise HTTPException(422, 'The assistant supports questions about clinical evidence and documentation in this case')
    if not facts: raise HTTPException(422, 'Add case evidence before asking the contextual assistant')
    return await routed('assistant', json.dumps({'case_facts': facts, 'untrusted_question': question}, ensure_ascii=False, default=str),
        settings.model_review, user=user, encounter_id=encounter_id)


async def checked_quick_text(context, task, options, *, user, encounter_id):
    operations=[]
    token=document_operations.set(operations)
    try:return await _checked_quick_text(context,task,options,user=user,encounter_id=encounter_id)
    except HTTPException:
        if operations:
            with SessionLocal() as refund_db:billing.refund_failed_document(refund_db,user,operations)
        raise
    finally:document_operations.reset(token)


async def _checked_quick_text(context, task, options, *, user, encounter_id):
    from .documentation import quality_defects, clean_generated_layout
    content = {**context, **options, 'document_task': task}
    options = {**options, 'document_task': task}
    draft = await routed(task, json.dumps(content, ensure_ascii=False, default=str),
        user=user, encounter_id=encounter_id, max_tokens=8000 if len(json.dumps(context,ensure_ascii=False))>20000 else 2400)
    draft = clean_generated_layout(draft, options)
    qa = await routed('quick_qa', json.dumps({**content, 'proposed_draft': draft['text'], 'proposed_status': draft.get('status', 'ready'), 'proposed_ui_alerts': draft.get('ui_alerts', [])},
        ensure_ascii=False, default=str), user=user, encounter_id=encounter_id, max_tokens=1000)
    defects = quality_defects(draft['text'], context)
    if defects:
        qa = {**qa, 'passed': False, 'discrepancies': list(dict.fromkeys(qa.get('discrepancies', []) + defects))[:20]}
    if qa.get('passed') is False:
        from .quick_report_repair import repair_quick_report_once
        return await repair_quick_report_once(context, task, options, draft, qa,
            user=user, encounter_id=encounter_id, routed=routed)
    if qa.get('passed') is not True or qa.get('discrepancies') != []:
        raise HTTPException(502, 'The report check could not be verified. Your original source and editor text remain available.')
    draft.setdefault('usage', {})['stages'] = [qa.get('usage', {})]
    draft['qa'] = {'passed': True, 'discrepancies': []}
    return draft


async def quick_documentation(context, template, preferred_length, *, custom_template=None, user, encounter_id):
    if custom_template:
        scope_guard(json.dumps(custom_template, ensure_ascii=False))
    return await checked_quick_text(context, 'quick_draft',
        {'template': template, 'preferred_length': preferred_length, 'untrusted_layout_preferences': custom_template},
        user=user, encounter_id=encounter_id)


async def refine_quick_documentation(context, text, instruction, template, *, custom_template=None, user, encounter_id):
    scope_guard(instruction)
    if custom_template:
        scope_guard(json.dumps(custom_template, ensure_ascii=False))
    return await checked_quick_text(context, 'quick_refine',
        {'template': template, 'untrusted_existing_report': text,
         'untrusted_clinician_edit_instruction': instruction, 'untrusted_layout_preferences': custom_template},
        user=user, encounter_id=encounter_id)


async def wound_consultant(context, message, history, *, user, encounter_id):
    scope_guard(message)
    if not 1 <= len(message.strip()) <= 2000:
        raise HTTPException(422, 'Ask a brief wound-care question')
    if re.search(r'(?i)\b(poem|song|recipe|vacation|holiday|stock price|investment|video game|javascript|python code|sports score)\b', message):
        raise HTTPException(422, 'Wound Consultant supports wound-care questions and clinical documentation')
    return await routed('consult', json.dumps({**context, 'untrusted_question': message,
        'untrusted_conversation_history': history}, ensure_ascii=False, default=str),
        user=user, encounter_id=encounter_id)


def review_triggers(facts):
    """Material evidence gaps/conflicts, not a wound diagnosis or severity score."""
    grouped = {}
    repeatable = {'allergy','medication','history','lab','culture','imaging','vascular_test','narrative','treatment_performed','patient_education'}
    for fact in facts:
        if fact.get('confirmation_state') == 'rejected' or fact.get('name') in repeatable: continue
        grouped.setdefault((fact.get('wound_id'), fact.get('name')), set()).add(fingerprint(fact.get('value')))
    triggers = ['Resolve the source conflict for ' + str(key[1]) for key, values in grouped.items() if len(values) > 1]
    by_wound = {}
    for fact in facts:
        if fact.get('confirmation_state') != 'confirmed' or fact.get('value', {}).get('present') is False: continue
        by_wound.setdefault(fact.get('wound_id'), set()).add(fact.get('name'))
    for wound, names in by_wound.items():
        if 'compression' in names and not names.intersection({'vascular_test', 'perfusion_assessment'}):
            triggers.append(f'For wound {wound or "unassigned"}, identify missing vascular evidence needed to evaluate documented compression; do not recommend a pressure.')
        if 'infection_findings' in names:
            triggers.append(f'For wound {wound or "unassigned"}, review documented infection concerns and missing assessment or escalation evidence without diagnosing infection.')
    return triggers[:6]


def audio_duration(data: bytes) -> float:
    """Decode container duration locally. Client duration is never a billing authority."""
    executable = shutil.which('ffprobe')
    if not executable: raise HTTPException(503, 'Audio validation runtime is not configured')
    with tempfile.TemporaryDirectory(prefix='wcp-audio-') as directory:
        path = Path(directory) / 'input.media'
        path.write_bytes(data)
        try:
            completed = subprocess.run([executable, '-v', 'error', '-protocol_whitelist', 'file,pipe', '-show_entries', 'format=duration:stream=codec_type,duration', '-of', 'json', str(path)], capture_output=True, timeout=12, check=True,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            metadata = json.loads(completed.stdout)
            if not any(x.get('codec_type') == 'audio' for x in metadata.get('streams', [])): raise ValueError()
            duration = float(metadata.get('format', {}).get('duration') or max(float(x.get('duration', 0)) for x in metadata['streams']))
            if not math.isfinite(duration) or not 0 < duration <= settings.max_audio_seconds: raise ValueError()
            return duration
        except (subprocess.SubprocessError, ValueError, KeyError): raise HTTPException(422, 'Audio duration is invalid or exceeds the recording limit')


async def speech_attempt(provider, data, mime, filename, seconds, *, user, encounter_id, verification_model=None, purpose='transcription'):
    primer = (PROMPTS / 'speech_primer.txt').read_text(encoding='utf-8')[:1000]
    model = {'cloudflare': '@cf/openai/whisper-large-v3-turbo', 'groq': 'whisper-large-v3-turbo', 'deepgram': 'nova-3-medical'}[provider]
    if purpose not in {'transcription', 'audio_verification'}:
        raise HTTPException(422, 'Unsupported speech purpose')
    if verification_model is not None:
        if provider != 'groq' or verification_model != 'whisper-large-v3' or purpose != 'audio_verification':
            raise HTTPException(422, 'Unsupported speech verification model')
        model = verification_model
    seconds = max(10, seconds) if provider == 'groq' else seconds
    price = GROQ_FULL_WHISPER_MICROS_PER_MINUTE if model == 'whisper-large-v3' else SPEECH_PRICES[provider]
    normalized = math.ceil(Decimal(str(seconds)) / 60 * price)
    async def perform():
        async with httpx.AsyncClient(timeout=settings.provider_timeout_seconds, follow_redirects=False) as client:
            if provider == 'cloudflare':
                response = await client.post(f'https://api.cloudflare.com/client/v4/accounts/{settings.cloudflare_account_id}/ai/run/{model}',
                    headers={'Authorization': 'Bearer ' + settings.cloudflare_api_token},
                    json={'audio': base64.b64encode(data).decode(), 'task': 'transcribe', 'initial_prompt': primer, 'vad_filter': True, 'condition_on_previous_text': False})
            elif provider == 'groq':
                response = await client.post('https://api.groq.com/openai/v1/audio/transcriptions', headers={'Authorization': 'Bearer ' + settings.groq_api_key},
                    data={'model': model, 'prompt': primer, 'response_format': 'verbose_json', 'temperature': '0'}, files={'file': (filename, data, mime)})
            else:
                response = await client.post('https://api.deepgram.com/v1/listen', params={'model': model, 'smart_format': 'true', 'utterances': 'true', 'mip_opt_out': 'true'},
                    headers={'Authorization': 'Token ' + settings.deepgram_api_key, 'Content-Type': mime}, content=data)
            response.raise_for_status()
            return response.json()
    def validate(raw):
        if provider == 'cloudflare':
            if raw.get('success') is False: raise ValueError('Provider failure')
            raw = raw.get('result', raw)
        if provider == 'deepgram':
            alt = raw['results']['channels'][0]['alternatives'][0]
            text = alt['transcript']
            segments = [{'text': x['transcript'], 'start': x['start'], 'end': x['end'], 'confidence': x.get('confidence'), 'speaker': x.get('speaker')} for x in raw['results'].get('utterances', [])]
        else: text, segments = raw.get('text'), raw.get('segments') or []
        limit = 8000 if purpose == 'audio_verification' else settings.max_text_chars
        if not isinstance(text, str) or len(text) > limit or (purpose == 'audio_verification' and not text.strip()): raise ValueError('Invalid transcript')
        clean = []
        for segment in segments[:1000]:
            if not isinstance(segment, dict): continue
            item = {key: segment[key] for key in ('text', 'start', 'end', 'speaker', 'confidence', 'avg_logprob', 'no_speech_prob') if key in segment}
            clean.append(item)
        warnings = ['Review negation, units, laterality, numbers and speaker attribution before confirmation.']
        if not text.strip(): warnings.append('No speech was detected. No clinical findings were inferred.')
        return {'text': text, 'segments': clean, 'warnings': warnings}
    return await metered_call(user=user, encounter_id=encounter_id, task=purpose, provider=provider, model=model,
        payload_hash=fingerprint([sha256(data).hexdigest(), mime, model, primer]), estimate=normalized,
        perform=perform, measure=lambda raw: (normalized, {'audio_seconds': seconds, 'audio_minutes': seconds / 60}, None), validate=validate)


async def verify_speech_clip(data, *, user, encounter_id, verifier='stronger'):
    """No source transcript or uncertainty hint is supplied to the second speech pass."""
    if verifier not in {'stronger', 'medical'}:
        raise HTTPException(422, 'Select stronger speech verification or explicit medical transcription')
    if not data or len(data) > 1_100_000:
        raise HTTPException(413, 'Verification accepts only a normalized short audio clip')
    seconds = audio_duration(data)
    if not math.isfinite(seconds) or not 0 < seconds <= 30:
        raise HTTPException(422, 'Speech verification is limited to 30 seconds')
    options = []
    if verifier == 'medical':
        if settings.deepgram_enabled and settings.deepgram_api_key:
            options.append(('deepgram', None, 'explicit_medical_transcription'))
    else:
        if settings.groq_api_key:
            options.append(('groq', 'whisper-large-v3', 'full_whisper_v3_verification'))
        if settings.cloudflare_api_token and settings.cloudflare_account_id:
            options.append(('cloudflare', None, 'repeat_transcription_fallback'))
    if not options:
        raise HTTPException(503, 'The requested short-window speech verifier is not configured')
    errors = []
    for provider, model, method in options:
        try:
            result = await speech_attempt(provider, data, 'audio/wav', 'verification-window.wav', seconds,
                user=user, encounter_id=encounter_id, verification_model=model, purpose='audio_verification')
            result['verifier'] = {'provider': provider, 'model': result['usage']['model'], 'method': method}
            if method == 'repeat_transcription_fallback':
                result['warnings'].append('The configured Turbo speech service repeated this clip; a stronger or independent medical verifier was not used.')
            return result
        except HTTPException as error:
            if error.status_code not in {502, 503, 504}:
                raise
            errors.append(error)
    raise errors[-1]


async def transcribe(data, mime, filename, duration_seconds=None, *, user, db=None, encounter_id):
    if len(data) > settings.max_audio_mb * 1024**2: raise HTTPException(413, 'Audio exceeds size limit')
    seconds = audio_duration(data)
    available = []
    if settings.cloudflare_api_token and settings.cloudflare_account_id: available.append('cloudflare')
    if settings.groq_api_key: available.append('groq')
    if settings.deepgram_enabled and settings.deepgram_api_key: available.append('deepgram')
    if settings.speech_provider != 'auto': available = [p for p in available if p == settings.speech_provider]
    if not available: raise HTTPException(503, 'Speech providers are not configured. Typed transcripts and manual evidence remain available.')
    errors = []
    for provider in available[:3]:
        try:
            result = await speech_attempt(provider, data, mime, filename, seconds, user=user, encounter_id=encounter_id)
            if errors: result['warnings'].append('A configured fallback speech provider was used.')
            return result
        except HTTPException as exc:
            if exc.status_code not in {502, 503, 504}: raise
            errors.append(exc)
    raise errors[-1]


@router.get('/admin/providers')
def provider_status(user: User = Depends(require_admin)):
    return {'price_registry_version': PRICE_VERSION, 'models': list(MODEL_PRICES), 'clinical_data_enabled': settings.clinical_data_enabled, 'execution_enabled': settings.provider_execution_enabled,
        'providers': [{'name': name, 'configured': bool(key), 'clinical_approved': bool(getattr(settings, name + '_phi_approved', False))}
            for name, key in [('openrouter', settings.openrouter_api_key), ('cloudflare', settings.cloudflare_api_token), ('groq', settings.groq_api_key), ('deepgram', settings.deepgram_api_key)]]}
