from sqlalchemy import DateTime, ForeignKey, LargeBinary, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from .db import Base, uid, utcnow


class ProviderResult(Base):
    """Encrypted, short-lived replay result; never written into the usage ledger."""
    __tablename__ = 'provider_results'
    __table_args__ = (UniqueConstraint('workspace_id', 'operation_id'),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(ForeignKey('workspaces.id'), index=True)
    user_id: Mapped[str] = mapped_column(ForeignKey('users.id'))
    operation_id: Mapped[str] = mapped_column(String(160))
    request_hash: Mapped[str] = mapped_column(String(64))
    ciphertext: Mapped[bytes] = mapped_column(LargeBinary)
    created_at: Mapped[object] = mapped_column(DateTime(timezone=True), default=utcnow)
