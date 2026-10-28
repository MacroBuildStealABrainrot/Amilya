"""Bounded provider gateway. Source material is data, never executable authority.

Every attempt reserves and settles in its own short transaction. No network call
holds a business write lock. Encrypted replay results prevent duplicate inference.
"""
from __future__ import annotations

import logging
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
from .error_feedback import preflight_error
import httpx
from PIL import Image, ImageOps, UnidentifiedImageError
from sqlalchemy import select

from .core import settings
from .db import SessionLocal
from .identity import User, check_rate, require_admin
from .provider_models import ProviderResult
from .performance import timed
from .billing_models import UsageOperation
from .schemas import Name, Measurement, Location, Tissue, Pain, Observation, validated_value
from . import billing
from .kosmo import select_route
from . import diagnostics

router = APIRouter()


class OutputLimitError(ValueError):
    """The provider exhausted its output allowance; partial findings are unusable."""


class ModelIdentityError(ValueError):
    """A gateway response does not identify the model that was requested."""


request_operation: ContextVar[str | None] = ContextVar('request_operation', default=None)
document_operations: ContextVar[list | None] = ContextVar('document_operations',default=None)
PROMPTS = Path(__file__).parent / 'prompts'
PRICE_VERSION = 'normalized-2026-09-05'
# USD per million tokens: durable list-price normalization, independent of discounts.
MODEL_PRICES = {
    'meta/muse-spark-1.3': ('1.25', '4.25', '.15'),
    'openai/gpt-6-luna': ('.10', '.50', '.02'),
    'openai/gpt-6.1-sol': ('2', '10', '.2'),
    'openai/gpt-6-astra': ('10', '50', '.4'),
    'anthropic/claude-sonnet-5.5': ('2', '10', '.25'),
}
# Reviewed against the public OpenRouter endpoint catalog on 2026-10-26.
# Exact aliases only: never accept arbitrary date suffixes or another model.
MODEL_SNAPSHOTS = {
    'openai/gpt-6-luna': frozenset({'openai/gpt-6-luna-20260922'}),
    'openai/gpt-6.1-sol': frozenset({'openai/gpt-6.1-sol-20260929'}),
    'openai/gpt-6-astra': frozenset({'openai/gpt-6-astra-20260903'}),
    'anthropic/claude-sonnet-5.5': frozenset({'anthropic/claude-sonnet-5.5-20260928'}),
}
FINISH_REASONS = frozenset({'stop', 'length', 'content_filter', 'tool_calls', 'function_call'})


def known_model_id(value):
    return isinstance(value, str) and (value in MODEL_PRICES or any(value in ids for ids in MODEL_SNAPSHOTS.values()))


def model_identity_matches(selected, reported):
    return reported is None or isinstance(reported, str) and (reported == selected or reported in MODEL_SNAPSHOTS.get(selected, ()))


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
# Report stages that may be re-attempted (never charged for a failed attempt) when the
# same request key comes back after a timeout or upstream failure. Bounded per key.
REPORT_STAGE_TASKS = frozenset({'quick_draft', 'quick_qa', 'quick_repair', 'quick_refine', 'improve_report'})
MAX_REPORT_STAGE_ATTEMPTS = 3  # total per report step and request key, INCLUDING the first attempt
MAX_REPORT_STAGE_RETRIES = MAX_REPORT_STAGE_ATTEMPTS - 1
MAX_MEDIA_RETRIES = 3  # transcription and photo: unchanged from earlier versions
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
        raise preflight_error(503, 'Secure application storage must be configured before AI processing')
    return Fernet(base64.urlsafe_b64encode(sha256(('provider-replay:' + settings.app_secret).encode()).digest()))


def privacy_guard(provider: str):
    if not settings.provider_execution_enabled:
        raise preflight_error(503, 'Live AI is paused until the owner activates provider execution')
    if settings.clinical_data_enabled:
        approved = getattr(settings, provider + '_phi_approved', False)
        if provider == 'groq': approved = approved and settings.groq_zdr_enabled
        if not approved:
            raise preflight_error(503, 'This provider is awaiting clinical-data privacy approval')
    if provider == 'openrouter' and not settings.openrouter_require_zdr:
        raise preflight_error(503, 'Zero-retention routing must be enabled')


def prompt(task: str) -> str:
    boundary = (PROMPTS / '00_healthcare_boundary.system.md').read_text(encoding='utf-8')
    role = (PROMPTS / (TASK_FILES[task] + '.system.md')).read_text(encoding='utf-8')
    from .documentation import DOCUMENT_TASKS
    rules = (PROMPTS / '06_documentation_rules.system.md').read_text(encoding='utf-8') if task in DOCUMENT_TASKS | {'draft', 'qa', 'quick_qa'} else ''
    from .documentation_layers import instructions
    layers=instructions() if rules else ''
    return boundary + '\n\n' + rules + '\n\n' + layers + '\n\n' + role


def scope_guard(text: str):
    # Explicit jailbreak attempts cannot become instructions to the model. Clinical
    # discussion of these words is still usable through manual evidence entry.
    attack = re.search(r'(?is)\b(?:ignore|override|disregard)\s+(?:(?:all|the|any)\s+)?(?:previous\s+instructions?|system\s+(?:prompt|instructions?|safety\s+boundary)|safety\s+(?:rules|instructions?|boundary))\b|\breveal\s+(?:the\s+)?(?:system\s+prompt|api\s*key|secret\s+key)\b|\b(?:set|make)\s+(?:me\s+)?(?:an?\s+)?(?:admin|unlimited)(?:\s+(?:with\s+)?unlimited\s+credits)?\b|\b(?:write|create)\s+(?:ransomware|malware)\b', text)
    if attack:
        raise HTTPException(422, 'This request contains instructions outside the clinical evidence workflow')


