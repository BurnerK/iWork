"""Disposable issue19 acceptance: real business cancellation and Docker stop evidence.
Requires the test-only CancellationFixtureApplication and the 50-second parser fixture.
Never use against production. Password comes from IWORK_ACCEPTANCE_PASSWORD.
"""
import argparse
import http.cookiejar
import json
import os
import pathlib
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('url')
parser.add_argument('model')
parser.add_argument('base_version')
parser.add_argument('compose_file')
parser.add_argument('env_file')
parser.add_argument('evidence')
parser.add_argument('--pause-for-browser', action='store_true')
args = parser.parse_args()
assert urllib.parse.urlsplit(args.url).hostname in ('127.0.0.1', 'localhost')
compose = ['docker', 'compose', '--env-file', args.env_file, '-f', args.compose_file]
assert json.loads(subprocess.check_output(compose + ['config', '--format', 'json']))['name'] == 'iwork-issue19-check'
manager = subprocess.check_output(compose + ['ps', '-q', 'sandbox-manager'], text=True).strip()
assert manager
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = ''

def request(method, path, body=None, headers=None):
    extra = dict(headers or {})
    if csrf:
        extra['X-CSRF-TOKEN'] = csrf
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    extra.setdefault('Content-Type', 'application/json')
    with client.open(urllib.request.Request(args.url + path, data=data, method=method, headers=extra), timeout=20) as response:
        raw = response.read()
        if 'json' not in response.headers.get('Content-Type', ''):
            return raw
        payload = json.loads(raw)
        if path.startswith('/api/'):
            assert payload['success'] is True and payload['error'] is None
            return payload['data']
        return payload

def until(check, timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.1)
    raise AssertionError('Timed out waiting for observable state')

def containers(job):
    return subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=iwork.job=' + job], text=True).split()

csrf = request('GET', '/api/auth/csrf')['token']
request('POST', '/api/auth/login', urllib.parse.urlencode({'username': 'admin', 'password': os.environ['IWORK_ACCEPTANCE_PASSWORD']}).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
csrf = request('GET', '/api/auth/csrf')['token']
original = request('GET', '/api/document-versions/' + args.base_version + '/content')
conversation = request('POST', '/api/conversations', {'title': '取消与清理状态验收'})['id']
material = request('POST', '/api/materials', b'# Cancellation fixture\nSlow processing source.', {'Content-Type': 'application/octet-stream', 'X-File-Name': 'cancel-source.md'})['id']
print(json.dumps({'conversation': conversation, 'material': material}), flush=True)
results = []
paused = False
try:
    for unavailable in (False, True):
        task = request('POST', '/api/conversations/' + conversation + '/tasks', {'requestId': str(uuid.uuid4()), 'modelId': args.model, 'message': '整理资料并修改选定版本', 'outputMode': 'DOCUMENT', 'baseVersionId': args.base_version, 'materialIds': [material]})['id']
        parse = until(lambda: next(iter(request('GET', '/api/tasks/' + task + '/parses')), None))
        live = until(lambda: containers(parse['id']))
        info = json.loads(subprocess.check_output(['docker', 'inspect', live[0]]))[0]
        assert info['State']['Running'] and info['Config']['Labels']['iwork.sandbox'] == 'issue19'
        if unavailable:
            subprocess.run(['docker', 'pause', manager], check=True, stdout=subprocess.DEVNULL)
            paused = True
        request('POST', '/api/tasks/' + task + '/cancel', {})
        ended = until(lambda: (state if (state := request('GET', '/api/tasks/' + task))['executionState'] == 'STOPPED' else None), 40)
        assert ended['status'] == 'CANCELLED', ended
        observation = request('GET', '/api/tasks/' + task + '/parses')[0]
        if unavailable:
            assert observation['cleanup'] == 'UNKNOWN', observation
            still_running = json.loads(subprocess.check_output(['docker', 'inspect', live[0]]))[0]['State']['Running']
            assert still_running, 'The unknown-state fixture must still be running at this observation'
            print(json.dumps({'phase': 'UNKNOWN_READY', 'conversation': conversation, 'task': task, 'parse': observation, 'containerRunning': still_running}), flush=True)
            if args.pause_for_browser:
                input('Capture the unknown state, then press Enter to restore the test manager: ')
            subprocess.run(['docker', 'unpause', manager], check=True, stdout=subprocess.DEVNULL)
            paused = False
        final = until(lambda: (state if (state := request('GET', '/api/tasks/' + task + '/parses')[0])['cleanup'] == 'CONFIRMED' else None), 40)
        assert not containers(parse['id']), 'Docker still reports the job container'
        page = 0
        while True:
            versions = request('GET', f'/api/conversations/{conversation}/documents?page={page}')
            assert all(version['taskId'] != task for version in versions['items'])
            if not versions['hasMore']:
                break
            page += 1
        assert request('GET', '/api/document-versions/' + args.base_version + '/content') == original
        calls = request('GET', '/api/tasks/' + task + '/calls')
        assert calls and all(call['status'] == 'CLOSED' for call in calls)
        assert request('GET', '/api/budget')['reserved'] == '0.000000'
        results.append({'task': ended, 'unknownObservation': observation, 'finalParse': final, 'calls': calls, 'versions': versions})
finally:
    if paused:
        subprocess.run(['docker', 'unpause', manager], check=True, stdout=subprocess.DEVNULL)
pathlib.Path(args.evidence).write_text(json.dumps({'conversation': conversation, 'results': results}, ensure_ascii=False, indent=2))
print('Business cancellation, unknown cleanup, Docker absence and immutable source verified.', flush=True)
