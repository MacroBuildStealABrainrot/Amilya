"""Workspace billing and durable microUSD accounting.

Metering helpers own their supplied Session transaction. Provider callers must use
a separate Session with no staged clinical changes, reserve before external work,
and never repeat a provider call when reserve returns an existing operation.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import time
from typing import Literal
from urllib.parse import quote

import httpx
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from server.core import settings
from server.db import get_db, utcnow, uid
from server.identity import Membership, User, current_user, require_admin, require_csrf, check_rate
from server.audit import write_audit
from server.billing_models import BillingAccount, CheckoutAttempt, CreditLedger, PaymentGrant, StripeEvent, UsageOperation

router = APIRouter(tags=["billing"])
MICROS_PER_CREDIT = 1000
PRICE_REGISTRY_VERSION = "normalized-2026-09-05"
STRIPE_API_VERSION = "2026-08-26.dahlia"
PLANS = {
    "essential": {"name": "Essential", "price_cents": 2499, "credits": 2500, "encounter_estimate": 70},
    "plus": {"name": "Plus", "price_cents": 3499, "credits": 5000, "encounter_estimate": 140},
    "pro": {"name": "Pro", "price_cents": 4999, "credits": 12000, "encounter_estimate": 340},
    "power": {"name": "Power", "price_cents": 7500, "credits": 18000, "encounter_estimate": 510},
}
TOPUP = {"name": "1,000 Credit Top-Up", "price_cents": 899, "credits": 1000}


def _hash(value) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), default=str).encode()).hexdigest()


def _int(value, name="amount", maximum=10**15) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= maximum:
        raise HTTPException(422, f"Invalid integer {name}")
    return value


def _id(value) -> str | None:
    return value.get("id") if isinstance(value, dict) else value if isinstance(value, str) else None


def _active(account: BillingAccount) -> bool:
    return account.status == "active" and account.period_start <= int(time.time()) < account.period_end and not account.refund_debt_micros


def has_unlimited_credits(user: User) -> bool:
    """Operator-configured account entitlement; never grants platform privileges.

    Bind grants to immutable user IDs, not signup email or editable profile data.
    The legacy usage-operation admin_exempt column snapshots any credit exemption.
    """
    configured = getattr(settings, "unlimited_credit_user_ids", "")
    allowed = {value.strip() for value in configured.split(",") if value.strip()}
    return bool(user.is_platform_admin or user.id in allowed)


def _lock_account(db: Session, workspace_id: str) -> BillingAccount:
    dialect = db.get_bind().dialect.name
    if dialect == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    elif dialect == "sqlite":
        from sqlalchemy.dialects.sqlite import insert
    else:
        raise RuntimeError("Billing requires PostgreSQL or SQLite")
    db.execute(insert(BillingAccount).values(workspace_id=workspace_id).on_conflict_do_nothing(index_elements=["workspace_id"]))
    # An UPDATE acquires the row/write lock on PostgreSQL and SQLite respectively.
    db.execute(update(BillingAccount).where(BillingAccount.workspace_id == workspace_id).values(version=BillingAccount.version + 1))
    return db.scalar(select(BillingAccount).where(BillingAccount.workspace_id == workspace_id).execution_options(populate_existing=True))


def _ledger(db, account, key, kind, delta=0, reserved_delta=0, user_id=None, detail=None):
    db.add(CreditLedger(workspace_id=account.workspace_id, user_id=user_id, event_key=key, kind=kind,
                       delta_micros=delta, reserved_delta_micros=reserved_delta,
                       balance_after_micros=account.plan_micros + account.topup_micros, detail=detail or {}))


def refund_failed_document(db:Session,user:User,operation_ids:list[str]):
    """Append an idempotent credit reversal when an entire document fails QA.
    Preserve measured vendor costs and the original ledger entries for accounting.
    """
    account=_lock_account(db,user.workspace_id)
    returned=0
    for identifier in set(operation_ids):
        operation=db.scalar(select(UsageOperation).where(UsageOperation.workspace_id==user.workspace_id,UsageOperation.user_id==user.id,UsageOperation.operation_id==identifier))
        if not operation or operation.status!='settled' or operation.charged_micros<=0:continue
        key='failed-document-refund:'+identifier
        if db.scalar(select(CreditLedger.id).where(CreditLedger.workspace_id==user.workspace_id,CreditLedger.event_key==key)):continue
        amount=operation.charged_micros
        # Credit the non-expiring bucket: do not restore a plan period that has
        # already expired while the failed workflow was running.
        account.topup_micros+=amount
        operation.charged_micros=0
        operation.unfunded_micros=0
        operation.usage={**operation.usage,'charge_waived':True,'waive_reason':'document_failed','refunded_micros':amount}
        _ledger(db,account,key,'workflow_refund',amount,user_id=user.id)
        returned+=amount
    db.commit()
    return returned


def _expire_plan(db, account):
    if account.period_end and account.period_end <= int(time.time()) and account.plan_micros:
        expired = account.plan_micros
        account.plan_micros = 0
        _ledger(db, account, f"expire:{account.period_start}:{account.version}", "plan_expired", -expired)


def _operation_out(operation, *, created=False):
    return {"operation_id": operation.operation_id, "status": operation.status, "created": created,
            "estimated_micros": operation.estimated_micros, "charged_micros": operation.charged_micros,
            "normalized_micros": operation.normalized_micros, "actual_cost_micros": operation.actual_cost_micros,
            "unfunded_micros": operation.unfunded_micros, "admin_exempt": operation.admin_exempt}


def reserve(db: Session, user: User, operation_id: str, estimated_micros: int,
            encounter_id: str | None = None, task: str = "ai") -> dict:
    estimated_micros = _int(estimated_micros, "reservation")
    if not 1 <= len(operation_id) <= 160 or not 1 <= len(task) <= 64:
        raise HTTPException(422, "Invalid operation identifier or task")
    fingerprint = _hash({"user": user.id, "estimate": estimated_micros, "encounter": encounter_id, "task": task})
    try:
        account = _lock_account(db, user.workspace_id)
        existing = db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == user.workspace_id, UsageOperation.operation_id == operation_id))
        if existing:
            if existing.request_hash != fingerprint:
                raise HTTPException(409, "Operation id was already used for a different request")
            db.commit()
            return _operation_out(existing)
        _expire_plan(db, account)
        exempt = has_unlimited_credits(user)
        if not exempt and not _active(account):
            raise HTTPException(402, "An active paid plan is required for AI processing. Manual charting and signing remain available.")
        if not exempt and account.plan_micros + account.topup_micros < estimated_micros:
            raise HTTPException(402, "More credits are needed for this AI action. Your existing work is saved.")
        plan_hold = 0 if exempt else min(account.plan_micros, estimated_micros)
        topup_hold = 0 if exempt else estimated_micros - plan_hold
        account.plan_micros -= plan_hold
        account.topup_micros -= topup_hold
        account.reserved_micros += plan_hold + topup_hold
        operation = UsageOperation(workspace_id=user.workspace_id, user_id=user.id, operation_id=operation_id,
                                   encounter_id=encounter_id, task=task, request_hash=fingerprint, admin_exempt=exempt,
                                   estimated_micros=estimated_micros, reserved_plan_micros=plan_hold,
                                   reserved_topup_micros=topup_hold, cycle_start=account.period_start,
                                   price_registry_version=PRICE_REGISTRY_VERSION)
        db.add(operation)
        _ledger(db, account, f"reserve:{operation_id}", "reserved", -(plan_hold + topup_hold), plan_hold + topup_hold, user.id)
        db.commit()
        return _operation_out(operation, created=True)
    except Exception:
        db.rollback()
        raise


_USAGE_NUMBERS = {"input_tokens", "output_tokens", "cached_tokens", "total_tokens", "audio_seconds", "audio_minutes", "media_units", "image_units", "gateway_overhead_micros", "provider_list_cost_micros", "latency_ms", "attempt_count"}


def _safe_usage(usage: dict) -> dict:
    """Never persist raw prompts, transcripts or arbitrary provider payloads in cost logs."""
    safe = {}
    routing = usage.get('routing')
    if isinstance(routing, dict):
        from .kosmo import MODELS, VERSION
        from .providers import MODEL_PRICES
        if isinstance(routing.get('effort_level'), str) and routing['effort_level'] in MODELS and isinstance(routing.get('model'), str) and routing['model'] in MODEL_PRICES:
            safe['routing'] = {key: routing[key] for key in ('model', 'effort_level')}
            safe['routing']['policy_version'] = VERSION
    from .providers import MODEL_PRICES
    reported = usage.get('reported_model')
    if isinstance(reported, str) and reported in MODEL_PRICES:
        safe['reported_model'] = reported
    if usage.get('model_identity') in ('confirmed', 'unreported', 'mismatch'):
        safe['model_identity'] = usage['model_identity']
    if type(usage.get('upstream_status')) is int and 100 <= usage['upstream_status'] <= 599:
        safe['upstream_status'] = usage['upstream_status']
    if usage.get('failure_reason') in ('upstream_error', 'route_unavailable', 'provider_credits', 'provider_access', 'provider_rate_limit', 'request_incompatible', 'invalid_response', 'provider_timeout', 'provider_connection', 'response_processing'):
        safe['failure_reason'] = usage['failure_reason']
    retry_deadline = usage.get('retry_not_before')
    if type(retry_deadline) in (int, float) and 0 < retry_deadline < 10**12:
        # Application-generated deadline, never the raw upstream header/body.
        safe['retry_not_before'] = retry_deadline
    for key in _USAGE_NUMBERS:
        value = usage.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool) and 0 <= value < 10**15:
            safe[key] = value
    if isinstance(usage.get("price_registry_version"), str):
        safe["price_registry_version"] = usage["price_registry_version"][:64]
    attempts = usage.get("attempts")
    if isinstance(attempts, list):
        safe["attempts"] = []
        for attempt in attempts[:20]:
            if not isinstance(attempt, dict):
                continue
            item = {key: str(attempt[key])[:160] for key in ("provider", "model", "status", "error_code") if isinstance(attempt.get(key), str)}
            item.update({key: value for key, value in attempt.items() if key in _USAGE_NUMBERS | {"normalized_micros", "actual_cost_micros"} and isinstance(value, (int, float)) and not isinstance(value, bool) and 0 <= value < 10**15})
            safe["attempts"].append(item)
    return safe


def settle(db: Session, user: User, operation_id: str, actual_micros: int, provider: str,
           model: str, usage: dict, actual_cost_micros: int | None = None, *, commit: bool = True,
           waive_charge: bool = False, waive_reason: str = "unusable_provider_output") -> dict:
    actual_micros = _int(actual_micros, "normalized cost")
    if actual_cost_micros is not None:
        _int(actual_cost_micros, "actual provider cost")
    usage = _safe_usage(usage or {})
    if waive_charge:
        usage["charge_waived"] = True
        usage["waive_reason"] = re.sub(r"[^a-zA-Z0-9_-]", "_", waive_reason)[:80]
    fingerprint = _hash([actual_micros, provider, model, usage, actual_cost_micros])
    try:
        account = _lock_account(db, user.workspace_id)
        operation = db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == user.workspace_id, UsageOperation.operation_id == operation_id))
        if not operation or operation.user_id != user.id:
            raise HTTPException(404, "Usage reservation not found")
        if operation.status == "settled":
            if operation.settlement_hash != fingerprint:
                raise HTTPException(409, "Usage operation was already settled with different costs")
            if commit:
                db.commit()
            return _operation_out(operation)
        if operation.status != "reserved":
            raise HTTPException(409, "Released operations cannot be charged; use a new operation for a retry")
        held = operation.reserved_plan_micros + operation.reserved_topup_micros
        balance_before = account.plan_micros + account.topup_micros
        charged = 0
        if not operation.admin_exempt and not waive_charge:
            plan_used = min(operation.reserved_plan_micros, actual_micros)
            topup_used = min(operation.reserved_topup_micros, max(0, actual_micros - plan_used))
            same_cycle = operation.cycle_start == account.period_start and account.period_end > int(time.time()) and account.status not in {"canceled", "incomplete_expired"}
            if same_cycle:
                account.plan_micros += operation.reserved_plan_micros - plan_used
            account.topup_micros += operation.reserved_topup_micros - topup_used
            charged = plan_used + topup_used
            remaining = actual_micros - charged
            if remaining:
                extra_plan = min(account.plan_micros, remaining)
                account.plan_micros -= extra_plan
                extra_topup = min(account.topup_micros, remaining - extra_plan)
                account.topup_micros -= extra_topup
                charged += extra_plan + extra_topup
        elif waive_charge and not operation.admin_exempt:
            if operation.cycle_start == account.period_start and account.period_end > int(time.time()) and account.status not in {"canceled", "incomplete_expired"}:
                account.plan_micros += operation.reserved_plan_micros
            account.topup_micros += operation.reserved_topup_micros
        account.reserved_micros -= held
        operation.status = "settled"
        operation.normalized_micros = actual_micros
        operation.actual_cost_micros = actual_cost_micros
        operation.charged_micros = charged
        operation.unfunded_micros = 0 if operation.admin_exempt or waive_charge else actual_micros - charged
        operation.provider = provider[:80]
        operation.model = model[:160]
        operation.usage = usage
        operation.price_registry_version = usage.get("price_registry_version", PRICE_REGISTRY_VERSION)
        operation.settlement_hash = fingerprint
        operation.completed_at = utcnow()
        _ledger(db, account, f"settle:{operation_id}", "usage", account.plan_micros + account.topup_micros - balance_before,
                -held, user.id, {"charged_micros": charged, "normalized_micros": actual_micros, "unfunded_micros": operation.unfunded_micros, "admin_exempt": operation.admin_exempt,
                                "charge_waived": waive_charge, "waive_reason": usage.get("waive_reason")})
        if commit:
            db.commit()
        else:
            db.flush()
        return _operation_out(operation)
    except Exception:
        db.rollback()
        raise


def release(db: Session, user: User, operation_id: str, reason: str = "provider_failed") -> dict:
    try:
        account = _lock_account(db, user.workspace_id)
        operation = db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == user.workspace_id, UsageOperation.operation_id == operation_id))
        if not operation or operation.user_id != user.id:
            raise HTTPException(404, "Usage reservation not found")
        if operation.status != "reserved":
            db.commit()
            return _operation_out(operation)
        held = operation.reserved_plan_micros + operation.reserved_topup_micros
        plan_return = operation.reserved_plan_micros if operation.cycle_start == account.period_start and account.period_end > int(time.time()) and account.status not in {"canceled", "incomplete_expired"} else 0
        account.plan_micros += plan_return
        account.topup_micros += operation.reserved_topup_micros
        account.reserved_micros -= held
        operation.status = "released"
        operation.release_reason = re.sub(r"[^a-zA-Z0-9_-]", "_", reason)[:80]
        operation.completed_at = utcnow()
        _ledger(db, account, f"release:{operation_id}", "released", plan_return + operation.reserved_topup_micros, -held, user.id)
        db.commit()
        return _operation_out(operation)
    except Exception:
        db.rollback()
        raise


@router.get("/plans")
def plans():
    return {"plans": [{"id": key, "name": value["name"], "price_cad": value["price_cents"] / 100,
                       "credits": value["credits"], "encounter_estimate": value["encounter_estimate"]} for key, value in PLANS.items()],
            "topup": {"price_cad": TOPUP["price_cents"] / 100, "credits": TOPUP["credits"]}, "currency": "CAD",
            "estimate_disclaimer": "Approximate standard encounter capacity; actual usage varies with evidence, review depth and visit length.",
            "credit_policy": "Monthly plan credits reset at the paid billing-cycle boundary. Top-ups are preserved, but usable only with an active paid subscription. Paid upgrades add prorated incremental capacity; downgrades do not remove current-cycle credits.",
            "billing_available": bool(getattr(settings, "billing_enabled", False))}


@router.get("/billing")
def billing_status(user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = db.get(BillingAccount, user.workspace_id)
    history = list(db.scalars(select(CreditLedger).where(CreditLedger.workspace_id == user.workspace_id).order_by(CreditLedger.created_at.desc()).limit(60)))
    period_valid = account and account.period_end > int(time.time())
    balance = ((account.plan_micros if period_valid else 0) + account.topup_micros) if account else 0
    return {"plan": account.plan if account else None, "status": account.status if account else "inactive",
            "active": bool(account and _active(account)), "balance_credits": balance / MICROS_PER_CREDIT,
            "reserved_credits": (account.reserved_micros if account else 0) / MICROS_PER_CREDIT,
            "topup_credits": (account.topup_micros if account else 0) / MICROS_PER_CREDIT,
            "is_unlimited": has_unlimited_credits(user), "period_end": account.period_end if account else None,
            "cancel_at_period_end": account.cancel_at_period_end if account else False,
            "history": [{"id": row.id, "kind": row.kind, "credits": row.delta_micros / MICROS_PER_CREDIT,
                         "created_at": row.created_at, "balance_credits": row.balance_after_micros / MICROS_PER_CREDIT} for row in history]}


def _prices():
    result = {}
    for key in [*PLANS, "topup"]:
        value = getattr(settings, f"stripe_price_{key}", "")
        if value:
            if value in result:
                raise HTTPException(503, "Billing price configuration is invalid")
            result[value] = key
    return result


async def stripe_request(method: str, path: str, data: dict | None = None, idempotency_key: str | None = None) -> dict:
    key = getattr(settings, "stripe_secret_key", None)
    if not getattr(settings, "billing_enabled", False) or not key:
        raise HTTPException(503, "Billing is not configured yet. Contact support for availability.")
    expected_prefix = "sk_live_" if getattr(settings, "stripe_live_mode", False) else "sk_test_"
    if not key.startswith(expected_prefix):
        raise HTTPException(503, "Billing mode configuration requires review")
    headers = {"Authorization": f"Bearer {key}", "Stripe-Version": getattr(settings, "stripe_api_version", STRIPE_API_VERSION)}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    try:
        async with httpx.AsyncClient(timeout=25, follow_redirects=False) as client:
            response = await client.request(method, "https://api.stripe.com/v1/" + path, headers=headers,
                                            data=data if method != "GET" else None, params=data if method == "GET" else None)
    except httpx.HTTPError as exc:
        raise HTTPException(503, "Billing service is temporarily unavailable; retry with the same request.") from exc
    if response.status_code >= 400:
        raise HTTPException(502, "Billing service could not complete the request. Contact support if this persists.")
    return response.json()


async def stripe_configuration_report() -> dict:
    """Read-only owner diagnostic. The caller authorizes any real Stripe calls.

    Returns allowlisted validation results only; never a raw account/API response.
    """
    result = {"api_version": getattr(settings, "stripe_api_version", STRIPE_API_VERSION),
              "mode": "live" if getattr(settings, "stripe_live_mode", False) else "test", "prices": {}}
    for plan, definition in {**PLANS, "topup": TOPUP}.items():
        price_id = getattr(settings, "stripe_price_" + plan, "")
        if not price_id:
            result["prices"][plan] = {"configured": False, "valid": False}
            continue
        price = await stripe_request("GET", "prices/" + quote(price_id, safe=""))
        recurring = price.get("recurring") or {}
        valid = (price.get("active") is True and price.get("currency") == "cad" and price.get("unit_amount") == definition["price_cents"]
                 and (not recurring if plan == "topup" else recurring.get("interval") == "month" and recurring.get("interval_count") == 1))
        result["prices"][plan] = {"configured": True, "valid": bool(valid), "active": bool(price.get("active")),
                                  "currency": price.get("currency"), "amount_cents": price.get("unit_amount"),
                                  "interval": recurring.get("interval"), "credits": definition["credits"]}
    result["all_prices_valid"] = all(item["valid"] for item in result["prices"].values())
    return result


def _return_url() -> str:
    base = str(getattr(settings, "public_base_url", "http://localhost:8000")).rstrip("/")
    return base + "/?screen=settings&section=billing"


class CheckoutInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    plan: Literal["essential", "plus", "pro", "power", "topup"]


def _billing_owner(db, user):
    membership = db.scalar(select(Membership).where(Membership.workspace_id == user.workspace_id, Membership.user_id == user.id))
    if not user.is_platform_admin and (not membership or membership.role != "owner"):
        raise HTTPException(403, "Only the workspace owner can manage its billing")


async def _ensure_customer(db, user, account):
    if account.customer_id:
        customer = account.customer_id
        db.commit()
        return customer
    db.commit()
    response = await stripe_request("POST", "customers", {"email": user.email, "metadata[workspace_id]": user.workspace_id},
                                    "customer:" + user.workspace_id)
    account = _lock_account(db, user.workspace_id)
    account.customer_id = response["id"]
    db.commit()
    return response["id"]


@router.post("/billing/checkout", dependencies=[Depends(require_csrf)])
async def checkout(payload: CheckoutInput, user: User = Depends(current_user), db: Session = Depends(get_db),
                   idempotency_key: str | None = Header(None, alias="Idempotency-Key")):
    _billing_owner(db, user)
    db.rollback()
    check_rate("checkout:" + user.id, limit=8)
    price_id = getattr(settings, f"stripe_price_{payload.plan}", "")
    if not price_id or not getattr(settings, "billing_enabled", False):
        raise HTTPException(503, "This billing option is not configured yet")
    _prices()
    price = await stripe_request("GET", "prices/" + quote(price_id, safe=""))
    expected = TOPUP if payload.plan == "topup" else PLANS[payload.plan]
    recurring = price.get("recurring") or {}
    if price.get("active") is not True or price.get("currency") != "cad" or price.get("unit_amount") != expected["price_cents"] or (payload.plan != "topup" and (recurring.get("interval") != "month" or recurring.get("interval_count") != 1)) or (payload.plan == "topup" and recurring):
        raise HTTPException(503, "Configured billing price does not match the published plan")
    account = _lock_account(db, user.workspace_id)
    if payload.plan == "topup" and not _active(account):
        db.rollback()
        raise HTTPException(403, "Top-ups require an active paid subscription")
    if payload.plan != "topup" and account.subscription_id and account.status not in {"canceled", "inactive", "incomplete_expired"}:
        customer = account.customer_id
        subscription = account.subscription_id
        item = account.subscription_item_id
        db.commit()
        if not customer or not item:
            raise HTTPException(409, "Subscription update is still synchronizing; try Manage billing shortly")
        response = await stripe_request("POST", "billing_portal/sessions", {
            "customer": customer, "return_url": _return_url(), "flow_data[type]": "subscription_update_confirm",
            "flow_data[subscription_update_confirm][subscription]": subscription,
            "flow_data[subscription_update_confirm][items][0][id]": item,
            "flow_data[subscription_update_confirm][items][0][price]": price_id,
            "flow_data[subscription_update_confirm][items][0][quantity]": "1",
        })
        return {"url": response["url"]}
    key = idempotency_key or uid()
    if not 1 <= len(key) <= 128:
        raise HTTPException(422, "Invalid billing request id")
    existing = db.scalar(select(CheckoutAttempt).where(CheckoutAttempt.workspace_id == user.workspace_id, CheckoutAttempt.request_key == key))
    if existing:
        if existing.plan != payload.plan or existing.stripe_price_id != price_id:
            raise HTTPException(409, "Billing request id already used for a different plan")
        if existing.url and existing.expires_at > int(time.time()):
            db.commit()
            return {"url": existing.url}
        if existing.expires_at <= int(time.time()):
            raise HTTPException(409, "This checkout has completed or expired. Start a new billing request.")
        attempt = existing
    else:
        pending = db.scalar(select(CheckoutAttempt).where(CheckoutAttempt.workspace_id == user.workspace_id,
                                                        CheckoutAttempt.expires_at > int(time.time()), CheckoutAttempt.plan != "topup"))
        if pending and payload.plan != "topup":
            if pending.plan == payload.plan and pending.url:
                db.commit()
                return {"url": pending.url}
            raise HTTPException(409, "A subscription checkout is already open; complete or allow it to expire before starting another")
        attempt = CheckoutAttempt(workspace_id=user.workspace_id, request_key=key, plan=payload.plan,
                                  stripe_price_id=price_id, expires_at=int(time.time()) + 1800)
        db.add(attempt)
        db.flush()
    attempt_id = attempt.id
    customer = await _ensure_customer(db, user, account)
    data = {"mode": "payment" if payload.plan == "topup" else "subscription", "customer": customer,
            "line_items[0][price]": price_id, "line_items[0][quantity]": "1",
            "success_url": _return_url() + "&checkout=complete", "cancel_url": _return_url() + "&checkout=canceled",
            "client_reference_id": user.workspace_id, "metadata[workspace_id]": user.workspace_id,
            "metadata[checkout_attempt_id]": attempt_id, "expires_at": str(attempt.expires_at)}
    if payload.plan != "topup":
        data["subscription_data[metadata][workspace_id]"] = user.workspace_id
    response = await stripe_request("POST", "checkout/sessions", data, "checkout:" + attempt_id)
    account = _lock_account(db, user.workspace_id)
    attempt = db.get(CheckoutAttempt, attempt_id)
    attempt.stripe_session_id = response["id"]
    attempt.url = response["url"]
    attempt.expires_at = response.get("expires_at", attempt.expires_at)
    write_audit(db, user, "billing.checkout", attempt.id, {"plan": payload.plan})
    db.commit()
    return {"url": response["url"]}


@router.post("/billing/portal", dependencies=[Depends(require_csrf)])
async def portal(user: User = Depends(current_user), db: Session = Depends(get_db)):
    _billing_owner(db, user)
    check_rate("portal:" + user.id, limit=10)
    account = db.get(BillingAccount, user.workspace_id)
    if not account or not account.customer_id:
        raise HTTPException(409, "No billing account is available yet")
    result = await stripe_request("POST", "billing_portal/sessions", {"customer": account.customer_id, "return_url": _return_url()})
    return {"url": result["url"]}


def verify_stripe_event(raw: bytes, signature: str, secret: str, now: int | None = None) -> dict:
    if not secret:
        raise HTTPException(503, "Webhook signing is not configured")
    if len(raw) > 1024 * 1024:
        raise HTTPException(413, "Webhook exceeds size limit")
    # Reject malformed non-ASCII headers before signature verification.
    if not signature.isascii():
        raise HTTPException(400, "Invalid webhook signature")
    fields = {}
    for part in signature.split(","):
        key, sep, value = part.partition("=")
        if sep:
            fields.setdefault(key, []).append(value)
    try:
        timestamp = int(fields["t"][0])
    except (KeyError, ValueError, IndexError):
        raise HTTPException(400, "Invalid webhook signature")
    if abs((now if now is not None else int(time.time())) - timestamp) > 300:
        raise HTTPException(400, "Webhook signature timestamp is outside tolerance")
    expected = hmac.new(secret.encode(), str(timestamp).encode() + b"." + raw, hashlib.sha256).hexdigest()
    if not any(hmac.compare_digest(expected, candidate) for candidate in fields.get("v1", [])):
        raise HTTPException(400, "Invalid webhook signature")
    try:
        event = json.loads(raw)
        if not isinstance(event, dict) or not isinstance(event.get("id"), str) or not isinstance(event.get("data", {}).get("object"), dict):
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise HTTPException(400, "Invalid webhook payload")
    return event


def _price_key(line):
    price = _id(line.get("price")) or _id((line.get("pricing") or {}).get("price_details", {}).get("price"))
    return _prices().get(price)


def _subscription_id(obj):
    return _id(obj.get("subscription")) or _id((obj.get("parent") or {}).get("subscription_details", {}).get("subscription"))


def _payment_intent(obj):
    direct = _id(obj.get("payment_intent"))
    if direct:
        return direct
    for payment in (obj.get("payments") or {}).get("data", []):
        value = _id((payment.get("payment") or {}).get("payment_intent"))
        if value:
            return value
    return None


def _grant(db, account, source, bucket, amount, payment_intent=None, paid_cents=0):
    existing = db.scalar(select(PaymentGrant).where(PaymentGrant.source_id == source))
    if existing:
        if existing.workspace_id != account.workspace_id:
            raise HTTPException(409, "Payment belongs to a different workspace")
        return False
    net = max(0, amount - account.refund_debt_micros)
    account.refund_debt_micros = max(0, account.refund_debt_micros - amount)
    if bucket == "plan":
        account.plan_micros += net
    else:
        account.topup_micros += net
    db.add(PaymentGrant(workspace_id=account.workspace_id, source_id=source, payment_intent=payment_intent,
                        bucket=bucket, cycle_start=account.period_start, granted_micros=amount, amount_cents=paid_cents))
    _ledger(db, account, "grant:" + source, "monthly_grant" if bucket == "plan" else "topup_grant", net,
            detail={"grant_micros": amount, "debt_repaid_micros": amount - net})
    return True


def process_stripe_event(db: Session, event: dict) -> dict:
    """Process a verified snapshot atomically. Unknown dependencies return 503 for retry."""
    event_id = event["id"]
    kind = event.get("type", "")
    fingerprint = _hash(event)
    try:
        existing_event = db.get(StripeEvent, event_id)
        if existing_event:
            if existing_event.payload_hash != fingerprint:
                raise HTTPException(409, "Webhook event id reused with different content")
            return {"received": True, "duplicate": True}
        obj = event["data"]["object"]
        customer = _id(obj.get("customer"))
        account = db.scalar(select(BillingAccount).where(BillingAccount.customer_id == customer)) if customer else None
        supported = {"checkout.session.completed", "checkout.session.async_payment_succeeded", "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted", "invoice.paid", "invoice.payment_failed", "charge.refunded"}
        if kind not in supported:
            db.add(StripeEvent(id=event_id, event_type=kind[:80], payload_hash=fingerprint, outcome="ignored"))
            db.commit()
            return {"received": True, "ignored": True}
        if not account:
            raise HTTPException(503, "Billing account mapping is not available yet; retry this event")
        workspace_id = account.workspace_id
        # Release the read snapshot before taking the write lock (also safe in SQLite WAL).
        db.rollback()
        account = _lock_account(db, workspace_id)
        existing_event = db.get(StripeEvent, event_id, populate_existing=True)
        if existing_event:
            if existing_event.payload_hash != fingerprint:
                raise HTTPException(409, "Webhook event id reused with different content")
            db.commit()
            return {"received": True, "duplicate": True}
        created = _int(event.get("created", 0), "event timestamp")
        outcome = "processed"
        if kind.startswith("checkout.session."):
            attempt_id = (obj.get("metadata") or {}).get("checkout_attempt_id")
            attempt = db.get(CheckoutAttempt, attempt_id) if attempt_id else None
            if not attempt or attempt.workspace_id != account.workspace_id or attempt.stripe_session_id != obj.get("id"):
                raise HTTPException(503, "Checkout mapping is not ready; retry this event")
            if obj.get("payment_status") == "paid" or (attempt.plan != "topup" and obj.get("status") == "complete"):
                attempt.expires_at = 0
            if attempt.plan == "topup":
                if obj.get("payment_status") == "paid":
                    if obj.get("currency") != "cad" or obj.get("amount_total", 0) < TOPUP["price_cents"]:
                        raise HTTPException(409, "Top-up payment does not match the configured product")
                    # A delayed settlement after cancellation remains purchased but frozen.
                    _grant(db, account, obj["id"], "topup", TOPUP["credits"] * MICROS_PER_CREDIT,
                           _payment_intent(obj), obj["amount_total"])
                else:
                    outcome = "payment_pending"
            else:
                sub_id = _subscription_id(obj)
                if account.subscription_id and account.subscription_id != sub_id and account.status not in {"canceled", "inactive", "incomplete_expired"}:
                    raise HTTPException(409, "Workspace already has another subscription")
                if sub_id:
                    if account.subscription_id != sub_id and account.status in {"canceled", "inactive", "incomplete_expired"}:
                        account.status = "pending_payment"
                    account.subscription_id = sub_id
                # An invoice.paid snapshot is the grant authority, never checkout metadata.
        elif kind.startswith("customer.subscription."):
            sub_id = obj.get("id")
            if account.subscription_id and account.subscription_id != sub_id and account.status not in {"canceled", "inactive", "incomplete_expired"}:
                outcome = "unrelated_subscription_ignored"
            elif account.subscription_id == sub_id and account.status == "canceled" and not kind.endswith("deleted"):
                # Stripe cancellation is terminal for one subscription ID. Snapshot
                # delivery order and second-resolution timestamps cannot revive it.
                outcome = "terminal_subscription_event_ignored"
            elif created >= account.state_event_created:
                account.subscription_id = sub_id
                account.state_event_created = created
                account.cancel_at_period_end = bool(obj.get("cancel_at_period_end"))
                items = (obj.get("items") or {}).get("data", [])
                if len(items) == 1 and _price_key(items[0]) in PLANS and items[0].get("quantity", 1) == 1:
                    account.subscription_item_id = items[0].get("id")
                incoming_status = "canceled" if kind.endswith("deleted") else obj.get("status", "inactive")
                if incoming_status == "active":
                    # Subscription active alone must not unlock unpaid invoices.
                    if account.period_end > int(time.time()) and account.paid_event_created >= created:
                        account.status = "active"
                    elif account.status not in {"active", "past_due"}:
                        account.status = "pending_payment"
                else:
                    account.status = incoming_status[:32]
                if incoming_status == "canceled":
                    removed = account.plan_micros
                    account.plan_micros = 0
                    _ledger(db, account, "cancel:" + event_id, "subscription_canceled", -removed,
                            detail={"topup_preserved_micros": account.topup_micros})
            else:
                outcome = "stale_subscription_event_ignored"
        elif kind == "invoice.payment_failed":
            sub_id = _subscription_id(obj)
            paid_invoice = db.scalar(select(PaymentGrant.id).where(PaymentGrant.source_id == obj.get("id")))
            if paid_invoice or (account.subscription_id == sub_id and account.status == "canceled"):
                outcome = "terminal_payment_failure_ignored"
            elif (not account.subscription_id or sub_id == account.subscription_id) and created >= max(account.state_event_created, account.paid_event_created):
                account.status = "past_due"
                account.state_event_created = created
            else:
                outcome = "stale_payment_failure_ignored"
        elif kind == "invoice.paid":
            if obj.get("paid") is not True and obj.get("status") != "paid":
                raise HTTPException(409, "Invoice is not paid")
            sub_id = _subscription_id(obj)
            if not sub_id:
                outcome = "non_subscription_invoice_ignored"
            elif account.subscription_id == sub_id and account.status == "canceled":
                outcome = "canceled_subscription_invoice_ignored"
            elif account.subscription_id and account.subscription_id != sub_id and account.status not in {"canceled", "inactive", "incomplete_expired"}:
                raise HTTPException(409, "Invoice belongs to another subscription")
            else:
                lines = (obj.get("lines") or {}).get("data", [])
                candidates = [line for line in lines if _price_key(line) in PLANS and line.get("quantity", 1) == 1 and line.get("amount", 0) >= 0]
                if len(candidates) != 1 or (obj.get("lines") or {}).get("has_more"):
                    raise HTTPException(409, "Invoice must contain exactly one configured monthly plan")
                line = candidates[0]
                plan = _price_key(line)
                period = line.get("period") or {}
                start, end = _int(period.get("start", 0), "period start"), _int(period.get("end", 0), "period end")
                if not start or end <= start or obj.get("currency") != "cad":
                    raise HTTPException(409, "Invalid paid billing cycle")
                if start > int(time.time()):
                    # Preserve the current paid cycle. A verified future snapshot
                    # is retryable and remains unacknowledged until its boundary.
                    # Long-future invoices need operator replay if Stripe's retry
                    # window elapses; no scheduled entitlement is implied here.
                    raise HTTPException(503, "This paid billing cycle has not started; retry the invoice at its period boundary")
                previous_grant = db.scalar(select(PaymentGrant).where(PaymentGrant.source_id == obj["id"]))
                if previous_grant:
                    outcome = "invoice_already_granted"
                elif end < account.period_end or (account.status == "canceled" and created < account.state_event_created):
                    outcome = "old_invoice_ignored"
                else:
                    full_allowance = PLANS[plan]["credits"] * MICROS_PER_CREDIT
                    same_cycle = end == account.period_end and bool(account.period_start)
                    if not same_cycle:
                        expired = account.plan_micros
                        account.plan_micros = 0
                        _ledger(db, account, "cycle_reset:" + obj["id"], "plan_expired", -expired)
                        account.period_start, account.period_end = start, end
                        account.cycle_granted_micros = 0
                        account.cycle_plan_ceiling_micros = 0
                        grant = full_allowance
                    else:
                        increase = max(0, full_allowance - account.cycle_plan_ceiling_micros)
                        remaining = min(end - account.period_start, max(0, end - start))
                        grant = increase * remaining // max(1, end - account.period_start)
                    account.cycle_plan_ceiling_micros = max(account.cycle_plan_ceiling_micros, full_allowance)
                    account.cycle_granted_micros += grant
                    account.subscription_id = sub_id
                    account.plan = plan
                    if created >= account.state_event_created:
                        account.status = "active"
                    account.paid_event_created = max(account.paid_event_created, created)
                    _grant(db, account, obj["id"], "plan", grant, _payment_intent(obj), _int(obj.get("amount_paid", 0), "invoice amount"))
        elif kind == "charge.refunded":
            payment = _payment_intent(obj)
            grant = db.scalar(select(PaymentGrant).where(PaymentGrant.payment_intent == payment, PaymentGrant.workspace_id == account.workspace_id)) if payment else None
            if not grant:
                raise HTTPException(503, "Refund payment mapping is not ready; reconcile invoice payment and retry")
            refunded = _int(obj.get("amount_refunded", 0), "refund amount")
            if refunded > grant.refunded_cents:
                target = min(grant.granted_micros, grant.granted_micros * refunded // max(1, grant.amount_cents))
                reverse = max(0, target - grant.reversed_micros)
                available = account.topup_micros if grant.bucket == "topup" else account.plan_micros if grant.cycle_start == account.period_start else 0
                take = min(available, reverse)
                if grant.bucket == "topup":
                    account.topup_micros -= take
                else:
                    account.plan_micros -= take
                # Already-used refunded credits become explicit debt; balances never go negative.
                debt = reverse - take if grant.bucket == "topup" or grant.cycle_start == account.period_start else 0
                account.refund_debt_micros += debt
                grant.reversed_micros = target
                grant.refunded_cents = refunded
                _ledger(db, account, "refund:" + event_id, "refund_reversal", -take,
                        detail={"reversal_micros": reverse, "debt_micros": debt})
        db.add(StripeEvent(id=event_id, event_type=kind, payload_hash=fingerprint, workspace_id=account.workspace_id, outcome=outcome))
        db.commit()
        return {"received": True, "outcome": outcome}
    except Exception:
        db.rollback()
        raise


@router.post("/billing/webhook")
async def webhook(request: Request, db: Session = Depends(get_db)):
    raw = await request.body()
    event = verify_stripe_event(raw, request.headers.get("Stripe-Signature", ""), getattr(settings, "stripe_webhook_secret", ""))
    if event.get("livemode") is not bool(getattr(settings, "stripe_live_mode", False)):
        raise HTTPException(400, "Webhook mode does not match this deployment")
    expected_version = getattr(settings, "stripe_api_version", STRIPE_API_VERSION)
    if event.get("api_version") and event["api_version"] != expected_version:
        raise HTTPException(409, "Configure the webhook snapshot API version to match this deployment")
    return process_stripe_event(db, event)


@router.get("/admin/costs")
def admin_costs(user: User = Depends(require_admin), db: Session = Depends(get_db)):
    rows = list(db.scalars(select(UsageOperation).order_by(UsageOperation.created_at.desc()).limit(200)))
    grouped = db.execute(select(UsageOperation.provider, func.count(UsageOperation.id), func.sum(UsageOperation.normalized_micros), func.sum(UsageOperation.actual_cost_micros), func.sum(UsageOperation.charged_micros), func.sum(UsageOperation.unfunded_micros)).group_by(UsageOperation.provider)).all()
    return {"currency": "USD", "price_registry_version": PRICE_REGISTRY_VERSION,
            "providers": [{"provider": row[0], "operations": row[1], "normalized_micros": row[2] or 0,
                           "actual_cost_micros": row[3], "charged_micros": row[4] or 0, "unfunded_micros": row[5] or 0} for row in grouped],
            "operations": [{"id": row.id, "workspace_id": row.workspace_id, "encounter_id": row.encounter_id,
                            "task": row.task, "status": row.status, "provider": row.provider, "model": row.model,
                            "normalized_micros": row.normalized_micros, "actual_cost_micros": row.actual_cost_micros,
                            "charged_micros": row.charged_micros, "admin_exempt": row.admin_exempt,
                            "unfunded_micros": row.unfunded_micros, "usage": row.usage, "created_at": row.created_at} for row in rows]}


# ---------------------------------------------------------------------------------------------------------------------
# Stuck-credit recovery ("AI leases"), 1.8.5-test10. Development only: active only when settings.ai_leases_enabled is True.
#
# Problem: a request reserves (holds) credits, calls the AI, then settles. If the server process dies in between (Cloud Run
# restart, crash), the hold is never released and every retry of the same request answers "already processing" forever.
#
# Rules (plain words):
#   * While a request is being worked on, its worker renews a lease (in the existing `usage` JSON; no schema change).
#   * A hold whose lease ran out (no renewal for the lease time plus a grace period) is RELEASED: the full hold goes back to
#     the customer, nothing is ever charged, and the request is marked `lease_expired`.
#   * A late worker that finishes after its hold was released cannot charge or save: `settle` already refuses any operation
#     that is not "reserved" (fencing). Amilya absorbs that provider cost (approved decision 5).
#   * A retry of the same request then makes a NEW uncharged-on-failure attempt, within the normal attempt limit.
#   * The batch job never charges. It can run in report-only mode.
# ---------------------------------------------------------------------------------------------------------------------
LEASE_EXPIRED = "lease_expired"


def lease_started(db: Session, user: User, operation_id: str, token: str, now: float | None = None) -> bool:
    """Mark a fresh reservation as being worked on. Returns False when it is not this worker's to run."""
    now = time.time() if now is None else now
    operation = db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == user.workspace_id, UsageOperation.operation_id == operation_id))
    if not operation or operation.status != "reserved":
        return False
    operation.usage = {**(operation.usage or {}), "lease_token": token, "lease_expires_at": now + settings.ai_lease_seconds}
    db.commit()
    return True


