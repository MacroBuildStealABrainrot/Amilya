"""Explicit scoped erasure while retaining normal immutable-table privileges."""
from alembic import op
revision='0006'
down_revision='0005'
branch_labels=None
depends_on=None

ERASURE_SQL="""
CREATE OR REPLACE FUNCTION public.wcp_erase_work(p_id text,p_workspace text,p_actor text,p_photo text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.encounters WHERE id=p_id AND workspace_id=p_workspace AND created_by=p_actor FOR UPDATE) THEN
   RAISE EXCEPTION 'Work unavailable';
 END IF;
 IF EXISTS(SELECT 1 FROM public.usage_operations WHERE encounter_id=p_id AND workspace_id=p_workspace AND status='reserved') THEN
   RAISE EXCEPTION 'Work is still processing';
 END IF;
 IF p_photo IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.clinical_evidence WHERE id=p_photo AND encounter_id=p_id AND workspace_id=p_workspace AND created_by=p_actor AND kind='photo') THEN
   RAISE EXCEPTION 'Photo unavailable';
 END IF;
 UPDATE public.report_transfers SET ciphertext=NULL,pin_hash=NULL,revoked_at=now() WHERE workspace_id=p_workspace AND created_by=p_actor AND ciphertext IS NOT NULL;
 DELETE FROM public.provider_results WHERE workspace_id=p_workspace AND operation_id IN(SELECT operation_id FROM public.usage_operations WHERE encounter_id=p_id AND workspace_id=p_workspace);
 DELETE FROM public.fact_revisions WHERE fact_id IN(SELECT id FROM public.clinical_facts WHERE encounter_id=p_id AND (p_photo IS NULL OR evidence_id=p_photo));
 DELETE FROM public.clinical_facts WHERE encounter_id=p_id AND (p_photo IS NULL OR evidence_id=p_photo);
 DELETE FROM public.clinical_reviews WHERE encounter_id=p_id;
 DELETE FROM public.clinical_evidence WHERE encounter_id=p_id AND (p_photo IS NULL OR id=p_photo);
 IF p_photo IS NULL THEN
   DELETE FROM public.document_signatures WHERE document_id IN(SELECT id FROM public.clinical_documents WHERE encounter_id=p_id);
   DELETE FROM public.clinical_documents WHERE encounter_id=p_id;
   DELETE FROM public.clinical_timeline WHERE encounter_id=p_id;
   DELETE FROM public.encounters WHERE id=p_id;
 END IF;
END $$;
REVOKE ALL ON FUNCTION public.wcp_erase_work(text,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.wcp_erase_work(text,text,text,text) TO wcp_app;
"""

def upgrade():
    if op.get_bind().dialect.name=='postgresql':op.execute(ERASURE_SQL)

def downgrade():
    if op.get_bind().dialect.name=='postgresql':op.execute('DROP FUNCTION public.wcp_erase_work(text,text,text,text)')