def normalized_tokens(model: str, usage: dict) -> tuple[int, dict]:
    prices = MODEL_PRICES.get(model)
    if not prices: raise preflight_error(503, 'Model needs a reviewed price registry entry')
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
        invalid_photo_count = 0
        for item in data['facts']:
            try:
                if not isinstance(item, dict) or set(item) - {'name', 'value', 'quote'}: raise ValueError('Invalid finding')
                name, value = item.get('name'), item.get('value')
                if task == 'image' and name not in PHOTO_NAMES:
                    warnings.append('A finding outside visible photo evidence was excluded.')
                    continue
                if name not in get_args(Name): raise ValueError('Unknown field')
                quote = item.get('quote', '' if task == 'image' else None)
                if not isinstance(quote, str) or len(quote) > 4000: raise ValueError('Invalid supporting quote')
                if task == 'image' and quote: raise ValueError('Photo cannot supply a text quotation')
                if source is not None and (not quote.strip() or quote not in source):
                    warnings.append('A suggestion without an exact source quotation was excluded.')
                    continue
                checked = validated_value(name, value)
                if task == 'image' and name == 'wound_tissue' and isinstance(checked, dict) and (checked.get('text') or '').strip():
                    # Percentages are charted in the sentence; extra numeric fields read as robotic duplicates.
                    checked = {'text': checked['text']}
                facts.append({'name': name, 'value': checked, 'quote': quote})
            except (ValueError, KeyError, TypeError):
                if task != 'image':raise
                invalid_photo_count += 1
                # A bad candidate must not discard independently valid visual observations.
                # No rejected field names, values, quotes, or provider text enter diagnostics.
                warnings.append('An unusable photo finding was excluded. Review the remaining observations.')
                diagnostics.event({'source':'server','stage':'photo_finding_validation','status':'failed','error_code':'INVALID_PHOTO_FINDING'})
        result = {'facts': facts, 'warnings': warnings + extra[:16]}
        if quality:
            if not facts and invalid_photo_count:
                raise ValueError('No valid visual findings after candidate validation')
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


# Only documented technical codes may leave this parser. Provider messages,
# native codes, raw metadata and generated content can contain clinical data.
PROVIDER_ERROR_STATUS = {
    'authentication': 401, 'permission_denied': 403, 'payment_required': 402,
    'rate_limit_exceeded': 429, 'provider_overloaded': 503,
    'provider_unavailable': 502, 'timeout': 504, 'server': 500,
    'content_policy_violation': 403, 'refusal': 403,
    'context_length_exceeded': 400, 'max_tokens_exceeded': 400,
    'token_limit_exceeded': 400, 'string_too_long': 400,
    'invalid_request': 400, 'invalid_prompt': 400, 'not_found': 404,
    'precondition_failed': 400, 'payload_too_large': 413, 'unprocessable': 422,
    'invalid_image': 400, 'image_too_large': 400, 'image_too_small': 400,
    'unsupported_image_format': 400, 'image_not_found': 404,
    'image_download_failed': 400, 'unmapped': 502,
}


def provider_error_response(response, raw):
    """Normalize top-level/choice errors without retaining their sensitive body.

    HTTP 200 does not prove generation succeeded. Never retry an error envelope:
    partial generation and vendor cost may be unknown.
    """
    if not isinstance(raw, dict):
        return None
    info = raw.get('error')
    if info is None:
        choices = raw.get('choices')
        if isinstance(choices, list):
            info = next((choice['error'] for choice in choices
                         if isinstance(choice, dict) and choice.get('error') is not None), None)
    if info is None:
        return None
    metadata = info.get('metadata') if isinstance(info, dict) else None
    category = metadata.get('error_type') if isinstance(metadata, dict) else None
    if not isinstance(category, str) or category not in PROVIDER_ERROR_STATUS:
        category = 'unknown'
    code = info.get('code') if isinstance(info, dict) else None
    if isinstance(code, str) and code.isascii() and code.isdigit() and len(code) <= 3:
        code = int(code)
    if category in PROVIDER_ERROR_STATUS:
        code = PROVIDER_ERROR_STATUS[category]
    elif type(code) is not int or not 400 <= code <= 599:
        code = 502
    delay = retry_after_delay(response)
    return httpx.Response(code, request=response.request,
        extensions={'wcp_transport_status': response.status_code, 'wcp_error_type': category},
        headers={'Retry-After': str(math.ceil(delay))} if delay is not None else {},
        json={'error': {'type': 'insufficient_quota' if quota_rejection(response) is True else 'upstream_error'}})


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


def photo_error_headers(usage, exhausted=False):
    """Closed technical categories, never provider error text."""
    category = {
        'provider_credits':'PROVIDER_QUOTA', 'provider_access':'PROVIDER_AUTH',
        'route_unavailable':'MODEL_UNAVAILABLE', 'request_incompatible':'REQUEST_INCOMPATIBLE',
        'provider_timeout':'PROVIDER_TIMEOUT', 'invalid_response':'INVALID_RESPONSE',
        'provider_connection':'NETWORK_UNAVAILABLE', 'provider_rate_limit':'RATE_LIMITED',
        'response_processing':'RESPONSE_PROCESSING_FAILED', 'output_limit':'OUTPUT_LIMIT',
    }.get(usage.get('failure_reason'), 'PROVIDER_UNAVAILABLE')
    permanent = category in {'PROVIDER_QUOTA','PROVIDER_AUTH','MODEL_UNAVAILABLE','REQUEST_INCOMPATIBLE','OUTPUT_LIMIT'}
    return {**(retry_after_headers(usage) or {}),
            'X-WCP-Error': 'RETRY_LIMIT' if exhausted and not permanent else category,
            'X-WCP-Retryable': 'false' if permanent or exhausted else 'true',
            **({'X-WCP-Upstream-Status':str(usage['upstream_status'])} if type(usage.get('upstream_status')) is int and 100 <= usage['upstream_status'] <= 599 else {})}


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


