import copy
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

CI = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CI))
import repair_deploy as deploy
from repair_connection import readiness


def service():
    traffic=[{'type':deploy.REVISION,'revision':deploy.BASELINE,'percent':100},
             {'type':deploy.REVISION,'revision':'woundcopilot-photo-ce63ac2','percent':0,'tag':'repair'},
             {'type':deploy.REVISION,'revision':'woundcopilot-00022-yer','percent':0,'tag':'srv1'}]
    return {'name':deploy.NAME,'etag':'1','reconciling':False,
        'template':{'revision':'woundcopilot-00029-dtk','serviceAccount':'runtime@example.test',
            'containers':[{'image':'original-image','env':[{'name':'KEEP_SECRET','valueSource':{'secretKeyRef':{'secret':'existing-secret','version':'2'}}}],
                'resources':{'limits':{'cpu':'1','memory':'512Mi'}}}],
            'volumes':[{'name':'secret','secret':{'secret':'existing-secret'}}],
            'vpcAccess':{'connector':'existing-connector','egress':'ALL_TRAFFIC'}},
        'traffic':traffic,'trafficStatuses':[{**x,**({'uri':deploy.REPAIR_URL} if x.get('tag')=='repair' else {})} for x in traffic]}


class FakeCloud:
    def __init__(self):
        self.value=service()
        self.updates=[]

    def get(self): return copy.deepcopy(self.value)

    def update(self,current,template,traffic):
        if current['etag']!=self.value['etag']: raise deploy.Stop('Conflict')
        self.value['template']=copy.deepcopy(template)
        self.value['traffic']=copy.deepcopy(traffic)
        self.value['trafficStatuses']=copy.deepcopy(traffic)
        self.value['etag']=str(int(self.value['etag'])+1)
        self.updates.append(self.get())
        deploy.assert_baseline(self.value)
        return self.get()


class RepairDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.folder=tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.state=Path(self.folder.name)/'private-state.json'
        self.cloud=FakeCloud()
        self.original=self.cloud.get()
        self.image=f'{deploy.REGION}-docker.pkg.dev/{deploy.PROJECT}/wcp/app@sha256:'+'a'*64
        self.sha='b'*40

    def execute(self,smoke):
        return deploy.deploy(self.cloud,self.image,self.sha,'1231',self.state,smoke)

    def test_success_restores_template_and_moves_only_repair_tag(self):
        calls=[]
        def smoke(url,full):
            self.assertEqual(deploy.template_key(self.cloud.value['template']),deploy.template_key(self.original['template']))
            calls.append((url,full))
        report=self.execute(smoke)
        self.assertEqual(report['status'],'repair_updated')
        self.assertEqual(calls,[(deploy.REPAIR_URL.replace('repair---','ci-1231---'),True),(deploy.REPAIR_URL,False)])
        self.assertEqual(deploy.tag_revision(self.cloud.value,'repair'),report['revision'])
        self.assertEqual(deploy.tag_revision(self.cloud.value,'srv1'),'woundcopilot-00022-yer')
        for state in self.cloud.updates: deploy.assert_baseline(state)
        self.assertEqual(self.state.stat().st_mode & 0o777,0o600)

    def test_failure_before_promotion_keeps_previous_repair(self):
        def fail(*a,**k): raise deploy.Stop('Synthetic failure')
        with self.assertRaises(deploy.Stop): self.execute(fail)
        self.assertEqual(deploy.tag_revision(self.cloud.value,'repair'),deploy.tag_revision(self.original,'repair'))
        self.assertEqual(deploy.template_key(self.cloud.value['template']),deploy.template_key(self.original['template']))

    def test_failure_after_promotion_automatically_rolls_back_test_tag(self):
        def smoke(url,full):
            if not full: raise deploy.Stop('Post-promotion health failed')
        with self.assertRaises(deploy.Stop): self.execute(smoke)
        self.assertEqual(deploy.tag_revision(self.cloud.value,'repair'),deploy.tag_revision(self.original,'repair'))

    def test_interrupted_mutation_has_recoverable_state(self):
        update=self.cloud.update
        count=0
        def interrupt(*args):
            nonlocal count
            count+=1
            result=update(*args)
            if count==1:
                self.assertTrue(self.state.exists())
                raise deploy.Stop('Connection lost after apply')
            return result
        self.cloud.update=interrupt
        with self.assertRaises(deploy.Stop): self.execute(lambda *a,**k:None)
        self.assertEqual(deploy.template_key(self.cloud.value['template']),deploy.template_key(self.original['template']))

    def test_external_template_change_is_never_overwritten(self):
        def changed(*a,**k): self.cloud.value['template']['timeout']='900s'
        with self.assertRaises(deploy.Stop): self.execute(changed)
        self.assertEqual(self.cloud.value['template']['timeout'],'900s')
        self.assertEqual(deploy.tag_revision(self.cloud.value,'repair'),deploy.tag_revision(self.original,'repair'))

    def test_changed_normal_traffic_blocks_all_mutations(self):
        self.cloud.value['traffic'][0]['revision']='different-live-revision'
        with self.assertRaises(deploy.Stop): self.execute(lambda *a,**k:None)
        self.assertEqual(self.cloud.updates,[])

    def test_latest_routing_is_rejected(self):
        self.cloud.value['traffic'][0]['type']='TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST'
        with self.assertRaises(deploy.Stop): self.execute(lambda *a,**k:None)
        self.assertFalse(self.state.exists())

    def test_inherited_test_environment_blocks_before_mutation(self):
        self.cloud.value['template']['containers'][0]['env'].append({'name':'PUBLIC_BASE_URL','value':deploy.REPAIR_URL})
        with self.assertRaises(deploy.Stop): self.execute(lambda *a,**k:None)
        self.assertEqual(self.cloud.updates,[])

    def test_unpinned_image_is_rejected(self):
        self.image='untrusted.example/app:latest'
        with self.assertRaises(deploy.Stop): self.execute(lambda *a,**k:None)

    def test_candidate_changes_only_image_addresses_and_revision(self):
        result=deploy.candidate_template(self.original['template'],self.image,'candidate','https://ci-1---woundcopilot-rhd7rrcqdq-uc.a.run.app')
        self.assertEqual(self.original,service())
        for name in ['serviceAccount','volumes','vpcAccess']: self.assertEqual(result[name],self.original['template'][name])
        self.assertEqual(result['containers'][0]['resources'],self.original['template']['containers'][0]['resources'])
        self.assertEqual(result['containers'][0]['env'][0],self.original['template']['containers'][0]['env'][0])

    def test_replacing_tag_does_not_drop_normal_traffic(self):
        traffic=[{'type':deploy.REVISION,'revision':deploy.BASELINE,'percent':100,'tag':'repair'}]
        result=deploy.replace_tag(traffic,'repair','candidate')
        self.assertEqual(result[0],{'type':deploy.REVISION,'revision':deploy.BASELINE,'percent':100})
        self.assertEqual(result[1]['percent'],0)


class ConnectionTests(unittest.TestCase):
    def test_missing_connection_is_explicitly_blocked(self):
        result=readiness({})
        self.assertFalse(result['ready'])
        self.assertEqual(len(result['missing']),6)

    def test_only_exact_deploy_identity_and_synthetic_account_allowed(self):
        env={'AMILYA_GCP_WIF_PROVIDER':'projects/123/locations/global/workloadIdentityPools/amilya-repair/providers/github',
            'AMILYA_GCP_DEPLOY_ACCOUNT':'amilya-repair-ci@woundcopilot-20260905.iam.gserviceaccount.com',
            'AMILYA_SMOKE_WORKSPACE_ID':'synthetic-workspace','AMILYA_SMOKE_EMAIL':'smoke@example.test',
            'AMILYA_SMOKE_PASSWORD':'synthetic-password','AMILYA_REPAIR_DEPLOY_ENABLED':'true'}
        self.assertTrue(readiness(env)['ready'])
        for key,value in [('AMILYA_GCP_DEPLOY_ACCOUNT','admin@example.com'),('AMILYA_SMOKE_EMAIL','real@example.com'),('AMILYA_REPAIR_DEPLOY_ENABLED','false')]:
            changed={**env,key:value}
            self.assertFalse(readiness(changed)['ready'])
            self.assertNotIn(value,str(readiness(changed)))


