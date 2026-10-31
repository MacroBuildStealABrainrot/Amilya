"""Only revoke transfers containing the deleted reports."""
import importlib.util
from pathlib import Path
from alembic import op
revision='0007'
down_revision='0006'
branch_labels=None
depends_on=None

def scoped_sql():
    spec=importlib.util.spec_from_file_location('privacy_0006',Path(__file__).with_name('0006_scoped_privacy_erasure.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module.erasure_sql().replace('AND created_by=p_actor AND ciphertext IS NOT NULL;',"AND created_by=p_actor AND ciphertext IS NOT NULL AND id=ANY(string_to_array(current_setting('wcp.erasure_transfers',true),','));")

def upgrade():
    if op.get_bind().dialect.name=='postgresql':op.execute(scoped_sql())

def downgrade():
    raise RuntimeError('Broad transfer revocation must not be restored.')

