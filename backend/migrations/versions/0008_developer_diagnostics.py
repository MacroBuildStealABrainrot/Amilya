"""Add technical-only request and event diagnostics."""
from alembic import op
revision='0008'
down_revision='0007'
branch_labels=None
depends_on=None
def upgrade():
    from server.diagnostics import DiagnosticRequest,DiagnosticEvent
    for table in (DiagnosticRequest.__table__,DiagnosticEvent.__table__):table.create(op.get_bind(),checkfirst=True)
def downgrade():
    raise RuntimeError('Diagnostic history must be retired explicitly, not silently discarded.')