def lease_heartbeat(db: Session, workspace_id: str, operation_id: str, token: str, now: float | None = None) -> bool:
    """Extend the lease only for the current worker and only while the hold is still reserved."""
    now = time.time() if now is None else now
    operation = db.scalar(select(UsageOperation).where(UsageOperation.workspace_id == workspace_id, UsageOperation.operation_id == operation_id))
    if not operation or operation.status != "reserved" or (operation.usage or {}).get("lease_token") != token:
        db.rollback()
        return False
    operation.usage = {**operation.usage, "lease_expires_at": now + settings.ai_lease_seconds}
    db.commit()
    return True


def lease_is_stale(operation: UsageOperation, now: float, grace_seconds: float) -> bool:
    """True when nobody has renewed this hold for its lease time plus [grace_seconds]."""
    if operation.status != "reserved":
        return False
    expires = (operation.usage or {}).get("lease_expires_at")
    if isinstance(expires, (int, float)) and not isinstance(expires, bool):
        return now >= expires + grace_seconds
    # A hold made before leases existed (or with leases off): only after a very long silence, far beyond any provider call.
    created = operation.created_at
    if created is None:
        return False
    if created.tzinfo is None:
        from datetime import timezone
        created = created.replace(tzinfo=timezone.utc)
    return now - created.timestamp() >= max(grace_seconds, settings.ai_stale_release_quiet_seconds) + settings.provider_timeout_seconds * 5


