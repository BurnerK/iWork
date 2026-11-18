"""Local issue20 process/Docker acceptance. Uses only a synthetic fixture launcher.
The launcher must exec Java; this script kills only processes it starts.
"""
import argparse
import hashlib
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
p.add_argument('launcher')
p.add_argument('model')
p.add_argument('base_version')
p.add_argument('compose_file')
p.add_argument('env_file')
p.add_argument('evidence_directory')
p.add_argument('--files-directory', required=True)
a = p.parse_args()
root = pathlib.Path(a.evidence_directory)
root.mkdir(parents=True, exist_ok=True)
compose = ['docker', 'compose', '--env-file', a.env_file, '-f', a.compose_file]
assert json.loads(subprocess.check_output(compose + ['config', '--format', 'json']))['name'] == 'iwork-issue20-check'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = ''
app = None
generation = 0
results = []
database = 'iwork-issue12-check-mysql-1'
assert json.loads(subprocess.check_output(['docker', 'inspect', database]))[0]['Config']['Labels']['com.docker.compose.project'] == 'iwork-issue12-check'
mysql = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', database, 'mysql', '-uroot', '--batch', '--skip-column-names', '--unbuffered', 'iwork']
mysql_env = {**os.environ, 'MYSQL_PWD': os.environ['IWORK_ACCEPTANCE_DB_PASSWORD']}

def sql(statement):
    return subprocess.check_output(mysql, input=statement, text=True, env=mysql_env).strip()

def lock(statement):
    process = subprocess.Popen(mysql, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, env=mysql_env)
    process.stdin.write('BEGIN; SELECT CONNECTION_ID(); ' + statement + "; SELECT 'LOCKED'; DO SLEEP(90); ROLLBACK;\n")
    process.stdin.close()
    connection = int(process.stdout.readline().strip())
    while True:
        line = process.stdout.readline().strip()
        assert line, 'Database lock connection ended early'
        if line == 'LOCKED':
            return process, connection

def unlock(held):
    process, connection = held
    sql('KILL CONNECTION ' + str(connection))
    process.wait(timeout=10)

def request(method, path, body=None, headers=None):
    extra = dict(headers or {})
    extra['X-CSRF-TOKEN'] = csrf
    extra.setdefault('Content-Type', 'application/json')
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    with client.open(urllib.request.Request('http://127.0.0.1:18092' + path, data=data, method=method, headers=extra), timeout=15) as response:
        raw = response.read()
        if 'json' not in response.headers.get('Content-Type', ''):
            return raw
        envelope = json.loads(raw)
        assert envelope['success'] is True and envelope['error'] is None
        return envelope['data']

def until(check, timeout=40):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.1)
    raise AssertionError('Timed out waiting for observable state')

