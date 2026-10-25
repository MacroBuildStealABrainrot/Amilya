"""Integer credit accounting, payment event deduplication and operational records."""
from __future__ import annotations

from datetime import datetime
from sqlalchemy import BigInteger, Boolean, CheckConstraint, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from server.db import Base, utcnow, uid


class BillingAccount(Base):
    __tablename__ = "billing_accounts"
    __table_args__ = (
        CheckConstraint("plan_micros >= 0 AND topup_micros >= 0 AND reserved_micros >= 0 AND refund_debt_micros >= 0", name="ck_billing_nonnegative"),
    )
    workspace_id: Mapped[str] = mapped_column(ForeignKey("workspaces.id"), primary_key=True)
    customer_id: Mapped[str | None] = mapped_column(String(128), unique=True)
    subscription_id: Mapped[str | None] = mapped_column(String(128), unique=True)
    subscription_item_id: Mapped[str | None] = mapped_column(String(128))
    plan: Mapped[str | None] = mapped_column(String(24))
    status: Mapped[str] = mapped_column(String(32), default="inactive")
    cancel_at_period_end: Mapped[bool] = mapped_column(Boolean, default=False)
    period_start: Mapped[int] = mapped_column(BigInteger, default=0)
    period_end: Mapped[int] = mapped_column(BigInteger, default=0)
    state_event_created: Mapped[int] = mapped_column(BigInteger, default=0)
    paid_event_created: Mapped[int] = mapped_column(BigInteger, default=0)
    cycle_granted_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    cycle_plan_ceiling_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    plan_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    topup_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    reserved_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    refund_debt_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    version: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class CreditLedger(Base):
    __tablename__ = "credit_ledger"
    __table_args__ = (UniqueConstraint("workspace_id", "event_key", name="uq_credit_event"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(ForeignKey("workspaces.id"), index=True)
    user_id: Mapped[str | None] = mapped_column(ForeignKey("users.id"))
    event_key: Mapped[str] = mapped_column(String(240))
    kind: Mapped[str] = mapped_column(String(40))
    delta_micros: Mapped[int] = mapped_column(BigInteger)
    reserved_delta_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    balance_after_micros: Mapped[int] = mapped_column(BigInteger)
    detail: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class UsageOperation(Base):
    __tablename__ = "usage_operations"
    __table_args__ = (
        UniqueConstraint("workspace_id", "operation_id", name="uq_usage_operation"),
        CheckConstraint("estimated_micros >= 0 AND reserved_plan_micros >= 0 AND reserved_topup_micros >= 0 AND normalized_micros >= 0 AND charged_micros >= 0 AND unfunded_micros >= 0", name="ck_usage_nonnegative"),
    )
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(ForeignKey("workspaces.id"), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    operation_id: Mapped[str] = mapped_column(String(160))
    encounter_id: Mapped[str | None] = mapped_column(String(36), index=True)
    task: Mapped[str] = mapped_column(String(64))
    request_hash: Mapped[str] = mapped_column(String(64))
    settlement_hash: Mapped[str | None] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(24), default="reserved", index=True)
    admin_exempt: Mapped[bool] = mapped_column(Boolean, default=False)
    estimated_micros: Mapped[int] = mapped_column(BigInteger)
    reserved_plan_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    reserved_topup_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    cycle_start: Mapped[int] = mapped_column(BigInteger, default=0)
    normalized_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    actual_cost_micros: Mapped[int | None] = mapped_column(BigInteger)
    charged_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    unfunded_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    provider: Mapped[str | None] = mapped_column(String(80))
    model: Mapped[str | None] = mapped_column(String(160))
    price_registry_version: Mapped[str] = mapped_column(String(64), default="normalized-2026-09-05")
    usage: Mapped[dict] = mapped_column(JSON, default=dict)
    release_reason: Mapped[str | None] = mapped_column(String(80))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class StripeEvent(Base):
    __tablename__ = "stripe_events"
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    event_type: Mapped[str] = mapped_column(String(80))
    payload_hash: Mapped[str] = mapped_column(String(64))
    workspace_id: Mapped[str | None] = mapped_column(ForeignKey("workspaces.id"), index=True)
    outcome: Mapped[str] = mapped_column(String(80), default="processed")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class PaymentGrant(Base):
    __tablename__ = "payment_grants"
    __table_args__ = (UniqueConstraint("source_id", name="uq_payment_grant_source"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(ForeignKey("workspaces.id"), index=True)
    source_id: Mapped[str] = mapped_column(String(128))
    payment_intent: Mapped[str | None] = mapped_column(String(128), index=True)
    bucket: Mapped[str] = mapped_column(String(16))
    cycle_start: Mapped[int] = mapped_column(BigInteger, default=0)
    granted_micros: Mapped[int] = mapped_column(BigInteger)
    reversed_micros: Mapped[int] = mapped_column(BigInteger, default=0)
    amount_cents: Mapped[int] = mapped_column(BigInteger)
    refunded_cents: Mapped[int] = mapped_column(BigInteger, default=0)
    currency: Mapped[str] = mapped_column(String(3), default="cad")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class CheckoutAttempt(Base):
    __tablename__ = "checkout_attempts"
    __table_args__ = (UniqueConstraint("workspace_id", "request_key", name="uq_checkout_request"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(ForeignKey("workspaces.id"), index=True)
    request_key: Mapped[str] = mapped_column(String(128))
    plan: Mapped[str] = mapped_column(String(24))
    stripe_price_id: Mapped[str] = mapped_column(String(128))
    stripe_session_id: Mapped[str | None] = mapped_column(String(128), unique=True)
    url: Mapped[str | None] = mapped_column(Text)
    expires_at: Mapped[int] = mapped_column(BigInteger, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ContactRequest(Base):
    __tablename__ = "contact_requests"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    name: Mapped[str] = mapped_column(String(120))
    email: Mapped[str] = mapped_column(String(254))
    organization: Mapped[str | None] = mapped_column(String(180))
    role: Mapped[str | None] = mapped_column(String(120))
    team_size: Mapped[str | None] = mapped_column(String(80))
    region: Mapped[str | None] = mapped_column(String(100))
    subject: Mapped[str | None] = mapped_column(String(180))
    message: Mapped[str] = mapped_column(Text)
    type: Mapped[str] = mapped_column(String(24))
    category: Mapped[str] = mapped_column(String(24), default="support")
    status: Mapped[str] = mapped_column(String(24), default="new", index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, index=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)
