#!/usr/bin/env python3
"""Verify the user-selected restricted-manager boundary, using synthetic resources only."""
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
TEMP = Path(tempfile.gettempdir()) / 'codex'
TEMP.mkdir(exist_ok=True)
OUT = Path(tempfile.mkdtemp(prefix='iwork-managed-', dir=TEMP))
for name in ['app', 'registry']:
    (OUT / name).mkdir(mode=0o777)
    (OUT / name).chmod(0o777)
RUN = 'iw8m-' + uuid.uuid4().hex[:12]
ENV = dict(os.environ, PROBE_RUN=RUN, PROBE_EVIDENCE=str(OUT))
COMMANDS = []
REPORT = {'run': RUN, 'host': platform.platform()}
APPS = []
manager = None


def run(*args, check=True, timeout=120):
    r = subprocess.run(args, cwd=HERE, env=ENV, text=True, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=timeout)
    COMMANDS.append({'argv': list(args), 'returncode': r.returncode, 'output': r.stdout})
    (OUT / 'commands.json').write_text(json.dumps(COMMANDS, indent=2))
    if check and r.returncode:
        raise RuntimeError(repr(args) + '\n' + r.stdout)
    return r.stdout.strip()


def dc(*args, **kwargs):
    return run('docker', 'compose', '-f', 'compose.managed.yaml', '-p', RUN, *args, **kwargs)


def until(test, timeout=20):
    deadline = time.monotonic() + timeout
    while True:
        value = test()
        if value:
            return value
        if time.monotonic() > deadline:
            raise AssertionError('condition timed out')
        time.sleep(.25)


def app(mode, suffix):
    cid = dc('run', '-d', '--name', RUN + '-' + suffix, 'app', mode).splitlines()[-1]
    APPS.append(cid)
    return cid


def exists(cid):
    return bool(run('docker', 'ps', '-aq', '--filter', 'id=' + cid))


def slow_task(suffix):
    leasefile = OUT / 'app/client-lease.local.json'
    leasefile.unlink(missing_ok=True)
    cid = app('crash', suffix)
    until(lambda: leasefile.exists() and leasefile.stat().st_size)
    lease = json.loads(leasefile.read_text())
    worker = until(lambda: run('docker', 'ps', '-q', '--filter', 'label=iwork.lease=' + lease['id']))
    until(lambda: run('docker', 'exec', worker, 'sh', '-c',
                     'test -f /workspace/child-heartbeat && echo ready || true') == 'ready')
    return cid, lease['id'], worker


def progression(worker):
    first = int(run('docker', 'exec', worker, 'cat', '/workspace/child-heartbeat'))
    time.sleep(.35)
    second = int(run('docker', 'exec', worker, 'cat', '/workspace/child-heartbeat'))
    assert second > first
    return [first, second]


