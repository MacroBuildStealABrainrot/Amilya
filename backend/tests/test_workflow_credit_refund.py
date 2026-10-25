from sqlalchemy import select
from server import billing
from server.billing_models import BillingAccount,UsageOperation,CreditLedger
from test_billing import commerce,fund

def test_failed_document_refunds_once_and_preserves_vendor_cost(commerce):
    env=commerce;fund(env,10000)
    with env.db() as db:
        billing.reserve(db,env.user,'draft-stage',1000,task='quick_draft')
        billing.settle(db,env.user,'draft-stage',750,'synthetic','synthetic',{},actual_cost_micros=725)
    with env.db() as db:assert billing.refund_failed_document(db,env.user,['draft-stage'])==750
    with env.db() as db:assert billing.refund_failed_document(db,env.user,['draft-stage'])==0
    with env.db() as db:
        account=db.get(BillingAccount,env.user.workspace_id)
        assert account.plan_micros+account.topup_micros==10000
        operation=db.scalar(select(UsageOperation))
        assert operation.charged_micros==0 and operation.actual_cost_micros==725
        assert operation.usage['waive_reason']=='document_failed'
        assert len(list(db.scalars(select(CreditLedger).where(CreditLedger.kind=='workflow_refund'))))==1

def test_refund_cannot_target_another_tenant(commerce):
    env=commerce;fund(env,10000)
    with env.db() as db:
        billing.reserve(db,env.user,'owned',1000)
        billing.settle(db,env.user,'owned',1000,'synthetic','synthetic',{})
    with env.db() as db:assert billing.refund_failed_document(db,env.other,['owned'])==0
