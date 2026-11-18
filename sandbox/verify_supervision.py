"""Issue21 real CLI failure and watchdog acceptance; dedicated local test stack only."""
import argparse
import datetime
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

p = argparse.ArgumentParser()
for name in ('launcher', 'model', 'base_version', 'compose_file', 'env_file', 'directory'):
    p.add_argument(name)
a = p.parse_args()
root = pathlib.Path(a.directory)
faults = root / 'faults'
assert faults.is_dir()
compose = ['docker', 'compose', '--env-file', a.env_file, '-f', a.compose_file]
assert json.loads(subprocess.check_output(compose + ['config', '--format', 'json']))['name'] == 'iwork-issue21-check'

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()

manager = subprocess.check_output(compose + ['ps', '-q', 'sandbox-manager'], text=True).strip()
watchdog = subprocess.check_output(compose + ['ps', '-q', 'sandbox-watchdog'], text=True).strip()
assert manager and watchdog
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = ''
app = None
generation = 0
results = []

def request(method, path, body=None, headers=None):
    extra = {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf, **(headers or {})}
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    with client.open(urllib.request.Request('http://127.0.0.1:18092' + path, data=data, method=method, headers=extra), timeout=15) as response:
        raw = response.read()
        if 'json' not in response.headers.get('Content-Type', ''):
            return raw
        payload = json.loads(raw)
        if path.startswith('/api/'):
            assert payload['success'] is True and payload['error'] is None
            return payload['data']
        return payload

def until(check, timeout=40):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.2)
    raise AssertionError('Timed out waiting for observable state')

