"""Renewable native sessions; no passwords or plaintext tokens in this table."""
from alembic import op
import sqlalchemy as sa
revision='0005'
down_revision='0004'
branch_labels=None
depends_on=None

def upgrade():
    op.create_table('mobile_sessions',
        sa.Column('id',sa.String(64),primary_key=True),
        sa.Column('access_id',sa.String(64),nullable=False,index=True),
        sa.Column('user_id',sa.String(36),sa.ForeignKey('users.id'),nullable=False,index=True),
        sa.Column('password_version',sa.String(64),nullable=False),
        sa.Column('expires_at',sa.DateTime(timezone=True),nullable=False))

def downgrade():
    op.drop_table('mobile_sessions')