async def rate_limit_backoff(seconds):
    await asyncio.sleep(seconds)


async def metered_call(*, user: User, encounter_id: str, task: str, provider: str, model: str, payload_hash: str,
                       estimate: int, perform, measure, validate, prior_attempts=None, replay_invalid_failure=False) -> dict:
    diagnostics.bind(user)
    diagnostics.event({'source':'router','stage':'provider_preflight','selected_model':model,'provider':provider,'status':'selected','finished_at':diagnostics.timestamp()})
    privacy_guard(provider)
    from . import render_owner_test
    if render_owner_test.required() and (provider != 'openrouter' or not render_owner_test.allows(user)):
        raise preflight_error(503, 'The synthetic test allowance is unavailable for this account or has expired')
    check_rate('provider:' + user.id, limit=60)
    crypt = cipher()
    seed = request_operation.get() or str(uuid.uuid4())
    operation = fingerprint([user.id, encounter_id, seed, task, provider, model, payload_hash])
    resumed_attempt = 0
    if getattr(settings, 'ai_leases_enabled', False):
        # Stuck-credit recovery (development flag): a hold whose worker stopped renewing it is given back, never charged.
        _release_if_stale(user, operation)
    if task in {'transcription', 'image'} | REPORT_STAGE_TASKS:
        base_operation=operation
        # Resume successful checkpoints; retry only settled, waived failures.
        # Unknown/in-flight operations never qualify for another request.
        with SessionLocal() as retry_db:
            for retry_number in range(MAX_REPORT_STAGE_RETRIES if task in REPORT_STAGE_TASKS else MAX_MEDIA_RETRIES):
                previous=retry_db.scalar(select(UsageOperation).where(UsageOperation.workspace_id==user.workspace_id,UsageOperation.operation_id==operation))
                if previous is not None and getattr(settings, 'ai_leases_enabled', False) and previous.status=='reserved' and _release_if_stale(user, operation):
                    retry_db.expire_all()
                    previous=retry_db.scalar(select(UsageOperation).where(UsageOperation.workspace_id==user.workspace_id,UsageOperation.operation_id==operation))
                interrupted=billing.is_lease_expired_release(previous)
                if not previous or not interrupted and (previous.status!='settled' or previous.usage.get('charge_waived') is not True):break
                if task in REPORT_STAGE_TASKS:
                    # A report stage that timed out or failed upstream was settled
                    # without charge. Let the same request key try again (bounded),
                    # instead of replaying the old failure forever. Permanent
                    # configuration failures (credits, access, routing) never advance.
                    if previous.usage.get('failure_reason') in {'provider_credits','provider_access','route_unavailable','request_incompatible','output_limit'}:
                        break
                    attempts = previous.usage.get('attempts', [])
                    if not attempts or attempts[-1].get('error_code') not in {'502','503','504'}:
                        break
                if task == 'image':
                    # Retry only a settled, unusable photo result. Keep the same
                    # source identity, but reserve one deterministic child attempt.
                    # A success, unknown charge or running request never advances.
                    reason = previous.usage.get('failure_reason')
                    if replay_invalid_failure and reason == 'invalid_response':break
                    if reason in {'provider_credits','provider_access','route_unavailable','request_incompatible','output_limit'}:
                        break
                    if retry_after_headers(previous.usage):
                        raise HTTPException(503, 'Photo assessment is waiting for provider capacity.',
                                            headers=retry_after_headers(previous.usage))
                    attempts = previous.usage.get('attempts', [])
                    if not attempts or attempts[-1].get('error_code') not in {'502','503','504'}:
                        break
                operation=fingerprint([base_operation,'retry',retry_number+1])
                resumed_attempt = retry_number + 1
    if getattr(settings, 'ai_leases_enabled', False) and resumed_attempt:
        _release_if_stale(user, operation)  # the last allowed attempt may itself be a dead hold
    if task == 'image':
        diagnostics.event({'source':'server','stage':'photo_attempt','status':'selected',
            'selected_model':model,'provider':provider,'retry_count':resumed_attempt,
            'operation_id':operation,'fallback_used':False})
    with SessionLocal() as ledger_db:
        old = ledger_db.scalar(select(ProviderResult).where(ProviderResult.workspace_id == user.workspace_id, ProviderResult.operation_id == operation))
        if old:
            if old.user_id != user.id or old.request_hash != payload_hash: raise HTTPException(409, 'Request identity mismatch')
            if document_operations.get() is not None:document_operations.get().append(operation)
            try:
                replay=json.loads(crypt.decrypt(old.ciphertext, ttl=86400))
                cached_usage=replay.get('usage',{})
                diagnostics.event({'source':'replay','stage':task,'status':'success','selected_model':cached_usage.get('model'),'provider':cached_usage.get('provider'),'operation_id':operation,'original_completed_at':old.created_at.isoformat(),'new_provider_request':False,'cost_usd':0,'cost_basis':'no_new_provider_request'})
                return replay
            except InvalidToken: raise HTTPException(409, 'This completed request has expired. Review the case before creating a new request.')
        reservation = billing.reserve(ledger_db, user, operation, estimate, encounter_id, task)
        if not reservation['created']:
            previous = ledger_db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == user.workspace_id,
                UsageOperation.operation_id == operation, UsageOperation.user_id == user.id))
            if billing.is_lease_expired_release(previous):
                raise HTTPException(503, 'This AI request was interrupted on the server. No charge was made. Try again.',
                                    headers={'X-WCP-Retryable': 'true', 'X-WCP-Error': 'PROVIDER_UNAVAILABLE'})
            if previous and previous.status == 'settled' and previous.usage.get('charge_waived') is True:
                attempts = previous.usage.get('attempts', [])
                if 1 <= len(attempts) <= MAX_RATE_LIMIT_ATTEMPTS and attempts[-1].get('status') == 'failed':
                    code = attempts[-1].get('error_code')
                    if code in {'502', '503', '504'}:
                        # Replay the settled failure so a configured fallback can
                        # reach its cached success; never perform or charge again.
                        diagnostics.event({'source':'replay','stage':task,'status':'failed','selected_model':attempts[-1].get('model'),'provider':attempts[-1].get('provider'),'operation_id':operation,'new_provider_request':False,'error_code':code})
                        exhausted = task == 'image' and resumed_attempt >= 3
                        message = ('We could not assess this photo after several attempts. Your photo is saved. Retake it, try later, or chart by hand.'
                                   if exhausted else
                                   'This provider attempt previously failed. No new request or charge was made; a configured fallback may supply its saved result.')
                        raise HTTPException(int(code), message,
                            headers=photo_error_headers(previous.usage, exhausted))
            raise HTTPException(409, 'This AI request is already processing or awaiting reconciliation. Refresh the case before retrying.')
    if document_operations.get() is not None:document_operations.get().append(operation)
    started = time.monotonic()
    usage, normalized, billed = {}, 0, None
    result, error = None, None
    heartbeat = _start_lease(user, operation) if getattr(settings, 'ai_leases_enabled', False) else None
    try:
        raw = await perform()
        normalized, usage, billed = measure(raw)
        result = validate(raw)
    except asyncio.CancelledError:
        # Cancellation must not leave a reserved credit hold with no result.
        if heartbeat is not None:
            heartbeat.cancel()
        with SessionLocal() as cancelled_db:
            billing.release(cancelled_db, user, operation, "provider_cancelled")
        raise
    except httpx.TimeoutException:
        usage['failure_reason'] = 'provider_timeout'
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
                      if reason == 'provider_credits' else 'The AI provider rate-limited this request. Wait before retrying.')
            delay = retry_after_delay(exc.response)
            if delay is not None:
                usage['retry_not_before'] = time.time() + math.ceil(delay)
        elif code == 400:
            reason = 'request_incompatible'
            detail = 'The AI provider rejected the request format or model parameters. The owner must review the integration; manual charting remains available.'
        category = exc.response.extensions.get('wcp_error_type', 'unknown')
        if category not in PROVIDER_ERROR_STATUS:
            category = 'unknown'
        if category in {'content_policy_violation', 'refusal'}:
            reason = 'request_incompatible'
            detail = 'The AI provider declined this assessment. No findings were accepted. Manual charting remains available.'
        elif category in {'invalid_image', 'image_too_large', 'image_too_small', 'unsupported_image_format', 'image_not_found', 'image_download_failed'}:
            reason = 'request_incompatible'
            detail = 'The AI provider could not read the uploaded image. No findings were accepted. Manual charting remains available.'
        elif category in {'context_length_exceeded', 'max_tokens_exceeded', 'token_limit_exceeded', 'string_too_long'}:
            reason = 'output_limit'
            detail = 'The AI provider reached a token or length limit. No partial findings were accepted. Manual charting remains available.'
        elif category == 'timeout':
            reason = 'provider_timeout'
        logging.getLogger('wcp.providers').warning('provider_request_failed transport=%d status=%d category=%s',
            exc.response.extensions.get('wcp_transport_status', code), code, category)
        usage.update({'upstream_status': exc.response.extensions.get('wcp_transport_status', code), 'failure_reason': reason})
        error = HTTPException(503 if code in {401, 402, 403, 404, 429} else 502, detail,
                              headers={**(retry_after_headers(usage) or {}), 'X-WCP-Upstream-Status': str(usage['upstream_status']),
                                       **({'X-WCP-Provider-Error': category} if task == 'image' or category != 'unknown' else {}),
                                       **({'X-WCP-Retryable': 'false'} if task == 'image' and exc.response.extensions.get('wcp_transport_status') == 200 else {})})
    except OutputLimitError:
        usage['failure_reason'] = 'output_limit'
        error = HTTPException(502, 'The AI response reached its output limit before completion. No partial findings were accepted. The source is preserved; the administrator must review the output allowance.')
    except ModelIdentityError:
        usage['failure_reason'] = 'request_incompatible'
        error = HTTPException(502, 'The provider returned an unexpected model. The owner must review routing; no findings were accepted.')
    except (ValueError, KeyError, TypeError, IndexError):
        usage['failure_reason'] = 'invalid_response'
        error = HTTPException(502, 'The provider returned an invalid clinical response. No unsupported findings were accepted.')
    except httpx.HTTPError:
        usage['failure_reason'] = 'provider_connection'
        error = HTTPException(503, 'The provider connection failed. Your source remains available for retry.')
    except HTTPException as exc: error = exc
    except Exception:
        usage['failure_reason'] = 'response_processing'
        error = HTTPException(502, 'The provider response could not be processed safely')
    finally:
        if heartbeat is not None:
            heartbeat.cancel()
    earlier = list(prior_attempts or [])
    diagnostics.outcome(task,model,provider,error is None,usage,normalized,billed)
    if earlier:
        # A rate-limit rejection usually has no generation, but vendor cost for
        # that attempt was not supplied. Do not label the total measured zero.
        billed = None
    usage.update({'latency_ms': int((time.monotonic() - started) * 1000), 'retry_count':resumed_attempt, 'attempt_count': len(earlier) + 1, 'price_registry_version': PRICE_VERSION,
                  'attempts': [*earlier, {'provider': provider, 'model': model, 'status': 'failed' if error else 'completed',
                                'error_code': str(error.status_code) if error else '', 'normalized_micros': normalized}]})
    with SessionLocal() as ledger_db:
        # Unknown vendor costs are NULL, never represented as measured zero.
        try:
            settled = billing.settle(ledger_db, user, operation, normalized, provider, model, usage, actual_cost_micros=billed, commit=False, waive_charge=bool(error))
        except HTTPException as fenced:
            # Our hold was released while we worked (lease expired). Nothing is charged or saved; the customer may retry.
            if fenced.status_code == 409 and billing.is_lease_expired_release(ledger_db.scalar(select(UsageOperation).where(
                    UsageOperation.workspace_id == user.workspace_id, UsageOperation.operation_id == operation))):
                raise HTTPException(503, 'This AI request took too long on the server and was cancelled without charge. Try again.',
                                    headers={'X-WCP-Retryable': 'true', 'X-WCP-Error': 'PROVIDER_UNAVAILABLE'}) from None
            raise
        if result is not None:
            result['usage'] = {**usage, 'normalized_micros': normalized, 'charged_credits': settled['charged_micros'] / 1000,
                               'actual_cost_micros': billed, 'operation_id': operation, 'provider': provider, 'model': model}
            ledger_db.add(ProviderResult(workspace_id=user.workspace_id, user_id=user.id, operation_id=operation,
                request_hash=payload_hash, ciphertext=crypt.encrypt(json.dumps(result, ensure_ascii=False).encode())))
        ledger_db.commit()
    if error:
        if usage.get('failure_reason'):
            error.headers = {**photo_error_headers(usage, task == 'image' and resumed_attempt >= 3), **(error.headers or {})}
        raise error
    return result


