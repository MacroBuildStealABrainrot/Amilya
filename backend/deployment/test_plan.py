import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location('wcp_deployment_plan', Path(__file__).with_name('render_plan.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_free_tier_plan_is_unapplied_and_avoids_fixed_paid_network_resources():
    plan = module.make_plan()
    assert plan['automatic_apply_supported'] is False
    assert plan['zero_cost_guaranteed'] is False
    assert plan['disk_total_gb'] == 30
    commands = [step['args'] for step in plan['steps']]
    vm = next(args for args in commands if args[:3] == ['compute', 'instances', 'create'])
    assert '--no-address' in vm and '--machine-type=e2-micro' in vm
    assert '--boot-disk-type=pd-standard' in vm
    run = next(args for args in commands if args[:2] == ['run', 'deploy'])
    assert '--min=0' in run and '--max=1' in run and '--vpc-egress=private-ranges-only' in run
    assert '--no-allow-unauthenticated' in run
    assert not any(args[:2] in [['sql', 'instances'], ['compute', 'routers'], ['builds', 'submit']] for args in commands)
    assert all('owner_review_and_billing_authorization' in step['requires'] for step in plan['steps'])


@pytest.mark.parametrize('kwargs', [
    {'region':'northamerica-northeast2','zone':'northamerica-northeast2-a'},
    {'zone':'us-west1-a'}, {'boot_gb':20,'data_gb':20}, {'boot_gb':9},
    {'app_image':'gcr.io/example/app:latest'},
    {'postgres_image':'us-central1-docker.pkg.dev/another-project/wcp/postgres@sha256:' + 'a'*64},
])
def test_invalid_or_potentially_paid_changes_fail_before_render(kwargs):
    with pytest.raises(ValueError): module.make_plan(**kwargs)


def test_same_project_digest_is_accepted_without_sdk_or_network():
    digest = 'us-central1-docker.pkg.dev/woundcopilot-20260905/wcp/postgres@sha256:' + 'a'*64
    assert module.make_plan(postgres_image=digest)['postgres_image'] == digest