def release_stale_hold(db: Session, operation: UsageOperation) -> bool:
    """Give the whole hold back, never charge, and record a retryable failed attempt. Caller holds no other locks; commits."""
    account = _lock_account(db, operation.workspace_id)
    operation = db.scalar(select(UsageOperation).where(UsageOperation.id == operation.id).execution_options(populate_existing=True))
    if operation is None or operation.status != "reserved":
        db.rollback()
        return False
    # Re-check under the account lock: the lease may have been renewed
    # between the batch scan and this transaction.
    if not lease_is_stale(operation, time.time(), settings.ai_stale_release_quiet_seconds):
        db.rollback()
        return False
    held = operation.reserved_plan_micros + operation.reserved_topup_micros
    plan_return = operation.reserved_plan_micros if operation.cycle_start == account.period_start and account.period_end > int(time.time()) and account.status not in {"canceled", "incomplete_expired"} else 0
    account.plan_micros += plan_return
    account.topup_micros += operation.reserved_topup_micros
    account.reserved_micros -= held
    usage = dict(operation.usage or {})
    usage.pop("lease_token", None)
    attempts = [a for a in usage.get("attempts", []) if isinstance(a, dict)][:19]
    attempts.append({"provider": operation.provider or "unknown", "model": operation.model or "unknown", "status": "failed", "error_code": "503"})
    usage.update({"failure_reason": LEASE_EXPIRED, "charge_waived": True, "waive_reason": LEASE_EXPIRED, "attempts": attempts})
    operation.usage = usage
    operation.status = "released"
    operation.release_reason = LEASE_EXPIRED
    operation.charged_micros = 0
    operation.completed_at = utcnow()
    _ledger(db, account, f"release:{operation.operation_id}", "released", plan_return + operation.reserved_topup_micros, -held,
            operation.user_id, {"reason": LEASE_EXPIRED, "charged_micros": 0})
    db.commit()
    return True


