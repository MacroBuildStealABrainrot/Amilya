"""Clinical persistence: immutable source, revision, document and signature records."""
from __future__ import annotations

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint, event
from sqlalchemy.orm import Mapped, mapped_column

from server.db import Base, utcnow, uid


class Patient(Base):
    __tablename__ = 'patients'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    display_name: Mapped[str] = mapped_column(String(200))
    external_ref: Mapped[str | None] = mapped_column(String(200), nullable=True)
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


class Wound(Base):
    __tablename__ = 'wounds'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    patient_id: Mapped[str] = mapped_column(ForeignKey('patients.id'), index=True)
    label: Mapped[str] = mapped_column(String(200))
    location: Mapped[str | None] = mapped_column(String(200), nullable=True)
    laterality: Mapped[str | None] = mapped_column(String(32), nullable=True)
    wound_type: Mapped[str | None] = mapped_column(String(200), nullable=True)
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


class Encounter(Base):
    __tablename__ = 'encounters'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    patient_id: Mapped[str] = mapped_column(ForeignKey('patients.id'), index=True)
    wound_id: Mapped[str | None] = mapped_column(ForeignKey('wounds.id'), nullable=True)
    title: Mapped[str] = mapped_column(String(200))
    status: Mapped[str] = mapped_column(String(24), default='open')
    revision: Mapped[int] = mapped_column(Integer, default=1)
    draft_data: Mapped[dict] = mapped_column(JSON, default=dict)
    created_by: Mapped[str] = mapped_column(String(36))
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class Evidence(Base):
    __tablename__ = 'clinical_evidence'
    __table_args__ = (UniqueConstraint('workspace_id', 'encounter_id', 'request_key', name='uq_evidence_request'),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    encounter_id: Mapped[str] = mapped_column(ForeignKey('encounters.id'), index=True)
    wound_id: Mapped[str | None] = mapped_column(ForeignKey('wounds.id'), nullable=True)
    kind: Mapped[str] = mapped_column(String(24))
    text: Mapped[str | None] = mapped_column(Text, nullable=True)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict)
    warnings: Mapped[list] = mapped_column(JSON, default=list)
    created_by: Mapped[str] = mapped_column(String(36))
    request_key: Mapped[str | None] = mapped_column(String(128), nullable=True)
    request_hash: Mapped[str] = mapped_column(String(64))
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


class ClinicalFact(Base):
    __tablename__ = 'clinical_facts'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    encounter_id: Mapped[str] = mapped_column(ForeignKey('encounters.id'), index=True)
    evidence_id: Mapped[str] = mapped_column(ForeignKey('clinical_evidence.id'), index=True)
    wound_id: Mapped[str | None] = mapped_column(ForeignKey('wounds.id'), nullable=True)
    name: Mapped[str] = mapped_column(String(64))
    current_revision: Mapped[int] = mapped_column(Integer, default=1)
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


class FactRevision(Base):
    __tablename__ = 'fact_revisions'
    __table_args__ = (UniqueConstraint('fact_id', 'revision', name='uq_fact_revision'),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    fact_id: Mapped[str] = mapped_column(ForeignKey('clinical_facts.id'), index=True)
    revision: Mapped[int] = mapped_column(Integer)
    value: Mapped[dict] = mapped_column(JSON)
    confirmation_state: Mapped[str] = mapped_column(String(24))
    source_type: Mapped[str] = mapped_column(String(40))
    source_ref: Mapped[str] = mapped_column(String(36))
    source_quote: Mapped[str | None] = mapped_column(Text, nullable=True)
    source_locator: Mapped[dict] = mapped_column(JSON, default=dict)
    confirmed_by: Mapped[str | None] = mapped_column(String(36), nullable=True)
    confirmed_at = mapped_column(DateTime(timezone=True), nullable=True)
    authored_by: Mapped[str] = mapped_column(String(36))
    action: Mapped[str] = mapped_column(String(24))
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


class ClinicalReview(Base):
    __tablename__ = 'clinical_reviews'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    encounter_id: Mapped[str] = mapped_column(ForeignKey('encounters.id'), index=True)
    case_revision: Mapped[int] = mapped_column(Integer)
    result: Mapped[dict] = mapped_column(JSON)
    created_by: Mapped[str] = mapped_column(String(36))
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


class ClinicalDocument(Base):
    __tablename__ = 'clinical_documents'
    __table_args__ = (
        UniqueConstraint('encounter_id', 'version', name='uq_document_version'),
        UniqueConstraint('workspace_id', 'encounter_id', 'request_key', name='uq_document_request'),
    )
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    encounter_id: Mapped[str] = mapped_column(ForeignKey('encounters.id'), index=True)
    text: Mapped[str] = mapped_column(Text)
    template: Mapped[str] = mapped_column(String(24))
    custom_template: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    content_hash: Mapped[str] = mapped_column(String(64))
    version: Mapped[int] = mapped_column(Integer)
    case_revision: Mapped[int] = mapped_column(Integer)
    fact_snapshot: Mapped[list] = mapped_column(JSON)
    amends_id: Mapped[str | None] = mapped_column(ForeignKey('clinical_documents.id'), nullable=True)
    amendment_reason: Mapped[str | None] = mapped_column(Text, nullable=True)
    request_key: Mapped[str | None] = mapped_column(String(128), nullable=True)
    request_hash: Mapped[str] = mapped_column(String(64))
    created_by: Mapped[str] = mapped_column(String(36))
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


class DocumentSignature(Base):
    __tablename__ = 'document_signatures'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    document_id: Mapped[str] = mapped_column(ForeignKey('clinical_documents.id'), unique=True)
    content_hash: Mapped[str] = mapped_column(String(64))
    signed_case_revision: Mapped[int] = mapped_column(Integer)
    signed_by: Mapped[str] = mapped_column(String(36))
    signed_at = mapped_column(DateTime(timezone=True), default=utcnow)


class TimelineEvent(Base):
    __tablename__ = 'clinical_timeline'
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(String(36), index=True)
    encounter_id: Mapped[str] = mapped_column(ForeignKey('encounters.id'), index=True)
    user_id: Mapped[str] = mapped_column(String(36))
    action: Mapped[str] = mapped_column(String(64))
    resource_id: Mapped[str] = mapped_column(String(36))
    detail: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at = mapped_column(DateTime(timezone=True), default=utcnow)


def _immutable(mapper, connection, target):
    raise ValueError(f'{type(target).__name__} records are immutable; append a new revision')


for _model in (Evidence, FactRevision, ClinicalDocument, DocumentSignature, ClinicalReview, TimelineEvent):
    event.listen(_model, 'before_update', _immutable)
    event.listen(_model, 'before_delete', _immutable)