print('Evidence:', OUT, flush=True)
try:
    context = json.loads(run('docker', 'context', 'inspect'))[0]
    endpoint = os.environ.get('DOCKER_HOST', context['Endpoints']['docker']['Host'])
    assert endpoint.startswith('unix://')
    ENV['DOCKER_SOCKET'] = endpoint.removeprefix('unix://')
    run('mvn', '-s', 'settings.xml', '-q', 'compile',
        'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy-dependencies', '-DoutputDirectory=target/lib')
    run('docker', 'build', '-f', 'Dockerfile.worker', '-t', 'iwork-sandbox-worker:local', '.')
    run('docker', 'build', '-f', 'Dockerfile.app', '-t', 'iwork-sandbox-probe:local', '.')
    REPORT['sourceHashes'] = {str(p.relative_to(HERE)): hashlib.sha256(p.read_bytes()).hexdigest()
                              for p in sorted(HERE.rglob('*')) if p.is_file() and
                              not any(x in p.parts for x in ['target', 'evidence', '__pycache__'])}
    REPORT['images'] = json.loads(run('docker', 'image', 'inspect', 'iwork-sandbox-worker:local', 'iwork-sandbox-probe:local'))
    dc('up', '-d', 'manager')
    manager = dc('ps', '-q', 'manager')
    until(lambda: 'MANAGER_READY' in run('docker', 'logs', manager))
    manager_config = json.loads(run('docker', 'inspect', manager))[0]
    assert not any(x.startswith(('DB_PASSWORD=', 'MODEL_API_KEY=')) for x in manager_config['Config']['Env'])
    assert not manager_config['HostConfig']['PortBindings']
    assert set(m['Destination'] for m in manager_config['Mounts']) == {'/var/run/docker.sock', '/registry'}
    main = app('suite', 'main')
    main_config = json.loads(run('docker', 'inspect', main))[0]
    assert set(m['Destination'] for m in main_config['Mounts']) == {'/evidence'}
    assert main_config['Config']['User'] == '1000:1000'
    assert main_config['HostConfig']['ReadonlyRootfs']
    assert not main_config['HostConfig']['PortBindings']
    network_name = list(main_config['NetworkSettings']['Networks'])[0]
    assert json.loads(run('docker', 'network', 'inspect', network_name))[0]['Internal']
    REPORT['boundary'] = {'mainMounts': main_config['Mounts'], 'mainUser': main_config['Config']['User'],
                          'managerMounts': manager_config['Mounts'], 'managerHasBusinessCredentials': False,
                          'internalControlNetwork': True, 'hostPorts': []}
    exitcode = run('docker', 'wait', main)
    logs = run('docker', 'logs', main)
    assert exitcode == '0', logs
    print('Restricted API, SDK tool, cancellation and timeout passed', flush=True)
    old = (OUT / 'app/managed-version.md').read_bytes()
    main, lease, worker = slow_task('main-crash')
    before = progression(worker)
    run('docker', 'kill', '--signal=KILL', main)
    after = progression(worker)
    begin = time.monotonic()
    until(lambda: not exists(worker), timeout=10)
    state = json.loads((OUT / 'registry' / (lease + '.json')).read_text())
    assert state['state'] == 'FAILED' and state['cleaned']
    REPORT['mainCrash'] = {'childBeforeKill': before, 'childAfterKill': after,
                            'removedAfterSeconds': time.monotonic() - begin, 'managerState': state['state']}
    main, lease, worker = slow_task('manager-crash')
    before = progression(worker)
    run('docker', 'kill', '--signal=KILL', manager)
    run('docker', 'kill', '--signal=KILL', main)
    after = progression(worker)
    begin = time.monotonic()
    until(lambda: not exists(worker), timeout=18)
    REPORT['bothCrash'] = {'childBeforeKill': before, 'childAfterKill': after,
                            'removedWithoutJavaAfterSeconds': time.monotonic() - begin,
                            'hardContainerLifetimeSeconds': 15}
    dc('up', '-d', 'manager')
    until(lambda: run('docker', 'logs', manager).count('MANAGER_READY') >= 2)
    dc('run', '--rm', '-T', 'app', 'recovered')
    assert (OUT / 'app/managed-version.md').read_bytes() == old
    assert not run('docker', 'ps', '-aq', '--filter', 'label=iwork.manager=' + RUN)
    REPORT.update(probePassed=True, productionAccepted=False, remainingTaskContainers=0,
                  scope='restricted-manager feasibility with synthetic data and fixed fixtures')
    (OUT / 'result.json').write_text(json.dumps(REPORT, indent=2))
    print('Main/manager SIGKILL, independent lifetime and recovery passed', flush=True)
finally:
    if manager:
        run('docker', 'logs', manager, check=False)
    for cid in run('docker', 'ps', '-aq', '--filter', 'label=iwork.manager=' + RUN, check=False).splitlines():
        if len(cid) == 12 and all(c in '0123456789abcdef' for c in cid):
            run('docker', 'rm', '-f', cid, check=False)
    for cid in APPS:
        run('docker', 'rm', '-f', cid, check=False)
    dc('down', '--remove-orphans', check=False)
    print('Evidence retained:', OUT, flush=True)
