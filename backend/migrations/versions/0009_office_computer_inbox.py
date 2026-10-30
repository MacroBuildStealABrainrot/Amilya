"""Office computer inbox: encrypted note copies that expire by the end of the clinician's day."""
from alembic import op
import sqlalchemy as sa

revision = '0009'
down_revision = '0008'
branch_labels = None
depends_on = None


def upgrade():
    op.create_table('desk_notes',
        sa.Column('id', sa.String(36), primary_key=True),
        sa.Column('workspace_id', sa.String(36), sa.ForeignKey('workspaces.id'), nullable=False),
        sa.Column('created_by', sa.String(36), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('encounter_id', sa.String(36), nullable=False),
        sa.Column('title', sa.String(80), nullable=False),
        sa.Column('ciphertext', sa.LargeBinary(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint('created_by', 'encounter_id', name='uq_desk_note_visit'))
    for name in ('workspace_id', 'created_by', 'encounter_id', 'expires_at'):
        op.create_index('ix_desk_notes_' + name, 'desk_notes', [name])


def downgrade():
    # Inbox copies are temporary by design, so removing the table loses nothing permanent.
    op.drop_table('desk_notes')
