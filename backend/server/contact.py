from __future__ import annotations

from pathlib import Path
import re
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from server.core import settings
from server.db import get_db, uid
from server.identity import User, Workspace, check_rate, require_admin, require_csrf
from server.audit import write_audit
from server.billing_models import BillingAccount, ContactRequest, PaymentGrant, StripeEvent, UsageOperation

router = APIRouter(tags=["contact and legal"])


class ContactInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    name: str = Field(min_length=2, max_length=120)
    email: str = Field(min_length=5, max_length=254)
    organization: str | None = Field(None, max_length=180)
    role: str | None = Field(None, max_length=120)
    team_size: str | None = Field(None, max_length=80)
    region: str | None = Field(None, max_length=100)
    subject: str | None = Field(None, max_length=180)
    message: str = Field(min_length=5, max_length=4000)
    type: Literal["support", "business"] = "support"
    category: Literal["support", "billing", "account", "business", "privacy", "feedback", "other"] = "support"
    no_patient_information: bool = True
    website: str = Field("", max_length=200)

    @field_validator("email")
    @classmethod
    def valid_email(cls, value):
        if not re.fullmatch(r"[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+", value):
            raise ValueError("Enter a valid work email address")
        return value.lower()


@router.post("/contact", status_code=201)
def submit_contact(payload: ContactInput, request: Request, db: Session = Depends(get_db)):
    ip = request.client.host if request.client else "unknown"
    check_rate("contact-ip:" + ip, limit=5, window_seconds=600)
    check_rate("contact-email:" + payload.email, limit=3, window_seconds=3600)
    if payload.website:
        # A bot receives the normal shape without creating inbox noise.
        return {"id": uid(), "status": "received"}
    if not payload.no_patient_information:
        raise HTTPException(422, "Remove all patient information before sending a support or business request")
    combined = " ".join(filter(None, [payload.subject, payload.message]))
    if re.search(r"\b(?:health\s*card|OHIP|patient\s+(?:name|DOB)|date\s+of\s+birth)\s*[:#=]\s*\S", combined, re.I):
        raise HTTPException(422, "Do not include patient names, dates of birth, health-card numbers or other patient information")
    row = ContactRequest(**payload.model_dump(exclude={"website", "no_patient_information"}))
    db.add(row)
    db.commit()
    return {"id": row.id, "status": "received"}


@router.get("/admin/contacts")
def contacts(user: User = Depends(require_admin), db: Session = Depends(get_db),
             status: str | None = None, limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
    stmt = select(ContactRequest)
    if status:
        stmt = stmt.where(ContactRequest.status == status)
    return [{key: getattr(row, key) for key in ("id", "name", "email", "organization", "role", "team_size", "region", "subject", "message", "type", "category", "status", "created_at")}
            for row in db.scalars(stmt.order_by(ContactRequest.created_at.desc()).offset(offset).limit(limit))]


class ContactStatusInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: Literal["new", "in_progress", "resolved", "spam"]


@router.patch("/admin/contacts/{contact_id}", dependencies=[Depends(require_csrf)])
def update_contact(contact_id: str, payload: ContactStatusInput, user: User = Depends(require_admin), db: Session = Depends(get_db)):
    row = db.get(ContactRequest, contact_id)
    if not row:
        raise HTTPException(404, "Contact request not found")
    row.status = payload.status
    write_audit(db, user, "contact.status", row.id, {"status": row.status})
    db.commit()
    return {"id": row.id, "status": row.status}


@router.get("/admin/overview")
def overview(user: User = Depends(require_admin), db: Session = Depends(get_db)):
    totals = db.execute(select(func.sum(UsageOperation.normalized_micros), func.sum(UsageOperation.actual_cost_micros),
                               func.sum(UsageOperation.charged_micros), func.sum(UsageOperation.unfunded_micros))).one()
    revenue = db.execute(select(func.sum(PaymentGrant.amount_cents), func.sum(PaymentGrant.refunded_cents))).one()
    return {"workspaces": db.scalar(select(func.count()).select_from(Workspace)) or 0,
            "users": db.scalar(select(func.count()).select_from(User)) or 0,
            "active_subscriptions": db.scalar(select(func.count()).select_from(BillingAccount).where(BillingAccount.status == "active")) or 0,
            "new_contacts": db.scalar(select(func.count()).select_from(ContactRequest).where(ContactRequest.status == "new")) or 0,
            "contacts": db.scalar(select(func.count()).select_from(ContactRequest)) or 0,
            "costs": {"normalized_micros": totals[0] or 0, "actual_cost_micros": totals[1], "charged_micros": totals[2] or 0,
                      "unfunded_micros": totals[3] or 0, "currency": "USD"},
            "payments": {"recorded_paid_cents": revenue[0] or 0, "refunded_cents": revenue[1] or 0, "currency": "CAD",
                         "note": "Recorded invoice/top-up payments include tax when supplied by Stripe; this is not net revenue or profit."},
            "recent_billing_events": [{"id": row.id, "type": row.event_type, "outcome": row.outcome, "created_at": row.created_at}
                                      for row in db.scalars(select(StripeEvent).order_by(StripeEvent.created_at.desc()).limit(12))],
            "outbound_contact_notifications": "not_configured; requests remain available in the admin inbox"}


@router.get("/legal/{document}")
def legal(document: Literal["privacy", "terms"]):
    path = Path(__file__).resolve().parents[1] / "docs" / ("PRIVACY.md" if document == "privacy" else "TERMS.md")
    content = path.read_text(encoding="utf-8")
    replacements = {
        "{{LEGAL_BUSINESS_NAME}}": getattr(settings, "legal_business_name", "") or "[Owner must supply legal business name]",
        "{{LEGAL_BUSINESS_ADDRESS}}": getattr(settings, "legal_business_address", "") or "[Owner must supply business address]",
        "{{PRIVACY_EMAIL}}": getattr(settings, "privacy_email", "") or "[Owner must supply privacy contact email]",
        "{{GOVERNING_LAW}}": getattr(settings, "governing_law", "") or "[Owner and legal reviewer must confirm governing law]",
    }
    for token, value in replacements.items():
        content = content.replace(token, value)
    return {"version": settings.legal_version, "title": "Privacy Policy" if document == "privacy" else "Terms of Use",
            "content": content, "requires_legal_review": True}