def start():
    global app, generation, csrf
    generation += 1
    with (root / ('app-' + str(generation) + '.log')).open('wb') as log:
        app = subprocess.Popen([a.launcher], stdout=log, stderr=subprocess.STDOUT)
    def ready():
        assert app.poll() is None
        try:
            return request('GET', '/actuator/health/readiness')['status'] == 'UP'
        except urllib.error.URLError:
            return False
    until(ready)
    csrf = request('GET', '/api/auth/csrf')['token']
    request('POST', '/api/auth/login', urllib.parse.urlencode({'username': 'admin', 'password': os.environ['IWORK_ACCEPTANCE_PASSWORD']}).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
    csrf = request('GET', '/api/auth/csrf')['token']

def submit():
    cid = request('POST', '/api/conversations', {'title': '清理故障与独立监督验收'})['id']
    task = request('POST', '/api/conversations/' + cid + '/tasks', {'requestId': str(uuid.uuid4()), 'modelId': a.model, 'message': '整理资料并修改原版本', 'materialIds': [material], 'outputMode': 'DOCUMENT', 'baseVersionId': a.base_version})['id']
    return cid, task

def live():
    cid, task = submit()
    parse = until(lambda: next(iter(request('GET', '/api/tasks/' + task + '/parses')), None))
    worker = until(lambda: docker('ps', '-q', '--filter', 'label=iwork.job=' + parse['id']))
    until(lambda: len(docker('top', worker, '-eo', 'pid,ppid,args').splitlines()) >= 3)
    return cid, task, parse['id'], worker

def all_workers():
    return docker('ps', '-aq', '--filter', 'label=iwork.sandbox=issue21')

def present(worker):
    return bool(docker('ps', '-aq', '--filter', 'id=' + worker))

def protected(task, status):
    state = request('GET', '/api/tasks/' + task)
    assert state['status'] == status, state
    assert request('GET', '/api/document-versions/' + a.base_version + '/content') == original
    assert request('GET', '/api/materials/' + material + '/content') == source
    assert all(v['taskId'] != task for v in request('GET', '/api/document-versions')['items'])
    return state

def rejected():
    before = all_workers()
    cid, task = submit()
    until(lambda: request('GET', '/api/tasks/' + task)['status'] == 'FAILED')
    assert all_workers() == before, 'Refused processing created a container'
    return task

def kill_app():
    app.kill()
    app.wait(timeout=10)

control = None
try:
    start()
    original = request('GET', '/api/document-versions/' + a.base_version + '/content')
    source = b'# Supervision fixture\nOriginal remains available.'
    material = request('POST', '/api/materials', source, {'Content-Type': 'application/octet-stream', 'X-File-Name': 'supervision.md'})['id']
    control = docker('run', '-d', '--network', 'none', '--label', 'iwork.sandbox=issue21-control', '--entrypoint', 'python', 'iwork-text-parser-supervision:21', '-c', 'import time; time.sleep(600)')
    for fault in ('deny-cleanup', 'daemon-offline'):
        cid, task, parse, worker = live()
        (faults / fault).touch()
        request('POST', '/api/tasks/' + task + '/cancel', {})
        until(lambda: request('GET', '/api/tasks/' + task)['executionState'] == 'STOPPED')
        pending = request('GET', '/api/tasks/' + task + '/parses')[0]
        assert pending['cleanup'] == 'UNKNOWN'
        persisted = json.loads(docker('exec', manager, 'cat', '/registry/' + parse + '.json'))
        assert persisted['status'] == 'CANCELLED' and persisted['cleanup'] == 'UNKNOWN'
        processes = docker('top', worker, '-eo', 'pid,ppid,args')
        assert len(processes.splitlines()) >= 3
        denied = rejected()
        docker('restart', manager)
        retained = json.loads(docker('exec', manager, 'cat', '/registry/' + parse + '.json'))
        assert retained['status'] == 'CANCELLED' and retained['cleanup'] == 'UNKNOWN'
        assert json.loads(docker('inspect', worker))[0]['State']['Running']
        (faults / fault).unlink()
        until(lambda: not present(worker))
        until(lambda: request('GET', '/api/tasks/' + task + '/parses')[0]['cleanup'] == 'CONFIRMED')
        docker('restart', manager)
        until(lambda: json.loads(docker('exec', manager, 'cat', '/registry/' + parse + '.json'))['cleanup'] == 'CONFIRMED')
        assert not present(worker)
        state = protected(task, 'CANCELLED')
        assert json.loads(docker('inspect', control))[0]['State']['Running']
        results.append({'fault': fault, 'conversation': cid, 'task': state, 'pending': pending, 'liveProcesses': processes, 'persistedAcrossRestart': True, 'deniedTask': denied})
        print(fault + ': live resource retained as unknown, new processing refused, retry confirmed deletion', flush=True)

    for supervisor_down in (False, True):
        cid, task, parse, worker = live()
        created = json.loads(docker('inspect', worker))[0]['Created']
        born = datetime.datetime.fromisoformat(created[:26] + '+00:00').timestamp()
        docker('exec', worker, 'python', '-c', "from pathlib import Path; Path('/tmp/codex/deadline').write_text('9999999999999')")
        if supervisor_down:
            docker('update', '--restart=no', watchdog)
            docker('kill', watchdog)
            time.sleep(2.5)
            denied = rejected()
        docker('update', '--restart=no', manager)
        kill_app()
        docker('kill', manager)
        processes = docker('top', worker, '-eo', 'pid,ppid,args')
        assert len(processes.splitlines()) >= 3
        print('Waiting for original Docker creation deadline; supervisor down=' + str(supervisor_down), flush=True)
        if supervisor_down:
            until(lambda: time.time() - born >= 63, 70)
            assert json.loads(docker('inspect', worker))[0]['State']['Running']
            restored = time.time()
            docker('start', watchdog)
            until(lambda: not present(worker), 10)
            assert time.time() - restored < 10, 'Watchdog reset an overdue container lifetime'
        else:
            until(lambda: not present(worker), 75)
        age = time.time() - born
        assert age >= 60
        receipts = [json.loads(line) for line in docker('logs', watchdog).splitlines() if line.startswith('{')]
        receipt = next((r for r in receipts if r.get('container', '').startswith(worker)), None)
        assert receipt and receipt['confirmedAt'] >= receipt['expiredAt'], 'Missing actual expiry/removal observation times'
        docker('start', manager)
        docker('update', '--restart=unless-stopped', manager)
        start()
        until(lambda: request('GET', '/api/tasks/' + task + '/parses')[0]['cleanup'] == 'CONFIRMED')
        state = protected(task, 'INTERRUPTED')
        assert json.loads(docker('inspect', control))[0]['State']['Running']
        results.append({'supervisorWasDown': supervisor_down, 'conversation': cid, 'task': state, 'created': created, 'observedAbsentAgeSeconds': age, 'processesAfterBothKilled': processes, 'receipt': receipt})
        print('Independent expiry verified; no deadline reset', flush=True)
    cid, task, parse, worker = live()
    request('POST', '/api/tasks/' + task + '/cancel', {})
    until(lambda: not present(worker))
    results.append({'admissionRestoredTask': task, 'conversation': cid})
    (root / 'evidence.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
finally:
    for name in ('deny-cleanup', 'daemon-offline'):
        (faults / name).unlink(missing_ok=True)
    for service in (manager, watchdog):
        docker('start', service)
        docker('update', '--restart=unless-stopped', service)
    if app and app.poll() is None:
        app.terminate()
        try:
            app.wait(timeout=15)
        except subprocess.TimeoutExpired:
            kill_app()
    if control:
        docker('rm', '-f', control)
