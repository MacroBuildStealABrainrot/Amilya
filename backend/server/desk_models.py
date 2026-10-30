"""Office computer inbox: a short-lived encrypted copy of a finished note, erased once copied or at day's end."""
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, LargeBinary, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from server.db import Base, uid, utcnow


class DeskNote(Base):
    __tablename__ = 'desk_notes'
    # One inbox copy per visit: sending again replaces it with the newest wording.
    __table_args__ = (UniqueConstraint('created_by', 'encounter_id', name='uq_desk_note_visit'),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    workspace_id: Mapped[str] = mapped_column(ForeignKey('workspaces.id'), index=True)
    created_by: Mapped[str] = mapped_column(ForeignKey('users.id'), index=True)
    encounter_id: Mapped[str] = mapped_column(String(36), index=True)
    title: Mapped[str] = mapped_column(String(80))
    ciphertext: Mapped[bytes] = mapped_column(LargeBinary)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
