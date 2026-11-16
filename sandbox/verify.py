"""Real Docker contract check against a disposable Compose sandbox instance.
Usage: python sandbox/verify.py URL KEY_FILE COMPOSE_FILE INSTANCE
Never point this at production: it pauses only the named test watchdog.
"""
import base64
import concurrent.futures
import json
import pathlib
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

url, key_file, compose_file, instance = sys.argv[1:]
assert instance.startswith('issue16'), 'Only disposable issue16 instances are permitted'
key = pathlib.Path(key_file).read_text().strip()
compose = ['docker', 'compose', '-f', compose_file]

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.DEVNULL).strip()

def request(method, path, token='', body=None, service=key):
    req = urllib.request.Request(url + path, data=None if body is None else json.dumps(body).encode(), method=method,
        headers={'Authorization': 'Bearer ' + service, 'X-Task-Token': token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())

def payload(raw=b'# text\r\ncontent'):
    return {'id': str(uuid.uuid4()), 'taskId': str(uuid.uuid4()), 'materialId': str(uuid.uuid4()), 'operation': 'parse_text', 'content': base64.b64encode(raw).decode()}

def end(body, token):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        code, result = request('GET', '/tasks/' + body['id'], token)
        assert code == 200, (code, result)
        if result['status'] != 'RUNNING':
            assert result['cleanup'] == 'CONFIRMED', result
            return result
        time.sleep(.1)
    raise AssertionError('Processing did not terminate')

for _ in range(30):
    if request('GET', '/health')[0] == 200:
        break
    time.sleep(.5)
assert request('GET', '/health', service='wrong')[0] == 401
assert request('POST', '/tasks', 'a'*64, dict(payload(), command='sh'))[0] == 400
assert request('POST', '/tasks', 'a'*64, dict(payload(), id='../etc/passwd'))[0] == 400
assert request('POST', '/tasks?command=sh', 'a'*64, payload())[0] == 400
body = payload('\ufeff# 中文\r\n正文\r结束'.encode())
assert request('POST', '/tasks', 'a'*64, body)[0] == 200
assert request('GET', '/tasks/' + body['id'], 'b'*64)[0] == 404
result = end(body, 'a'*64)
assert result['text'] == '# 中文\n正文\n结束', result
assert request('POST', '/tasks', 'a'*64, body)[1]['status'] == 'COMPLETED'
assert request('POST', '/tasks', 'a'*64, dict(body, content='YQ=='))[0] == 400
bad = payload(b'\xff\xfe')
assert request('POST', '/tasks', 'b'*64, bad)[0] == 200
assert end(bad, 'b'*64)['status'] == 'FAILED'
# Cancellation arriving before a delayed POST must prevent any subsequent creation.
late = payload()
auth = {'taskId': late['taskId'], 'materialId': late['materialId']}
assert request('DELETE', '/tasks/' + late['id'], 'c'*64, auth)[1]['cleanup'] == 'CONFIRMED'
assert request('POST', '/tasks', 'c'*64, late)[1]['status'] == 'CANCELLED'
assert not docker('ps', '-aq', '--filter', 'label=iwork.job=' + late['id'])
# Capture actual manager-created containers during large real parses, not a reconstructed config.
observed = []
stop = threading.Event()
def inspect_live():
    while not stop.is_set():
        for ident in docker('ps', '-aq', '--filter', 'label=iwork.sandbox=' + instance).split():
            try:
                observed.append(json.loads(docker('inspect', ident))[0])
            except subprocess.CalledProcessError:
                pass
thread = threading.Thread(target=inspect_live)
thread.start()
try:
    for _ in range(5):
        large = payload(('实际解析资料\n' * 80000).encode())
        assert request('POST', '/tasks', 'd'*64, large)[0] == 200
        assert end(large, 'd'*64)['status'] == 'COMPLETED'
        if observed:
            break
finally:
    stop.set(); thread.join()
assert observed, 'No actual manager container was inspected'
info = observed[0]
config, host = info['Config'], info['HostConfig']
assert config['User'] == '1000:1000'
assert config['Entrypoint'] == ['python', '-I', '-B', '/opt/parse.py']
assert host['NetworkMode'] == 'none' and host['ReadonlyRootfs'] and not host['Privileged']
assert host['CapDrop'] == ['ALL'] and 'no-new-privileges' in host['SecurityOpt']
assert host['Memory'] == host['MemorySwap'] == 67108864 and host['NanoCpus'] == 500000000 and host['PidsLimit'] == 32
assert not host['Binds'] and set(host['Tmpfs']) == {'/tmp/codex'}
assert not any('IWORK_' in env or 'PASSWORD' in env for env in config['Env'])
assert all(mount['Type'] == 'tmpfs' for mount in info['Mounts'])
# Concurrent create/cancel must finish with no surviving container.
for _ in range(6):
    race = payload(('资料\n' * 30000).encode()); token='e'*64
    with concurrent.futures.ThreadPoolExecutor() as pool:
        created=pool.submit(request, 'POST', '/tasks', token, race)
        cancelled=pool.submit(request, 'DELETE', '/tasks/'+race['id'], token, {'taskId':race['taskId'],'materialId':race['materialId']})
        assert created.result()[0] == 200
        assert cancelled.result()[0] == 200
    assert end(race, token)['status'] in ('CANCELLED','COMPLETED')
    assert not docker('ps','-aq','--filter','label=iwork.job='+race['id'])
watchdog = subprocess.check_output(compose + ['ps','-q','sandbox-watchdog'], text=True).strip()
manager = subprocess.check_output(compose + ['ps','-q','sandbox-manager'], text=True).strip()
watch_info=json.loads(docker('inspect',watchdog))[0]; manager_info=json.loads(docker('inspect',manager))[0]
assert watch_info['HostConfig']['NetworkMode']=='none'
for control in (watch_info,manager_info):
    assert not any('PASSWORD' in env or 'MODEL_KEY' in env or 'DB_' in env for env in control['Config']['Env'])
assert all(mount['Destination']!='/run/secrets/sandbox_key' for mount in watch_info['Mounts'])
assert any(mount['Destination']=='/watchdog' and not mount['RW'] for mount in manager_info['Mounts'])
docker('pause',watchdog)
try:
    time.sleep(2.5)
    assert request('POST','/tasks','f'*64,payload())[0]==503
finally:
    docker('unpause',watchdog)
# Production parser waits on stdin. Independent watcher must remove it by Docker creation time.
name='iwork-'+instance+'-ttl-'+uuid.uuid4().hex
ident=docker('create','--name',name,'--label','iwork.sandbox='+instance,'--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','64m','--memory-swap','64m','--pids-limit','32','--cpus','0.5','-i','iwork-text-parser:1')
started=time.monotonic()
try:
    docker('start',ident)
    while time.monotonic()-started < 75:
        if not docker('ps','-aq','--filter','id='+ident):
            break
        time.sleep(.5)
    assert not docker('ps','-aq','--filter','id='+ident), 'Watchdog TTL failed'
    elapsed=time.monotonic()-started
    assert elapsed >= 55, elapsed
finally:
    if docker('ps','-aq','--filter','id='+ident):
        docker('rm','-f',ident)
subprocess.run(compose + ['restart', 'sandbox-manager'], check=True, stdout=subprocess.DEVNULL)
recovered = False
for _ in range(120):
    try:
        if request('GET', '/health')[0] != 200:
            time.sleep(.5)
            continue
        code, retained = request('GET', '/tasks/' + body['id'], 'a'*64)
        if code == 200:
            assert retained['status'] == 'COMPLETED' and retained['cleanup'] == 'CONFIRMED'
            assert retained['text'] == '# 中文\n正文\n结束'
            recovered = True
            break
    except OSError:
        pass
    time.sleep(.5)
assert recovered, 'Manager recovery did not retain completed parse'
print(json.dumps({'result':'PASS','realParser':True,'inspectedContainer':info['Id'],'ttlSeconds':round(elapsed,2),'checks':['identity','task-token','fixed-operation','path-rejection','UTF8','invalid-UTF8','idempotency','pre-cancel','cancel-race','isolation','credential-absence','stale-supervision','external-TTL','manager-restart']}))
