"""Synthetic commerce integration, concurrent-ledger and webhook adversarial tests."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import json
import time
from types import SimpleNamespace

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, func, select
from sqlalchemy.orm import sessionmaker

from server import billing, contact, identity
from server.db import Base, get_db, uid
from server.identity import Membership, User, Workspace, current_user, require_csrf
from server.billing_models import BillingAccount, CheckoutAttempt, ContactRequest, CreditLedger, PaymentGrant, StripeEvent, UsageOperation


@pytest.fixture
def commerce(tmp_path, monkeypatch):
    engine = create_engine("sqlite:///" + (tmp_path / "commerce.db").as_posix(), connect_args={"check_same_thread": False, "timeout": 30})
    @event.listens_for(engine, "connect")
    def pragmas(conn, _):
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=30000")
    Base.metadata.create_all(engine)
    sessions = sessionmaker(bind=engine, expire_on_commit=False, autoflush=False)
    with sessions() as db:
        workspace = Workspace(name="Synthetic team")
        other_workspace = Workspace(name="Synthetic other")
        db.add_all([workspace, other_workspace]); db.flush()
        user = User(email="synthetic@example.test", display_name="Synthetic user", workspace_id=workspace.id, password_hash="test-not-a-password-hash")
        other_user = User(email="synthetic-other@example.test", display_name="Other", workspace_id=other_workspace.id, password_hash="test-not-a-password-hash")
        admin = User(email="synthetic-admin@example.test", display_name="Synthetic admin", workspace_id=other_workspace.id,
                     password_hash="test-not-a-password-hash", is_platform_admin=True)
        db.add_all([user, other_user, admin]); db.flush()
        db.add_all([Membership(user_id=user.id, workspace_id=workspace.id, role="owner"),
                    Membership(user_id=other_user.id, workspace_id=other_workspace.id, role="clinician")])
        db.commit()
    config = SimpleNamespace(**{f"stripe_price_{key}": "price_test_" + key for key in [*billing.PLANS, "topup"]},
                             stripe_secret_key="", stripe_webhook_secret="synthetic-webhook-secret", stripe_live_mode=False,
                             stripe_api_version=billing.STRIPE_API_VERSION, billing_enabled=True,
                             public_base_url="http://testserver", legal_version="2026-09-05")
    monkeypatch.setattr(billing, "settings", config)
    monkeypatch.setattr(contact, "settings", config)
    monkeypatch.setattr(identity, "engine", engine)
    monkeypatch.setattr(identity, "SessionLocal", sessions)
    # No network transport is used by these tests; Stripe API is replaced per test.
    async def forbidden_stripe(*args, **kwargs):
        raise AssertionError("Test attempted a real Stripe call")
    monkeypatch.setattr(billing, "stripe_request", forbidden_stripe)
    app = FastAPI()
    app.include_router(billing.router, prefix="/api/v1")
    app.include_router(contact.router, prefix="/api/v1")
    selected = {"user": user}
    def database():
        with sessions() as db:
            yield db
    app.dependency_overrides[get_db] = database
    app.dependency_overrides[current_user] = lambda: selected["user"]
    app.dependency_overrides[require_csrf] = lambda: selected["user"]
    with TestClient(app) as client:
        yield SimpleNamespace(db=sessions, user=user, other=other_user, admin=admin, config=config,
                              client=client, select=selected, monkeypatch=monkeypatch)
    engine.dispose()


def fund(env, micros=10000, topup=0, status="active"):
    now = int(time.time())
    with env.db() as db:
        account = billing._lock_account(db, env.user.workspace_id)
        account.customer_id = "cus_synthetic"
        account.subscription_id = "sub_synthetic"
        account.subscription_item_id = "si_synthetic"
        account.status = status
        account.plan = "essential"
        account.period_start = now - 100
        account.period_end = now + 10000
        account.plan_micros = micros
        account.topup_micros = topup
        db.commit()


def invoice(env, plan="pro", invoice_id="in_synthetic", start=None, end=None, created=None):
    now = int(time.time())
    return {"id": "evt_" + invoice_id, "type": "invoice.paid", "created": created or now,
            "livemode": False, "api_version": billing.STRIPE_API_VERSION,
            "data": {"object": {"id": invoice_id, "customer": "cus_synthetic", "status": "paid", "paid": True,
                "currency": "cad", "amount_paid": billing.PLANS[plan]["price_cents"],
                "parent": {"subscription_details": {"subscription": "sub_synthetic"}},
                "payments": {"data": [{"payment": {"payment_intent": "pi_" + invoice_id}}]},
                "lines": {"data": [{"pricing": {"price_details": {"price": getattr(env.config, "stripe_price_" + plan)}},
                            "quantity": 1, "amount": billing.PLANS[plan]["price_cents"],
                            "period": {"start": start or now, "end": end or now + 2592000}}], "has_more": False}}}}


def send_event(env, event, signature=True):
    raw = json.dumps(event, separators=(",", ":")).encode()
    timestamp = int(time.time())
    digest = hmac.new(env.config.stripe_webhook_secret.encode(), str(timestamp).encode() + b"." + raw, hashlib.sha256).hexdigest()
    return env.client.post("/api/v1/billing/webhook", content=raw,
                           headers={"Content-Type": "application/json", "Stripe-Signature": f"t={timestamp},v1={digest if signature else 'invalid'}"})


def test_account_unlimited_credits_preserve_costs_without_admin_access(commerce):
    env = commerce
    env.config.unlimited_credit_user_ids = env.user.id
    assert env.client.get('/api/v1/billing').json()['is_unlimited'] is True
    assert env.client.get('/api/v1/admin/costs').status_code == 403
    assert env.user.is_platform_admin is False
    with env.db() as db:
        result = billing.reserve(db, env.user, 'account-unlimited', 5000)
        assert result['created'] is True
        settled = billing.settle(db, env.user, 'account-unlimited', 7500, 'synthetic', 'model', {'input_tokens': 12}, 6200)
        assert settled['charged_micros'] == 0
        assert settled['normalized_micros'] == 7500
        assert settled['actual_cost_micros'] == 6200
        assert settled['unfunded_micros'] == 0
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == account.topup_micros == account.reserved_micros == 0
    env.select['user'] = env.other
    assert env.client.get('/api/v1/billing').json()['is_unlimited'] is False
    with env.db() as db, pytest.raises(HTTPException) as denied:
        billing.reserve(db, env.other, 'other-not-unlimited', 100)
    assert denied.value.status_code == 402


def test_unlimited_credit_grant_is_exact_id_bound_and_revocable(commerce):
    env = commerce
    for misleading in [env.user.email, env.user.id[:-1], '*', '']:
        env.config.unlimited_credit_user_ids = misleading
        assert billing.has_unlimited_credits(env.user) is False
    env.config.unlimited_credit_user_ids = f'  {env.user.id} , '
    with env.db() as db:
        billing.reserve(db, env.user, 'before-revocation', 1000)
    env.config.unlimited_credit_user_ids = ''
    assert env.client.get('/api/v1/billing').json()['is_unlimited'] is False
    with env.db() as db:
        settled = billing.settle(db, env.user, 'before-revocation', 1500, 'synthetic', 'model', {})
        assert settled['charged_micros'] == 0
        with pytest.raises(HTTPException) as denied:
            billing.reserve(db, env.user, 'after-revocation', 100)
        assert denied.value.status_code == 402


def test_unsubscribed_ai_gate_admin_exemption_and_cost_recording(commerce):
    env = commerce
    with env.db() as db:
        with pytest.raises(HTTPException) as error:
            billing.reserve(db, env.user, "denied", 2000, task="review")
        assert error.value.status_code == 402
        allowed = billing.reserve(db, env.admin, "owner-work", 2500, task="review")
        assert allowed["created"] and allowed["admin_exempt"]
        settled = billing.settle(db, env.admin, "owner-work", 2301, "synthetic", "model", {"input_tokens": 12, "text": "must not be stored"}, 1900)
        assert settled["charged_micros"] == 0 and settled["actual_cost_micros"] == 1900
        operation = db.scalar(select(UsageOperation).where(UsageOperation.operation_id == "owner-work"))
        assert operation.normalized_micros == 2301 and "text" not in operation.usage


def test_reserve_settle_release_idempotent_and_payload_conflict(commerce):
    env = commerce; fund(env, 10000, 3000)
    with env.db() as db:
        first = billing.reserve(db, env.user, "op", 4000, task="draft")
        again = billing.reserve(db, env.user, "op", 4000, task="draft")
        assert first["created"] and not again["created"]
        with pytest.raises(HTTPException) as error:
            billing.reserve(db, env.user, "op", 4001, task="draft")
        assert error.value.status_code == 409
        result = billing.settle(db, env.user, "op", 2001, "test", "model", {"input_tokens": 3}, 1700)
        again = billing.settle(db, env.user, "op", 2001, "test", "model", {"input_tokens": 3}, 1700)
        assert result == again
        assert db.get(BillingAccount, env.user.workspace_id).plan_micros == 7999
        with pytest.raises(HTTPException) as error:
            billing.settle(db, env.user, "op", 2200, "test", "model", {}, 1700)
        assert error.value.status_code == 409
        billing.reserve(db, env.user, "fail", 6000, task="image")
        billing.release(db, env.user, "fail", "timeout")
        billing.release(db, env.user, "fail", "timeout")
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 7999 and account.reserved_micros == 0
        assert db.scalar(select(func.count()).select_from(CreditLedger).where(CreditLedger.event_key == "settle:op")) == 1


def test_competing_reservations_cannot_double_spend(commerce):
    env = commerce; fund(env, 1000)
    def compete(index):
        with env.db() as db:
            try:
                billing.reserve(db, env.user, f"parallel-{index}", 700, task="image")
                return "reserved"
            except HTTPException as error:
                return error.status_code
    with ThreadPoolExecutor(max_workers=10) as pool:
        results = list(pool.map(compete, range(10)))
    assert results.count("reserved") == 1 and results.count(402) == 9
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 300 and account.reserved_micros == 700


def test_same_operation_concurrency_only_one_new_reservation(commerce):
    env = commerce; fund(env, 10000)
    def compete(_):
        with env.db() as db:
            return billing.reserve(db, env.user, "same", 2000, task="draft")["created"]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(compete, range(8)))
    assert sum(results) == 1
    with env.db() as db:
        assert db.get(BillingAccount, env.user.workspace_id).plan_micros == 8000


def test_settlement_can_commit_atomically_with_provider_replay(commerce):
    env = commerce; fund(env, 10000)
    with env.db() as db:
        billing.reserve(db, env.user, "atomic", 2000, task="draft")
        billing.settle(db, env.user, "atomic", 1500, "test", "model", {}, 1200, commit=False)
        # Simulate a replay insert failure: the caller rolls back the entire settlement.
        db.rollback()
        operation = db.scalar(select(UsageOperation).where(UsageOperation.operation_id == "atomic"))
        assert operation.status == "reserved"
        assert db.get(BillingAccount, env.user.workspace_id).reserved_micros == 2000
        assert db.scalar(select(func.count()).select_from(CreditLedger).where(CreditLedger.event_key == "settle:atomic")) == 0
        billing.settle(db, env.user, "atomic", 1500, "test", "model", {}, 1200, commit=False)
        db.commit()
        assert db.get(BillingAccount, env.user.workspace_id).plan_micros == 8500


def test_unusable_output_waives_user_charge_preserves_normalized_and_actual_cost(commerce):
    env = commerce; fund(env, 10000)
    with env.db() as db:
        billing.reserve(db, env.user, "invalid-output", 2000)
        result = billing.settle(db, env.user, "invalid-output", 1500, "test", "model", {"input_tokens": 50}, 1250,
                                waive_charge=True, waive_reason="invalid_schema")
        assert result["normalized_micros"] == 1500 and result["actual_cost_micros"] == 1250
        assert result["charged_micros"] == 0 and result["unfunded_micros"] == 0
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 10000 and account.reserved_micros == 0
        ledger = db.scalar(select(CreditLedger).where(CreditLedger.event_key == "settle:invalid-output"))
        assert ledger.detail["charge_waived"] is True and ledger.detail["waive_reason"] == "invalid_schema"


def test_fractional_credits_underestimate_and_tenant_scoping(commerce):
    env = commerce; fund(env, 101)
    with env.db() as db:
        billing.reserve(db, env.user, "micro", 100, task="extract")
        result = billing.settle(db, env.user, "micro", 125, "test", "model", {}, 120)
        assert result["charged_micros"] == 101 and result["unfunded_micros"] == 24
        assert db.get(BillingAccount, env.user.workspace_id).plan_micros == 0
        with pytest.raises(HTTPException) as error:
            billing.settle(db, env.other, "micro", 125, "test", "model", {})
        assert error.value.status_code == 404


@pytest.mark.parametrize("plan,credits", [("essential", 2500), ("plus", 5000), ("pro", 12000), ("power", 18000)])
def test_signed_paid_invoice_grants_exact_plan_once(commerce, plan, credits):
    env = commerce; fund(env, 0, status="pending_payment")
    event = invoice(env, plan)
    assert send_event(env, event).status_code == 200
    assert send_event(env, event).json()["duplicate"] is True
    copied = dict(event, id="evt_different_id_same_invoice")
    assert send_event(env, copied).status_code == 200
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == credits * 1000 and account.status == "active"
        assert db.scalar(select(func.count()).select_from(PaymentGrant)) == 1


def test_webhook_bad_signature_replay_mode_version_and_price_rejected(commerce):
    env = commerce; fund(env, 0)
    event = invoice(env)
    assert send_event(env, event, signature=False).status_code == 400
    raw = json.dumps(event).encode()
    timestamp = int(time.time()) - 360
    digest = hmac.new(env.config.stripe_webhook_secret.encode(), str(timestamp).encode() + b"." + raw, hashlib.sha256).hexdigest()
    response = env.client.post("/api/v1/billing/webhook", content=raw, headers={"Stripe-Signature": f"t={timestamp},v1={digest}"})
    assert response.status_code == 400
    assert send_event(env, dict(event, livemode=True)).status_code == 400
    assert send_event(env, dict(event, api_version="1900-01-01.invalid")).status_code == 409
    event["data"]["object"]["lines"]["data"][0]["pricing"]["price_details"]["price"] = "price_untrusted"
    assert send_event(env, event).status_code == 409
    with env.db() as db:
        assert db.get(BillingAccount, env.user.workspace_id).plan_micros == 0
        assert db.scalar(select(func.count()).select_from(PaymentGrant)) == 0


def test_renewal_reset_topup_preserved_upgrade_prorated_and_stale_failure(commerce):
    env = commerce; fund(env, 0, topup=1000000)
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        account.period_start = account.period_end = 0
        account.status = "pending_payment"
        db.commit()
    now = int(time.time()); start = now - 100; end = now + 100
    initial = invoice(env, "essential", start=start, end=end, created=now - 10)
    assert send_event(env, initial).status_code == 200
    upgrade = invoice(env, "pro", "in_upgrade", start=now, end=end, created=now)
    upgrade["data"]["object"]["billing_reason"] = "subscription_update"
    assert send_event(env, upgrade).status_code == 200
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 2500000 + 9500000 // 2
        assert account.topup_micros == 1000000
    # Two paid snapshots of the same upgrade cannot grant the capacity twice.
    upgrade2 = invoice(env, "pro", "in_upgrade_again", start=now, end=end, created=now + 1)
    assert send_event(env, upgrade2).status_code == 200
    renewed = invoice(env, "plus", "in_renewed", start=end, end=end + 200, created=now + 2)
    assert send_event(env, renewed).status_code == 503
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.period_start == start and account.period_end == end
        assert account.plan_micros == 2500000 + 9500000 // 2 and billing._active(account)
        assert db.get(StripeEvent, renewed["id"]) is None
    # Retry at the actual boundary applies once without erasing the prior cycle early.
    env.monkeypatch.setattr(billing.time, "time", lambda: end)
    assert send_event(env, renewed).status_code == 200
    failed = {"id": "evt_stale_failure", "type": "invoice.payment_failed", "created": now - 1, "livemode": False,
              "data": {"object": {"customer": "cus_synthetic", "subscription": "sub_synthetic"}}}
    assert send_event(env, failed).status_code == 200
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 5000000 and account.topup_micros == 1000000 and account.status == "active"


def test_payment_failure_cancellation_freeze_topups(commerce):
    env = commerce; fund(env, 3000, topup=1000)
    now = int(time.time())
    failed = {"id": "evt_failure", "type": "invoice.payment_failed", "created": now, "livemode": False,
              "data": {"object": {"customer": "cus_synthetic", "subscription": "sub_synthetic"}}}
    assert send_event(env, failed).status_code == 200
    with env.db() as db:
        with pytest.raises(HTTPException) as error:
            billing.reserve(db, env.user, "after-failure", 1)
        assert error.value.status_code == 402
    canceled = {"id": "evt_cancel", "type": "customer.subscription.deleted", "created": now + 1, "livemode": False,
                "data": {"object": {"id": "sub_synthetic", "customer": "cus_synthetic", "status": "canceled"}}}
    assert send_event(env, canceled).status_code == 200
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 0 and account.topup_micros == 1000 and account.status == "canceled"


def test_reservation_return_across_cycle_does_not_resurrect_expired_plan(commerce):
    env = commerce; fund(env, 1000, topup=1000)
    with env.db() as db:
        billing.reserve(db, env.user, "cross-cycle", 1500)
        account = billing._lock_account(db, env.user.workspace_id)
        account.period_start += 1
        account.plan_micros = 2500
        db.commit()
        billing.release(db, env.user, "cross-cycle", "timeout")
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 2500 and account.topup_micros == 1000 and account.reserved_micros == 0


def test_cross_cycle_settle_preserves_unused_topup(commerce):
    env = commerce
    fund(env, 1000, topup=1000)
    with env.db() as db:
        billing.reserve(db, env.user, "cross-cycle-settle", 1500)
        account = billing._lock_account(db, env.user.workspace_id)
        account.period_start += 1
        account.plan_micros = 2500
        db.commit()
        billing.settle(db, env.user, "cross-cycle-settle", 200, "synthetic", "model", {})
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 2500
        assert account.topup_micros == 1000
        assert account.reserved_micros == 0


def test_terminal_subscription_and_paid_invoice_resist_same_second_reordering(commerce):
    env = commerce; fund(env, 0, topup=1000)
    now = int(time.time())
    paid = invoice(env, created=now)
    assert send_event(env, paid).status_code == 200
    failed = {"id": "evt_late_failure", "type": "invoice.payment_failed", "created": now, "livemode": False,
              "data": {"object": {"id": "in_synthetic", "customer": "cus_synthetic", "subscription": "sub_synthetic"}}}
    assert send_event(env, failed).json()["outcome"] == "terminal_payment_failure_ignored"
    canceled = {"id": "evt_terminal", "type": "customer.subscription.deleted", "created": now, "livemode": False,
                "data": {"object": {"id": "sub_synthetic", "customer": "cus_synthetic", "status": "canceled"}}}
    assert send_event(env, canceled).status_code == 200
    active = {"id": "evt_late_active", "type": "customer.subscription.updated", "created": now, "livemode": False,
              "data": {"object": {"id": "sub_synthetic", "customer": "cus_synthetic", "status": "active"}}}
    assert send_event(env, active).json()["outcome"] == "terminal_subscription_event_ignored"
    assert send_event(env, invoice(env, invoice_id="in_late", created=now + 1)).json()["outcome"] == "canceled_subscription_invoice_ignored"
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.status == "canceled" and account.plan_micros == 0 and account.topup_micros == 1000
    # A separately purchased subscription can activate; the old ID remains terminal.
    replacement = invoice(env, invoice_id="in_replacement", created=now + 2, end=now + 2592001)
    replacement["data"]["object"]["parent"]["subscription_details"]["subscription"] = "sub_replacement"
    assert send_event(env, replacement).status_code == 200
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.subscription_id == "sub_replacement" and account.status == "active"
        assert account.plan_micros == 12000000


def test_checkout_topup_requires_subscription_and_server_price_validation(commerce):
    env = commerce
    calls = []
    async def stripe(method, path, data=None, idempotency_key=None):
        calls.append((method, path, data, idempotency_key))
        if path.startswith("prices/"):
            return {"active": True, "currency": "cad", "unit_amount": 899, "recurring": None}
        raise AssertionError(path)
    env.monkeypatch.setattr(billing, "stripe_request", stripe)
    assert env.client.post("/api/v1/billing/checkout", json={"plan": "topup"}).status_code == 403
    assert env.client.post("/api/v1/billing/checkout", json={"plan": "pro", "credits": 999999}).status_code == 422
    assert env.client.post("/api/v1/billing/checkout", json={"plan": "pro"}).status_code == 503
    assert not any("checkout/sessions" == call[1] for call in calls)


def test_topup_grant_and_refund_are_idempotent(commerce):
    env = commerce; fund(env, 0)
    with env.db() as db:
        attempt = CheckoutAttempt(workspace_id=env.user.workspace_id, request_key="topup", plan="topup",
                                  stripe_price_id=env.config.stripe_price_topup, stripe_session_id="cs_topup")
        db.add(attempt); db.commit(); attempt_id = attempt.id
    event = {"id": "evt_topup", "type": "checkout.session.completed", "created": int(time.time()), "livemode": False,
             "data": {"object": {"id": "cs_topup", "customer": "cus_synthetic", "mode": "payment", "payment_status": "paid",
                      "payment_intent": "pi_topup", "amount_total": 899, "currency": "cad", "metadata": {"checkout_attempt_id": attempt_id}}}}
    assert send_event(env, event).status_code == 200
    assert send_event(env, dict(event, id="evt_topup_duplicate_object")).status_code == 200
    with env.db() as db:
        assert db.get(BillingAccount, env.user.workspace_id).topup_micros == 1000000
        billing.reserve(db, env.user, "use-topup", 600000)
        billing.settle(db, env.user, "use-topup", 600000, "test", "model", {}, 550000)
    refund = {"id": "evt_refund", "type": "charge.refunded", "created": int(time.time()), "livemode": False,
              "data": {"object": {"id": "ch_topup", "customer": "cus_synthetic", "payment_intent": "pi_topup", "amount_refunded": 899}}}
    assert send_event(env, refund).status_code == 200
    assert send_event(env, dict(refund, id="evt_refund_again")).status_code == 200
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.topup_micros == 0 and account.refund_debt_micros == 600000


def test_checkout_adapter_success_retry_completion_and_portal(commerce):
    env = commerce
    calls = []
    async def stripe(method, path, data=None, idempotency_key=None):
        calls.append((method, path, data, idempotency_key))
        if path.startswith("prices/"):
            return {"active": True, "currency": "cad", "unit_amount": 4999,
                    "recurring": {"interval": "month", "interval_count": 1}}
        if path == "customers":
            return {"id": "cus_checkout"}
        if path == "checkout/sessions":
            assert data["line_items[0][price]"] == env.config.stripe_price_pro
            assert data["mode"] == "subscription"
            return {"id": "cs_created", "url": "https://checkout.stripe.com/c/pay/synthetic", "expires_at": int(time.time()) + 1800}
        if path == "billing_portal/sessions":
            assert data["customer"] == "cus_checkout"
            return {"url": "https://billing.stripe.com/p/session/synthetic"}
        raise AssertionError(path)
    env.monkeypatch.setattr(billing, "stripe_request", stripe)
    first = env.client.post("/api/v1/billing/checkout", json={"plan": "pro"}, headers={"Idempotency-Key": "request-pro"})
    assert first.status_code == 200, first.text
    retry = env.client.post("/api/v1/billing/checkout", json={"plan": "pro"}, headers={"Idempotency-Key": "request-pro"})
    assert retry.status_code == 200 and retry.json() == first.json()
    assert sum(path == "checkout/sessions" for _, path, _, _ in calls) == 1
    with env.db() as db:
        attempt = db.scalar(select(CheckoutAttempt))
        attempt_id = attempt.id
    completed = {"id": "evt_complete", "type": "checkout.session.completed", "created": int(time.time()), "livemode": False,
                 "data": {"object": {"id": "cs_created", "status": "complete", "customer": "cus_checkout", "subscription": "sub_checkout",
                          "metadata": {"checkout_attempt_id": attempt_id}}}}
    assert send_event(env, completed).status_code == 200
    with env.db() as db:
        account = db.get(BillingAccount, env.user.workspace_id)
        assert account.plan_micros == 0 and account.status == "pending_payment"  # Checkout alone never grants credits.
    assert env.client.post("/api/v1/billing/portal").status_code == 200
    env.select["user"] = env.other
    assert env.client.post("/api/v1/billing/portal").status_code == 403


def test_existing_subscription_uses_confirmed_plan_update_not_second_checkout(commerce):
    env = commerce; fund(env)
    calls = []
    async def stripe(method, path, data=None, idempotency_key=None):
        calls.append((path, data))
        if path.startswith("prices/"):
            return {"active": True, "currency": "cad", "unit_amount": 4999, "recurring": {"interval": "month", "interval_count": 1}}
        assert path == "billing_portal/sessions"
        assert data["flow_data[type]"] == "subscription_update_confirm"
        assert data["flow_data[subscription_update_confirm][items][0][price]"] == env.config.stripe_price_pro
        return {"url": "https://billing.stripe.com/p/session/synthetic"}
    env.monkeypatch.setattr(billing, "stripe_request", stripe)
    result = env.client.post("/api/v1/billing/checkout", json={"plan": "pro"})
    assert result.status_code == 200
    assert not any(path == "checkout/sessions" for path, _ in calls)


def test_concurrent_duplicate_payment_delivery_grants_once(commerce):
    env = commerce; fund(env, 0, status="pending_payment")
    event = invoice(env)
    def deliver(_):
        with env.db() as db:
            return billing.process_stripe_event(db, event)
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(deliver, range(8)))
    assert sum(not row.get("duplicate", False) for row in results) == 1
    with env.db() as db:
        assert db.get(BillingAccount, env.user.workspace_id).plan_micros == 12000000
        assert db.scalar(select(func.count()).select_from(PaymentGrant)) == 1


def test_webhook_event_id_reuse_with_altered_content_is_rejected(commerce):
    env = commerce; fund(env, 0)
    event = invoice(env)
    assert send_event(env, event).status_code == 200
    altered = json.loads(json.dumps(event))
    altered["data"]["object"]["amount_paid"] += 100
    assert send_event(env, altered).status_code == 409


def test_contact_persists_honeypot_rate_limit_and_admin_roles(commerce):
    env = commerce
    payload = {"name": "Synthetic sender", "email": "sender@example.test", "message": "Please explain team billing.", "type": "business", "no_patient_information": True}
    response = env.client.post("/api/v1/contact", json=payload)
    assert response.status_code == 201
    contact_id = response.json()["id"]
    assert env.client.get("/api/v1/admin/contacts").status_code == 403
    assert env.client.get("/api/v1/admin/overview").status_code == 403
    assert env.client.get("/api/v1/admin/costs").status_code == 403
    env.select["user"] = env.admin
    records = env.client.get("/api/v1/admin/contacts").json()
    assert len(records) == 1 and records[0]["id"] == contact_id
    assert env.client.patch(f"/api/v1/admin/contacts/{contact_id}", json={"status": "resolved"}).status_code == 200
    assert env.client.post("/api/v1/contact", json=dict(payload, website="spam.example.test")).status_code == 201
    with env.db() as db:
        assert db.scalar(select(func.count()).select_from(ContactRequest)) == 1
    assert env.client.post("/api/v1/contact", json=dict(payload, no_patient_information=False)).status_code == 422
    assert env.client.post("/api/v1/contact", json=payload).status_code == 429
    assert env.client.get("/api/v1/admin/overview").json()["contacts"] == 1


def test_public_legal_documents_keep_owner_placeholders_and_review_flag(commerce):
    for document in ("privacy", "terms"):
        response = commerce.client.get("/api/v1/legal/" + document)
        assert response.status_code == 200
        body = response.json()
        assert body["requires_legal_review"] is True
        assert "[Owner must supply legal business name]" in body["content"]
        assert body["version"] == "2026-09-05"
