"""Preserve custom report layout with each immutable document version."""
from alembic import op
import sqlalchemy as sa

revision = '0003'
down_revision = '0002'
branch_labels = None
depends_on = None

def upgrade():
    op.add_column('clinical_documents', sa.Column('custom_template', sa.JSON(), nullable=True))

def downgrade():
    raise RuntimeError('Document provenance cannot be removed automatically')
