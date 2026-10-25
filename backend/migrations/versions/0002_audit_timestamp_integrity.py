"""Bind event identity and timestamp into new audit hashes; preserve historical v1 verification."""
from alembic import op
import sqlalchemy as sa
revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None
def upgrade():
    op.add_column('audit_events', sa.Column('hash_version', sa.Integer(), nullable=False, server_default='1'))
def downgrade():
    raise RuntimeError('Audit integrity migrations cannot be reversed automatically')
