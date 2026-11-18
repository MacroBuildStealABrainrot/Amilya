#!/usr/bin/env python3
"""Zero-normal-traffic repair deployment. Never print service config or API bodies."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

PROJECT = 'woundcopilot-20260905'
REGION = 'us-central1'
SERVICE = 'woundcopilot'
NAME = f'projects/{PROJECT}/locations/{REGION}/services/{SERVICE}'
BASELINE = 'woundcopilot-00021-lrx'
REPAIR_URL = 'https://repair---woundcopilot-rhd7rrcqdq-uc.a.run.app'
PARENT = f'{REGION}-docker.pkg.dev/{PROJECT}/wcp/app@sha256:1b05b36af5db9f1a53426101faef035d6940f6dabc6897301e4dd7c67fee39e4'
PARENT_PROVIDER_SHA = 'd2ec7ac83872485f3f1a9b24dd60fb4dd85d1d5ef0e345171375d96d8718ceb7'
REVISION = 'TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION'


class Stop(RuntimeError):
    """Application-owned message; never include an upstream exception/body."""


def command(args, timeout=600):
    try:
        return subprocess.run(args, check=True, capture_output=True, text=True, timeout=timeout).stdout.strip()
    except (subprocess.SubprocessError, OSError):
        raise Stop('A deployment command failed; sensitive command output was suppressed.') from None


class Cloud:
    def call(self, method, path, body=None):
        if not path.startswith(('projects/' + PROJECT + '/locations/' + REGION + '/', NAME)):
            raise Stop('Unexpected cloud resource.')
        token = command(['gcloud', 'auth', 'print-access-token'], timeout=30)
        req = urllib.request.Request('https://run.googleapis.com/v2/' + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method=method)
        try:
            with urllib.request.urlopen(req, timeout=45) as response:
                return json.load(response)
        except (urllib.error.URLError, ValueError):
            raise Stop('Cloud API request failed; no response body was logged.') from None

    def get(self):
        return self.call('GET', NAME)

    def update(self, current, template, traffic):
        if not current.get('etag'):
            raise Stop('Cloud did not provide a concurrency token.')
        body = {'name': NAME, 'etag': current['etag'], 'template': template, 'traffic': traffic}
        op = self.call('PATCH', NAME + '?updateMask=template,traffic', body)
        deadline = time.monotonic() + 600
        while not op.get('done'):
            if time.monotonic() >= deadline:
                raise Stop('Cloud reconciliation exceeded its deadline.')
            time.sleep(2)
            op = self.call('GET', op['name'])
        if op.get('error'):
            raise Stop('Cloud rejected the candidate configuration.')
        result = self.get()
        if result.get('reconciling') or result.get('terminalCondition', {}).get('state') != 'CONDITION_SUCCEEDED':
            raise Stop('Cloud did not report a ready service.')
        return result


def template_key(value):
    result = copy.deepcopy(value)
    result.pop('revision', None)
    return result


def traffic_key(items):
    return sorted((x.get('type', REVISION), x.get('revision', '').split('/')[-1],
                   x.get('percent', 0), x.get('tag', '')) for x in items)


def assert_baseline(service):
    if service.get('name') != NAME or service.get('reconciling'):
        raise Stop('Service identity/state is not the reviewed baseline.')
    for key in ('traffic', 'trafficStatuses'):
        traffic = service.get(key, [])
        if not traffic or any(x.get('type') != REVISION for x in traffic):
            raise Stop('Latest-revision traffic is unsafe for this repair workflow.')
        normal = [x for x in traffic if x.get('percent', 0)]
        if len(normal) != 1 or normal[0].get('percent') != 100 or normal[0].get('revision', '').split('/')[-1] != BASELINE:
            raise Stop('Normal traffic is not 100 percent on the approved baseline.')
    containers = service.get('template', {}).get('containers', [])
    if len(containers) != 1:
        raise Stop('The reviewed repair expects exactly one container.')


def replace_tag(traffic, tag, revision):
    result = []
    for entry in traffic:
        item = copy.deepcopy(entry)
        if item.get('tag') == tag:
            item.pop('tag')
            if not item.get('percent', 0):
                continue
        result.append(item)
    if revision:
        result.append({'type': REVISION, 'revision': revision, 'tag': tag, 'percent': 0})
    return result


def tag_revision(service, tag):
    found = [x.get('revision') for x in service['traffic'] if x.get('tag') == tag]
    if len(found) != 1:
        raise Stop('Expected exactly one existing repair tag.')
    return found[0]


def candidate_template(original, image, revision, test_url):
    value = copy.deepcopy(original)
    value['revision'] = revision
    container = value['containers'][0]
    container['image'] = image
    env = [x for x in container.get('env', []) if x.get('name') not in {'PUBLIC_BASE_URL', 'TRUSTED_HOSTS'}]
    hosts = [REPAIR_URL.split('://')[1], test_url.split('://')[1], 'localhost', '127.0.0.1']
    container['env'] = env + [{'name':'PUBLIC_BASE_URL', 'value':REPAIR_URL},
                            {'name':'TRUSTED_HOSTS', 'value':','.join(hosts)}]
    return value


def save_state(path, state):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(state, stream)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def restore_template(cloud, state):
    current = cloud.get()
    assert_baseline(current)
    original = state['original_template']
    if template_key(current['template']) == template_key(original):
        return current
    if template_key(current['template']) != template_key(state['candidate_template']):
        raise Stop('Concurrent template change detected; refusing to overwrite it.')
    if traffic_key(current['traffic']) != traffic_key(state['candidate_traffic']):
        raise Stop('Concurrent routing change detected; refusing to overwrite it.')
    restored = copy.deepcopy(original)
    restored['revision'] = state['restore_revision']
    current = cloud.update(current, restored, current['traffic'])
    assert_baseline(current)
    if template_key(current['template']) != template_key(original):
        raise Stop('Template restoration could not be verified.')
    return current


def recover(cloud, state):
    current = restore_template(cloud, state)
    if not state.get('completed'):
        repair = tag_revision(current, 'repair')
        if repair == state['candidate_revision']:
            current = cloud.update(current, current['template'],
                replace_tag(current['traffic'], 'repair', state['old_repair']))
        elif repair != state['old_repair']:
            raise Stop('Concurrent repair-tag change detected; refusing to overwrite it.')
    assert_baseline(current)
    return current


def deploy(cloud, image, sha, run, state_path, smoke):
    if not re.fullmatch('[0-9a-f]{40}', sha) or not re.fullmatch('[0-9]+', run):
        raise Stop('Invalid workflow identity.')
    expected_prefix = f'{REGION}-docker.pkg.dev/{PROJECT}/wcp/app@sha256:'
    if not image.startswith(expected_prefix) or not re.fullmatch('[0-9a-f]{64}', image[len(expected_prefix):]):
        raise Stop('Only a pinned repair repository digest may be deployed.')
    original = cloud.get()
    assert_baseline(original)
    old_repair = tag_revision(original, 'repair')
    statuses = [x for x in original['trafficStatuses'] if x.get('tag') == 'repair']
    if len(statuses) != 1 or statuses[0].get('uri') != REPAIR_URL:
        raise Stop('Repair URL differs from the APK endpoint.')
    # Stop on inherited test settings rather than silently changing them.
    env = {x['name']: x for x in original['template']['containers'][0].get('env', [])}
    if any(key in env for key in ('PUBLIC_BASE_URL', 'TRUSTED_HOSTS')):
        raise Stop('Template has address overrides; baseline restoration needs review first.')
    revision = f'{SERVICE}-ci-{sha[:8]}-{run}'
    test_tag = f'ci-{run}'
    test_url = REPAIR_URL.replace('repair---', test_tag + '---', 1)
    candidate = candidate_template(original['template'], image, revision, test_url)
    traffic = replace_tag(original['traffic'], test_tag, revision)
    state = {'original_template': original['template'], 'candidate_template': candidate,
             'candidate_traffic': traffic, 'candidate_revision': revision,
             'restore_revision': f'{SERVICE}-restore-{run}', 'old_repair':old_repair,
             'completed':False}
    save_state(state_path, state)  # Persist BEFORE any mutation; never upload this private file.
    try:
        current = cloud.update(original, candidate, traffic)
        assert_baseline(current)
        current = restore_template(cloud, state)  # Restore before lengthy feature checks.
        smoke(test_url, full=True)
        current = cloud.get()
        assert_baseline(current)
        if template_key(current['template']) != template_key(original['template']) or tag_revision(current, 'repair') != old_repair:
            raise Stop('Service changed during feature checks.')
        current = cloud.update(current, current['template'], replace_tag(current['traffic'], 'repair', revision))
        assert_baseline(current)
        smoke(REPAIR_URL, full=False)
        if tag_revision(current, 'repair') != revision:
            raise Stop('Repair tag promotion could not be verified.')
        state['completed'] = True
        save_state(state_path, state)
        return {'status':'repair_updated', 'commit':sha, 'revision':revision,
                'normal_traffic_unchanged':True, 'template_restored':True,
                'feature_checks':'passed'}
    finally:
        recover(cloud, state)


def build(sha, root):
    folder = root / 'repairs/live-00021-lrx'
    provider = folder / 'providers.py'
    if provider.read_bytes() != (root / 'Amilya/repairs/live-00021-lrx/providers.py').read_bytes():
        raise Stop('Repair source copies differ.')
    if (folder / 'Dockerfile').read_text() != f'FROM {PARENT}\nCOPY providers.py /app/server/providers.py\n':
        raise Stop('Repair Dockerfile differs from the pinned delivery contract.')
    command(['gcloud','auth','configure-docker',f'{REGION}-docker.pkg.dev','--quiet'])
    command(['docker','pull',PARENT])
    probe = "import hashlib;print(hashlib.sha256(open('/app/server/providers.py','rb').read()).hexdigest())"
    if command(['docker','run','--rm','--network','none','--entrypoint','python',PARENT,'-c',probe]) != PARENT_PROVIDER_SHA:
        raise Stop('Parent provider checksum mismatch.')
    tag = f'{REGION}-docker.pkg.dev/{PROJECT}/wcp/app:ci-{sha}'
    command(['docker','build','--pull','-t',tag,str(folder)])
    digest = hashlib.sha256(provider.read_bytes()).hexdigest()
    if command(['docker','run','--rm','--network','none','--entrypoint','python',tag,'-c',probe]) != digest:
        raise Stop('Built provider checksum mismatch.')
    command(['docker','run','--rm','--network','none','--entrypoint','python',tag,'-c',
             "compile(open('/app/server/providers.py').read(), 'providers.py', 'exec')"])
    command(['docker','push',tag])
    result = command(['docker','inspect','--format','{{json .RepoDigests}}',tag])
    digests = json.loads(result)
    if len(digests) != 1:
        raise Stop('Could not resolve the pushed immutable image.')
    return digests[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--recover', action='store_true')
    args = parser.parse_args()
    cloud = Cloud()
    try:
        if args.recover:
            if args.state.exists():
                recover(cloud, json.loads(args.state.read_text()))
            return
        sha = os.environ['GITHUB_SHA']
        if not re.fullmatch('[0-9a-f]{40}', sha):
            raise Stop('Invalid commit.')
        # Validate service before consuming build resources.
        assert_baseline(cloud.get())
        image = build(sha, Path(__file__).resolve().parents[1])
        from repair_smoke import run as smoke
        report = deploy(cloud, image, sha, os.environ['GITHUB_RUN_ID'] + os.environ.get('GITHUB_RUN_ATTEMPT','1'), args.state, smoke)
        print(json.dumps(report))
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write('\nRepair server updated; synthetic checks passed. Normal traffic unchanged; template restored.\n')
    except (Stop, KeyError, ValueError) as error:
        print('::error::' + (str(error) if isinstance(error, Stop) else 'Deployment configuration is incomplete.'))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