def is_lease_expired_release(operation: UsageOperation | None) -> bool:
    return bool(operation is not None and operation.status == "released" and operation.release_reason == LEASE_EXPIRED)


def release_stale_holds(db: Session, now: float | None = None, *, dry_run: bool = True, limit: int = 500) -> dict:
    """Batch job: find holds silent for the quiet period. dry_run=True only reports. Never charges."""
    now = time.time() if now is None else now
    candidates = list(db.scalars(select(UsageOperation).where(UsageOperation.status == "reserved").order_by(UsageOperation.created_at).limit(limit)))
    stale = [op for op in candidates if lease_is_stale(op, now, settings.ai_stale_release_quiet_seconds)]
    report = {"checked": len(candidates), "stale": len(stale), "released": 0, "held_micros": sum(op.reserved_plan_micros + op.reserved_topup_micros for op in stale),
              "dry_run": dry_run, "operations": [op.operation_id[:12] for op in stale]}
    db.rollback()
    if not dry_run:
        for op in stale:
            if release_stale_hold(db, op):
                report["released"] += 1
    return report


def absorbed_cost_report(db: Session, since_epoch: float) -> dict:
    """Cost monitoring: attempts we paid the provider for (or may have) that the customer was not charged for."""
    from datetime import datetime, timezone
    since = datetime.fromtimestamp(since_epoch, timezone.utc)
    rows = list(db.scalars(select(UsageOperation).where(UsageOperation.created_at >= since)))
    waived = [r for r in rows if (r.usage or {}).get("charge_waived") is True]
    return {"operations": len(rows), "waived_attempts": len(waived),
            "lease_expired": sum(1 for r in waived if r.release_reason == LEASE_EXPIRED),
            "known_absorbed_cost_micros": sum(r.actual_cost_micros or 0 for r in waived),
            "unknown_cost_attempts": sum(1 for r in waived if r.actual_cost_micros is None),
            "charged_micros": sum(r.charged_micros for r in rows)}