def _release_if_stale(user, operation_id):
    """If this request's hold has not been renewed for its lease time plus the grace period, give it back (never charge)."""
    with SessionLocal() as db:
        row = db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == user.workspace_id, UsageOperation.operation_id == operation_id))
        if row is None or row.user_id != user.id or not billing.lease_is_stale(row, time.time(), getattr(settings, 'ai_lease_takeover_grace_seconds', 60)):
            return False
        return billing.release_stale_hold(db, row)


def _start_lease(user, operation_id):
    """Claim the fresh hold for this worker and renew it in the background while the provider call runs."""
    token = str(uuid.uuid4())
    with SessionLocal() as db:
        if not billing.lease_started(db, user, operation_id, token):
            return None
    async def renew():
        while True:
            await asyncio.sleep(max(1, getattr(settings, 'ai_lease_seconds', 120) // 3))
            try:
                with SessionLocal() as db:
                    if not billing.lease_heartbeat(db, user.workspace_id, operation_id, token):
                        return  # lease no longer belongs to this worker
            except asyncio.CancelledError:
                raise
            except Exception:
                # Transient database failures must not permanently end lease renewal.
                logging.getLogger(__name__).warning("AI lease heartbeat failed; retrying", exc_info=True)
    return asyncio.create_task(renew())


async def structured(task, content, model, *, user, encounter_id, source=None, max_tokens=1800, routing=None, replay_invalid_failure=False):
    if not settings.openrouter_api_key: raise preflight_error(503, 'AI is not configured; manual evidence and documentation are available')
    if model not in MODEL_PRICES: raise preflight_error(503, 'Model configuration needs review')
    system = prompt(task) + '\nReturn only JSON matching the response schema. Do not add diagnostic or treatment certainty beyond the source.'
    payload = {'model': model, 'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': content}],
        ('max_completion_tokens' if model.startswith('openai/') else 'max_tokens'): max_tokens,
        'response_format': {'type': 'json_schema', 'json_schema': {'name': 'clinical_' + task, 'strict': False, 'schema': result_schema(task)}},
        'provider': {'zdr': True, 'data_collection': 'deny', 'require_parameters': True}, 'stream': False,'usage':{'include':True}}
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
                response = await diagnostics.post(client,settings.openrouter_base_url.rstrip('/') + '/chat/completions',provider='openrouter',model=model,stage=task,retry=attempt,effort=routing.get('effort_level') if routing else None,
                    headers={'Authorization': 'Bearer ' + settings.openrouter_api_key, 'HTTP-Referer': settings.public_base_url, 'X-Title': 'WoundCoPilot'}, json=payload)
                delay = rate_limit_retry_delay(response) if attempt == 0 else None
                if delay is None:
                    break
                prior_attempts.append({'provider': 'openrouter', 'model': model, 'status': 'failed',
                                       'error_code': '429', 'normalized_micros': 0})
                await rate_limit_backoff(delay)
            response.raise_for_status()
            raw = parse_provider_json(response.text)
            synthetic = provider_error_response(response, raw)
            if synthetic is not None:
                raise httpx.HTTPStatusError('Provider error envelope', request=response.request, response=synthetic)
            return raw
    async def perform():
        # Socket timeouts alone allow a slow/dribbling response to run indefinitely.
        # Bound every text/vision stage, including backoff, inside credit settlement.
        timeout = settings.provider_timeout_seconds
        if task in REPORT_STAGE_TASKS:
            timeout = min(REPORT_STAGE_TIMEOUT_SECONDS, timeout)
        try:
            return await asyncio.wait_for(perform_attempts(), timeout=timeout)
        except TimeoutError:
            raise httpx.TimeoutException('The AI stage exceeded its bounded wait') from None
    def measure(raw):
        vendor = raw.get('usage') or {}
        amount, safe = normalized_tokens(model, vendor)
        reported = raw.get('model')
        safe['reported_model'] = reported if known_model_id(reported) else None
        safe['model_identity'] = 'unreported' if reported is None else 'confirmed' if model_identity_matches(model, reported) else 'mismatch'
        safe['max_output_tokens'] = max_tokens
        choices = raw.get('choices')
        choice = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else {}
        finish = choice.get('finish_reason')
        if isinstance(finish, str) and finish in FINISH_REASONS:
            safe['finish_reason'] = finish
        if routing: safe['routing'] = routing
        return amount, safe, actual_cost(vendor)
    def validate(raw):
        if not model_identity_matches(model, raw.get('model')):
            raise ModelIdentityError('Provider returned a different or unrecognized model')
        choice = raw['choices'][0]
        if choice.get('finish_reason') == 'length': raise OutputLimitError('Output limit reached')
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
        prior_attempts=prior_attempts, replay_invalid_failure=replay_invalid_failure)


