"""Add encrypted, expiring one-time report transfers."""
from alembic import op
import sqlalchemy as sa

revision = '0004'
down_revision = '0003'
branch_labels = None
depends_on = None


def upgrade():
    op.create_table('report_transfers',
        sa.Column('id', sa.String(36), primary_key=True),
        sa.Column('workspace_id', sa.String(36), sa.ForeignKey('workspaces.id'), nullable=False),
        sa.Column('created_by', sa.String(36), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('token_hash', sa.String(64), nullable=False, unique=True),
        sa.Column('pin_hash', sa.String(64), nullable=True),
        sa.Column('ciphertext', sa.LargeBinary(), nullable=True),
        sa.Column('document_count', sa.Integer(), nullable=False),
        sa.Column('attempts', sa.Integer(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('consumed_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('revoked_at', sa.DateTime(timezone=True), nullable=True))
    for name in ('workspace_id', 'created_by', 'expires_at'):
        op.create_index('ix_report_transfers_' + name, 'report_transfers', [name])


def downgrade():
    raise RuntimeError('Report transfer history cannot be removed automatically')