class FeatureGateTests(unittest.TestCase):
    def response(self,path,body,code=200):
        import httpx
        return httpx.Response(code,json=body,request=httpx.Request('POST','https://test.invalid'+path))

    def run_features(self,report=None,photo=None,user=None,legal=False):
        import httpx
        import repair_smoke
        calls=[]
        def handle(request):
            path=request.url.path
            calls.append(path)
            if path.endswith('/login'): body={'user':user or {'workspace_id':'synthetic-workspace','is_platform_admin':False},'csrf_token':'synthetic-token'}
            elif path.endswith('/session'): body={'requires_legal_acceptance':legal}
            elif path.endswith('/quick/start'): body={'id':'11111111-1111-4111-8111-111111111111'}
            elif path.endswith('/quick-report'): body=report if report is not None else {'text':'Synthetic report','qa':{'passed':True}}
            elif path.endswith('/upload'):
                if b'synthetic-shapes.jpg' in request.content:
                    body=photo if photo is not None else {'facts':[],'photo_quality':{'status':'insufficient','retake_required':True}}
                else: body={'evidence':{'text':'Synthetic transcript'},'facts':[{'name':'pain'}]}
            elif path.endswith('/logout'): body={}
            else: raise AssertionError('Unexpected endpoint')
            return self.response(path,body)
        with tempfile.TemporaryDirectory() as folder:
            audio=Path(folder)/'synthetic.wav'
            audio.write_bytes(b'not-patient-audio')
            with patch.dict(os.environ,{'AMILYA_SMOKE_AUDIO':str(audio)}):
                with httpx.Client(base_url='https://test.invalid',transport=httpx.MockTransport(handle)) as client:
                    repair_smoke.features(client,'smoke@example.test','synthetic-password','synthetic-workspace')
        return calls

    def test_all_feature_gates_and_logout(self):
        calls=self.run_features()
        self.assertEqual(calls.count('/api/v1/quick/start'),2)
        self.assertTrue(calls[-1].endswith('/logout'))

    def test_source_only_report_is_failure(self):
        with self.assertRaises(deploy.Stop): self.run_features(report={'text':'Fallback','qa':{'passed':False},'recovery':{'mode':'source_only'}})

    def test_invented_photo_findings_fail(self):
        with self.assertRaises(deploy.Stop): self.run_features(photo={'facts':[{'name':'periwound'}],'photo_quality':{'status':'sufficient','retake_required':False}})

    def test_admin_account_is_rejected(self):
        with self.assertRaises(deploy.Stop): self.run_features(user={'workspace_id':'synthetic-workspace','is_platform_admin':True})

    def test_legal_terms_are_never_accepted_by_automation(self):
        with self.assertRaises(deploy.Stop): self.run_features(legal=True)

    def test_arbitrary_error_header_never_leaks(self):
        import repair_smoke
        response=self.response('/photo',{'detail':'PRIVATE TEXT'},502)
        response.headers['X-WCP-Provider-Error']='PatientJohnSmith'
        with self.assertRaises(deploy.Stop) as caught: repair_smoke.checked(response)
        self.assertNotIn('PatientJohnSmith',str(caught.exception))
        self.assertNotIn('PRIVATE',str(caught.exception))


if __name__=='__main__': unittest.main()