def configured_route(task, content):
    """Keep automatic effort selection while honoring the owner's model settings."""
    route = select_route(TASK_FAMILIES.get(task, task), content)
    family = TASK_FAMILIES.get(task, task)
    if task == 'image': selected = settings.model_vision
    elif task == 'challenge': selected = settings.model_challenger
    elif route['effort_level'] == 'high': selected = settings.model_frontier
    elif family in {'draft', 'qa'}: selected = settings.model_documentation
    elif family in {'review', 'holistic', 'assistant'}: selected = settings.model_review
    else: selected = settings.model_extraction
    if selected not in MODEL_PRICES:
        raise preflight_error(503, 'Configured AI model is not in the approved price registry')
    return {**route, 'model': selected, 'reason': 'configured_' + task, 'effort_reason': route.get('reason')}


@timed('ai_stage')
async def routed(task, content, model=None, **kwargs):
    if task not in REPORT_STAGE_TASKS and (diagnostics.active.get() or {}).get('task')!='deep_review':
        diagnostics.task({'image':'photo_assessment','consult':'ask_consultant','assistant':'ask_consultant','review':'clinical_review'}.get(task,task))
    route = configured_route(task, content)
    # Extraction emits structured facts, not a short summary. A live 2,426-token
    # input exhausted the old 1,800-token completion cap (2026-10-26). Keep low
    # reasoning effort while allowing bounded room for complete JSON and evidence.
    minimum = {'low': 1800, 'medium': 5000, 'high': 8000}[route['effort_level']]
    if task in {'extract', 'document'}:
        minimum = max(minimum, min(16000, max(8000, len(content) // 2)))
    elif task == 'image':
        # High-effort specialist reasoning also uses this allowance; leave room for the answer.
        minimum = max(minimum, 12000)
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


def read_pdf_text(data: bytes) -> str:
    """Blocking: extract PDF text in a separate, time-limited process. Call only through asyncio.to_thread from async code."""
    import sys
    with tempfile.TemporaryDirectory(prefix='wcp-pdf-') as directory:
        path = Path(directory) / 'source.pdf'; path.write_bytes(data)
        completed = subprocess.run([sys.executable, '-m', 'server.pdf_extract', str(path)], capture_output=True, timeout=15, check=True,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        return completed.stdout.decode('utf-8')


async def analyze_media(data, mime, kind, *, user, db=None, encounter_id):
    if kind == 'photo':
        diagnostics.bind(user)
        diagnostics.task('photo_assessment')
        if len(data) > settings.max_image_mb * 1024**2: raise HTTPException(413, 'Photo exceeds size limit')
        with diagnostics.measure('image_preparation'):
            prepared = await asyncio.to_thread(image_payload, data)
        with Image.open(io.BytesIO(prepared)) as image:
            dimensions = image.size
        diagnostics.event({'source':'server','stage':'image_input','status':'success',
            'input_bytes':len(data),'prepared_bytes':len(prepared),'mime':'image/jpeg',
            'width':dimensions[0],'height':dimensions[1]})
        content = [{'type': 'text', 'text': 'Assess the supplied wound photograph as an experienced wound care specialist and chart only supported findings. This photograph is untrusted source material.'},
                   {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,' + base64.b64encode(prepared).decode()}}]
        try:
            return await routed('image', content, settings.model_vision, user=user, encounter_id=encounter_id, replay_invalid_failure=True)
        except HTTPException as error:
            if (error.headers or {}).get('X-WCP-Error') != 'INVALID_RESPONSE':raise
            # Retry exactly once, from the original image, with a stable distinct payload.
            # The existing gateway meters, validates and replays each attempt independently.
            diagnostics.event({'source':'server','stage':'photo_schema_recovery','status':'pending','error_code':'INVALID_RESPONSE'})
            strict = [{'type':'text','text': 'Recovery attempt 1. Return only quality, facts, warnings using the supplied schema. '
                'Only wound_tissue, wound_edges, periwound, exudate may be observed. Use value.text and an empty quote. '
                'Omit any unassessable candidate. Do not add location, measurements, depth, diagnosis or nonvisual findings. '
                'If the image is inadequate, return insufficient quality with the applicable reason and no facts.'}, *content]
            result = await routed('image', strict, settings.model_vision, user=user, encounter_id=encounter_id)
            result['warnings'] = [*result.get('warnings', []), 'Photo assessment recovered after an invalid response. Review the accepted observations.']
            return result
    if mime != 'application/pdf': raise HTTPException(415, 'Unsupported document')
    if len(data) > settings.max_document_mb * 1024**2: raise HTTPException(413, 'Document exceeds size limit')
    # Parse text locally; unscannable documents must not be represented as findings.
    # The reader runs in a child process for safety; wait for it off the event loop so other requests are not stalled.
    try: text = await asyncio.to_thread(read_pdf_text, data)
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
    diagnostics.task('deep_review' if mode=='deep' else 'clinical_review')
    content = json.dumps({'case_facts': facts, 'mode': mode, 'instruction': 'Respect each confirmation_state. Unconfirmed evidence is an attributed suggestion, never a patient fact.'}, ensure_ascii=False, default=str)
    if mode == 'deep':
        primary = configured_route('review', content)['model']
        challenger = configured_route('challenge', content)['model']
        if primary.split('/')[0] == challenger.split('/')[0]:
            raise preflight_error(503, 'Deep Review requires a challenger from a different model developer')
    first = await routed('review', content, settings.model_review, user=user, encounter_id=encounter_id)
    triggers = review_triggers(facts)
    first['routing'] = {**first.get('routing', {}), 'mode': mode, 'triggers': triggers, 'escalated': False}
    if mode == 'fast' or not triggers and mode != 'deep': return first
    targeted = json.dumps({'case_facts': facts, 'material_questions': triggers, 'initial_considerations': first['considerations']}, ensure_ascii=False, default=str)
    stages = [first['usage']]
    opinions = [first['considerations']]
    if triggers:
        additional = await routed('escalation', targeted, settings.model_frontier, user=user, encounter_id=encounter_id)
        stages.append(additional['usage']); opinions.append(additional['considerations'])
    if mode == 'deep':
        # Independent assessment sees source evidence, not the first model's conclusions.
        challenged = await routed('challenge', content, settings.model_challenger, user=user, encounter_id=encounter_id)
        opinions.append(challenged['considerations']); stages.append(challenged['usage'])
    reconciled = await routed('reconcile', json.dumps({'case_facts': facts, 'material_questions': triggers, 'interpretations': opinions}, ensure_ascii=False, default=str),
        settings.model_review, user=user, encounter_id=encounter_id)
    reconciled['usage']['stages'] = stages
    reconciled['routing'] = {**reconciled.get('routing', {}), 'mode': mode, 'triggers': triggers, 'escalated': bool(triggers), 'independent_challenge': mode == 'deep'}
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
    from .documentation_layers import select as select_documentation,selection
    layer_token=select_documentation(user,options.get('template','nursing'))
    operations=[]
    token=document_operations.set(operations)
    try:return await _checked_quick_text(context,task,options,user=user,encounter_id=encounter_id)
    except HTTPException:
        if operations:
            with SessionLocal() as refund_db:billing.refund_failed_document(refund_db,user,operations)
        raise
    finally:document_operations.reset(token);selection.reset(layer_token)


@timed('report_pipeline')
async def _checked_quick_text(context, task, options, *, user, encounter_id):
    diagnostics.task(options.get('template','nursing'))
    from .documentation import quality_defects, clean_generated_layout
    content = {**context, **options, 'document_task': task}
    options = {**options, 'document_task': task}
    draft = await routed(task, json.dumps(content, ensure_ascii=False, default=str),
        user=user, encounter_id=encounter_id, max_tokens=8000 if len(json.dumps(context,ensure_ascii=False))>20000 else 2400)
    draft = clean_generated_layout(draft, options, context)
    qa = await routed('quick_qa', json.dumps({**content, 'proposed_draft': draft['text'], 'proposed_status': draft.get('status', 'ready'), 'proposed_ui_alerts': draft.get('ui_alerts', [])},
        ensure_ascii=False, default=str), user=user, encounter_id=encounter_id, max_tokens=1000)
    defects = quality_defects(draft['text'], context)
    if defects:
        qa = {**qa, 'passed': False, 'discrepancies': list(dict.fromkeys(qa.get('discrepancies', []) + defects))[:20]}
    diagnostics.faithfulness(qa.get('passed') is True and qa.get('discrepancies')==[],len(qa.get('discrepancies',[])),'initial')
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


_SENTENCE_SPLIT = re.compile(r'(?<=[.!?])\s+')


def _repeat_key(value):
    return re.sub(r'[^a-z0-9]+', ' ', str(value).casefold()).strip()


_CLINICAL_MEANING = re.compile(r"(?i)(\d|\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|hundred|thousand|half|quarter|once|twice)\b"
    r"|\b(?:no|not|non|none|nil|never|nothing|denies|denied|without|negative|absent|stop|stopped|hold|held|discontinue\w*|don'?t|doesn'?t|didn'?t|isn'?t|wasn'?t|can'?t|won'?t)\b"
    r"|\b(?:mg|mcg|g|ml|l|units?|iu|meq|mmol|cm|mm|kg|lbs?|percent|tabs?|tablets?|capsules?|dose|doses|puffs?|drops?|daily|bid|tid|qid|prn|hourly|weekly|left|right|bilateral)\b|%)")


def carries_clinical_meaning(sentence):
    """A sentence with a number, negation, dose/unit, frequency or side is never shortened, even if repeated."""
    return bool(_CLINICAL_MEANING.search(str(sentence))) or len(str(sentence).split()) > 8


def collapse_speech_loops(text, segments, minimum_run=3):
    """Speech models sometimes loop on silence or noise ("Thank you. Thank you. Thank you. ...").

    Only a run of at least three IDENTICAL consecutive short sentences (or segments) with no clinical
    meaning (no number, negation, dose, unit, frequency or side) is shortened to one copy. A repeated
    clinically meaningful sentence is kept in full and only flagged. Nothing is reworded.
    Returns (text, segments, changed, flagged); the caller keeps the original transcript.
    """
    flagged = False
    def collapse(items, key):
        nonlocal flagged
        kept, changed, index = [], False, 0
        while index < len(items):
            end = index + 1
            while end < len(items) and key(items[end]) and key(items[end]) == key(items[index]):
                end += 1
            run = items[index:end]
            if end - index >= minimum_run:
                sample = run[0].get('text', '') if isinstance(run[0], dict) else run[0]
                if carries_clinical_meaning(sample):
                    flagged = True
                    kept.extend(run)
                else:
                    changed = True
                    kept.append(run[0])
            else:
                kept.extend(run)
            index = end
        return kept, changed
    lines, changed_text = [], False
    for line in text.split('\n'):
        parts = _SENTENCE_SPLIT.split(line)
        kept, changed = collapse(parts, _repeat_key)
        changed_text = changed_text or changed
        lines.append(' '.join(kept) if changed else line)
    kept_segments, changed_segments = collapse(list(segments), lambda item: _repeat_key(item.get('text', '')) if isinstance(item, dict) else '')
    return ('\n'.join(lines) if changed_text else text), kept_segments, changed_text or changed_segments, flagged


@timed('transcription')
async def speech_attempt(provider, data, mime, filename, seconds, *, user, encounter_id, verification_model=None, purpose='transcription', diagnostic_original=None):
    diagnostics.task(purpose)
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
            async def send(url,**kwargs):
                return await diagnostics.post(client,url,provider=provider,model=model,stage=purpose,fallback=diagnostic_original is not None,original_provider=diagnostic_original[0] if diagnostic_original else provider,original_model=diagnostic_original[1] if diagnostic_original else model,**kwargs)
            if provider == 'cloudflare':
                response = await send(f'https://api.cloudflare.com/client/v4/accounts/{settings.cloudflare_account_id}/ai/run/{model}',
                    headers={'Authorization': 'Bearer ' + settings.cloudflare_api_token},
                    json={'audio': base64.b64encode(data).decode(), 'task': 'transcribe', 'initial_prompt': primer, 'vad_filter': True, 'condition_on_previous_text': False})
            elif provider == 'groq':
                response = await send('https://api.groq.com/openai/v1/audio/transcriptions', headers={'Authorization': 'Bearer ' + settings.groq_api_key},
                    data={'model': model, 'prompt': primer, 'response_format': 'verbose_json', 'temperature': '0'}, files={'file': (filename, data, mime)})
            else:
                response = await send('https://api.deepgram.com/v1/listen', params={'model': model, 'smart_format': 'true', 'utterances': 'true', 'mip_opt_out': 'true'},
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
        original_text = text
        text, clean, looped, repeated_clinical = collapse_speech_loops(text, clean)
        if looped: warnings.append('The speech service repeated the same short phrase several times in a row; the extra copies were left out of the working transcript. The original transcript is kept unchanged with this source. Check this part against the recording.')
        if repeated_clinical: warnings.append('A sentence with a number, dose, negation or side was repeated 3 or more times in a row. It was NOT shortened. Check this part against the recording.')
        if not text.strip(): warnings.append('No speech was detected. No clinical findings were inferred.')
        result = {'text': text, 'segments': clean, 'warnings': warnings}
        if looped: result['original_text'] = original_text  # the unshortened transcript is always kept
        return result
    return await metered_call(user=user, encounter_id=encounter_id, task=purpose, provider=provider, model=model,
        payload_hash=fingerprint([sha256(data).hexdigest(), mime, model, primer]), estimate=normalized,
        perform=perform, measure=lambda raw: (normalized, {'audio_seconds': seconds, 'audio_minutes': seconds / 60}, None), validate=validate)


async def verify_speech_clip(data, *, user, encounter_id, verifier='stronger'):
    """No source transcript or uncertainty hint is supplied to the second speech pass."""
    if verifier not in {'stronger', 'medical'}:
        raise HTTPException(422, 'Select stronger speech verification or explicit medical transcription')
    if not data or len(data) > 1_100_000:
        raise HTTPException(413, 'Verification accepts only a normalized short audio clip')
    seconds = await asyncio.to_thread(audio_duration, data)  # blocking ffprobe: run off the event loop
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
                user=user, encounter_id=encounter_id, verification_model=model, purpose='audio_verification',diagnostic_original=(options[0][0],options[0][1] or '@cf/openai/whisper-large-v3-turbo') if errors else None)
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
    seconds = await asyncio.to_thread(audio_duration, data)  # blocking ffprobe: run off the event loop
    available = []
    if settings.cloudflare_api_token and settings.cloudflare_account_id: available.append('cloudflare')
    if settings.groq_api_key: available.append('groq')
    if settings.deepgram_enabled and settings.deepgram_api_key: available.append('deepgram')
    if settings.speech_provider != 'auto': available = [p for p in available if p == settings.speech_provider]
    if not available: raise HTTPException(503, 'Speech providers are not configured. Typed transcripts and manual evidence remain available.')
    errors = []
    for provider in available[:3]:
        try:
            original=(available[0],{'cloudflare':'@cf/openai/whisper-large-v3-turbo','groq':'whisper-large-v3-turbo','deepgram':'nova-3-medical'}[available[0]]) if errors else None
            result = await speech_attempt(provider, data, mime, filename, seconds, user=user, encounter_id=encounter_id,diagnostic_original=original)
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