def start(wait=True):
    global app, generation, csrf
    generation += 1
    with (root / ('app-' + str(generation) + '.log')).open('wb') as log:
        app = subprocess.Popen([a.launcher], stdout=log, stderr=subprocess.STDOUT)
    if not wait:
        return
    def ready():
        assert app.poll() is None, 'Fixture exited'
        try:
            return request('GET', '/actuator/health/readiness')['status'] == 'UP'
        except (urllib.error.URLError, TimeoutError):
            return False
    until(ready)
    csrf = request('GET', '/api/auth/csrf')['token']
    request('POST', '/api/auth/login', urllib.parse.urlencode({'username': 'admin', 'password': os.environ['IWORK_ACCEPTANCE_PASSWORD']}).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
    csrf = request('GET', '/api/auth/csrf')['token']

def kill():
    assert app and app.poll() is None
    app.kill()
    app.wait(timeout=10)

def conversation():
    return request('POST', '/api/conversations', {'title': '重启与中断恢复验收'})['id']

def submit(cid, message, materials=None, mode='DOCUMENT'):
    body = {'requestId': str(uuid.uuid4()), 'modelId': a.model, 'message': message, 'outputMode': mode, 'baseVersionId': a.base_version if mode == 'DOCUMENT' else None, 'materialIds': materials or []}
    task = request('POST', '/api/conversations/' + cid + '/tasks', body)
    return cid, body, task['id']

def document_versions(cid):
    page = 0
    while True:
        result = request('GET', f'/api/conversations/{cid}/documents?page={page}')
        yield from result['items']
        if not result['hasMore']:
            return
        page += 1

def recovered(item):
    cid, body, task = item
    state = request('GET', '/api/tasks/' + task)
    assert state['status'] == 'INTERRUPTED' and state['executionState'] == 'UNKNOWN', state
    assert request('POST', '/api/conversations/' + cid + '/tasks', body)['id'] == task
    calls = request('GET', '/api/tasks/' + task + '/calls')
    assert all(call['status'] == 'CLOSED' for call in calls)
    assert request('GET', '/api/budget')['reserved'] == '0.000000'
    assert all(v['taskId'] != task for v in document_versions(cid))
    assert request('GET', '/api/document-versions/' + a.base_version + '/content') == original
    return {'task': state, 'calls': calls, 'budget': request('GET', '/api/budget')}

control = None
manager = subprocess.check_output(compose + ['ps', '-q', 'sandbox-manager'], text=True).strip()
assert manager
try:
    start()
    original = request('GET', '/api/document-versions/' + a.base_version + '/content')
    interrupted_start = submit(conversation(), 'before')
    until(lambda: request('GET', '/api/tasks/' + interrupted_start[2] + '/calls'))
    kill()
    held = lock("SELECT id FROM tasks WHERE id='" + interrupted_start[2] + "' FOR UPDATE")
    try:
        start(wait=False)
        def startup_status():
            try:
                request('GET', '/api/auth/csrf')
                return 200
            except urllib.error.HTTPError as error:
                return error.code
            except urllib.error.URLError:
                return None
        assert until(startup_status) == 503, 'Business HTTP opened before interrupted-task recovery finished'
    finally:
        unlock(held)
    kill()
    start()
    results.append({'phase': 'startup-admission', 'recovered': recovered(interrupted_start)})
    for phase in ('before', 'known', 'prepared'):
        item = submit(conversation(), phase)
        task = item[2]
        if phase == 'before':
            until(lambda: request('GET', '/api/tasks/' + task + '/calls'))
        elif phase == 'known':
            until(lambda: any(c['inputTokens'] == 23 and c['outputTokens'] == 11 for c in request('GET', '/api/tasks/' + task + '/calls')))
        else:
            until(lambda: request('GET', '/api/test/ready/' + task)['ready'])
            assert sql("SELECT COUNT(*) FROM conversation_contexts WHERE task_id='" + task + "'") == '1'
        kill()
        start()
        first = recovered(item)
        if phase == 'known':
            assert first['calls'][0]['inputTokens'] == 23 and first['calls'][0]['outputTokens'] == 11
        kill()
        start()
        second = recovered(item)
        assert first == second, 'Repeated startup changed accounting or task state'
        assert sql("SELECT COUNT(*) FROM conversation_contexts c JOIN tasks t ON t.id=c.task_id WHERE c.task_id='" + task + "' AND t.status='COMPLETED'") == '0'
        new = submit(item[0], 'normal')
        until(lambda: request('GET', '/api/tasks/' + new[2])['status'] == 'COMPLETED')
        results.append({'phase': phase, 'recovered': first, 'explicitNewTask': new[2], 'conversation': item[0]})
        print('Recovered ' + phase + ' and verified repeated startup', flush=True)

    cid = conversation()
    for message in ('summary-crash 蓝鲸项目，周五交付。', 'background ' + 'x' * 6000):
        seed = submit(cid, message, mode='TEXT')
        until(lambda: request('GET', '/api/tasks/' + seed[2])['status'] == 'COMPLETED')
    kill()
    os.environ['IWORK_TEST_SUMMARY_CRASH'] = 'true'
    start()
    summary = submit(cid, '继续整理计划', mode='TEXT')
    until(lambda: any(c['purpose'] == 'SUMMARY' and c['status'] == 'OPEN' for c in request('GET', '/api/tasks/' + summary[2] + '/calls')))
    kill()
    os.environ.pop('IWORK_TEST_SUMMARY_CRASH')
    start()
    interrupted = recovered(summary)
    assert sql("SELECT COUNT(*) FROM conversation_contexts WHERE task_id='" + summary[2] + "'") == '0'
    continued = submit(cid, '继续整理计划', mode='TEXT')
    until(lambda: request('GET', '/api/tasks/' + continued[2])['status'] == 'COMPLETED')
    calls = request('GET', '/api/tasks/' + continued[2] + '/calls')
    assert any(c['purpose'] == 'SUMMARY' for c in calls)
    results.append({'phase': 'automatic-summary', 'recovered': interrupted, 'explicitNewTask': continued[2], 'newCalls': calls, 'conversation': cid})
    print('Interrupted automatic summary retained history; explicit new task compacted successfully', flush=True)

    item = submit(conversation(), 'prepared')
    until(lambda: request('GET', '/api/test/ready/' + item[2])['ready'])
    artifact, version, size, digest = sql("SELECT artifact_id,version_id,byte_size,sha256 FROM document_drafts WHERE task_id='" + item[2] + "'").split('\t')
    held = lock("SELECT id FROM documents WHERE id='" + artifact + "' FOR UPDATE")
    try:
        request('POST', '/api/test/release/' + item[2], {})
        file = pathlib.Path(a.files_directory) / 'documents' / (version + '.md')
        until(file.exists)
        content = file.read_bytes()
        assert len(content) == int(size) and hashlib.sha256(content).hexdigest() == digest
        assert sql("SELECT COUNT(*) FROM document_versions WHERE id='" + version + "'") == '0'
        kill()
    finally:
        unlock(held)
    start()
    interrupted = recovered(item)
    try:
        request('GET', '/api/document-versions/' + version + '/content')
        raise AssertionError('Uncommitted file became a published version')
    except urllib.error.HTTPError as error:
        assert error.code == 404
    assert file.read_bytes() == content
    results.append({'phase': 'formal-file-before-commit', 'recovered': interrupted, 'unpublishedVersion': version, 'sha256': digest, 'conversation': item[0]})
    print('Complete file survived; uncommitted version remains invisible', flush=True)

    material = request('POST', '/api/materials', b'# Recovery source\nOriginal survives restart.', {'Content-Type': 'application/octet-stream', 'X-File-Name': 'recovery.md'})['id']
    items = [submit(conversation(), '整理资料', [material]) for _ in range(2)]
    jobs = []
    for item in items:
        parse = until(lambda: next(iter(request('GET', '/api/tasks/' + item[2] + '/parses')), None))
        job = until(lambda: subprocess.check_output(['docker', 'ps', '-q', '--filter', 'label=iwork.job=' + parse['id']], text=True).strip())
        jobs.append((parse['id'], job))
    control = subprocess.check_output(['docker', 'run', '-d', '--network', 'none', '--label', 'iwork.sandbox=issue20-control', '--name', 'iwork-issue20-control-' + str(uuid.uuid4()), '--entrypoint', 'python', 'iwork-text-parser-recovery:20', '-c', 'import time; time.sleep(120)'], text=True).strip()
    subprocess.run(['docker', 'update', '--restart=no', manager], check=True, stdout=subprocess.DEVNULL)
    kill()
    subprocess.run(['docker', 'kill', manager], check=True, stdout=subprocess.DEVNULL)
    live = []
    for parse, job in jobs:
        info = json.loads(subprocess.check_output(['docker', 'inspect', job]))[0]
        processes = subprocess.check_output(['docker', 'top', job, '-eo', 'pid,ppid,args'], text=True)
        assert info['State']['Running'] and info['Config']['Labels']['iwork.sandbox'] == 'issue20'
        assert len(processes.strip().splitlines()) >= 3 and 'time.sleep(50)' in processes, processes
        live.append({'parse': parse, 'container': job, 'processes': processes, 'running': True})
    subprocess.run(['docker', 'start', manager], check=True, stdout=subprocess.DEVNULL)
    until(lambda: not subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=iwork.sandbox=issue20'], text=True).strip())
    assert json.loads(subprocess.check_output(['docker', 'inspect', control]))[0]['State']['Running']
    start()
    for item in items:
        state = recovered(item)
        parsed = until(lambda: (rows if (rows := request('GET', '/api/tasks/' + item[2] + '/parses')) and rows[0]['cleanup'] == 'CONFIRMED' else None))
        results.append({'phase': 'container', 'recovered': state, 'parses': parsed, 'conversation': item[0]})
    results.append({'liveBeforeManagerRestart': live, 'otherInstancePreserved': control})
    (root / 'evidence.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print('Two live process trees removed; other instance preserved. Evidence saved.', flush=True)
finally:
    if app and app.poll() is None:
        app.terminate()
        try:
            app.wait(timeout=15)
        except subprocess.TimeoutExpired:
            app.kill()
            app.wait(timeout=10)
    if control:
        subprocess.run(['docker', 'rm', '-f', control], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(['docker', 'start', manager], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(['docker', 'update', '--restart=unless-stopped', manager], check=True, stdout=subprocess.DEVNULL)
