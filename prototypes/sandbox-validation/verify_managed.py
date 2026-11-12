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
for name in ['app', 'registry', 'watchdog', 'faults']:
    (OUT / name).mkdir(mode=0o777)
    (OUT / name).chmod(0o777)
RUN = 'iw8m-' + uuid.uuid4().hex[:12]
ENV = dict(os.environ, PROBE_RUN=RUN, PROBE_EVIDENCE=str(OUT))
COMMANDS = []
REPORT = {'run': RUN, 'host': platform.platform()}
APPS = []
manager = None
watchdog = None
foreign = None


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
    dc('up', '-d', 'watchdog')
    watchdog = dc('ps', '-q', 'watchdog')
    until(lambda: (OUT / 'watchdog/health.json').exists())
    dc('up', '-d', 'manager')
    manager = dc('ps', '-q', 'manager')
    until(lambda: 'MANAGER_READY' in run('docker', 'logs', manager))
    manager_config = json.loads(run('docker', 'inspect', manager))[0]
    assert not any(x.startswith(('DB_PASSWORD=', 'MODEL_API_KEY=')) for x in manager_config['Config']['Env'])
    assert not manager_config['HostConfig']['PortBindings']
    assert set(m['Destination'] for m in manager_config['Mounts']) == {'/var/run/docker.sock', '/registry', '/watchdog', '/faults'}
    watchdog_config = json.loads(run('docker', 'inspect', watchdog))[0]
    assert watchdog_config['HostConfig']['NetworkMode'] == 'none'
    assert set(m['Destination'] for m in watchdog_config['Mounts']) == {'/var/run/docker.sock', '/watchdog'}
    assert not any(x.startswith(('DB_PASSWORD=', 'MODEL_API_KEY=', 'MANAGER_KEY=')) for x in watchdog_config['Config']['Env'])
    foreign = run('docker', 'run', '-d', '--name', RUN + '-foreign', '--label', 'iwork.probe=' + RUN,
                  '--label', 'iwork.manager=' + RUN + '-foreign', '--network', 'none', '--read-only',
                  '--cap-drop=ALL', '--memory=32m', '--pids-limit=16', 'iwork-sandbox-worker:local', 'sleep', '180')
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
                          'internalControlNetwork': True, 'hostPorts': [],
                          'watchdogMounts': watchdog_config['Mounts'], 'watchdogNetwork': 'none'}
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
    # Restart while two actual processing containers are still alive; no automatic expiry first.
    main = app('multi-crash', 'live-orphans')
    multi_file = OUT / 'app/client-multi.local.json'
    until(lambda: multi_file.exists() and multi_file.stat().st_size)
    leases = json.loads(multi_file.read_text())
    orphans = [until(lambda lease=l: run('docker', 'ps', '-q', '--filter', 'label=iwork.lease=' + lease['id'])) for l in leases]
    run('docker', 'kill', '--signal=KILL', manager)
    run('docker', 'kill', '--signal=KILL', main)
    before_recovery = [progression(cid) for cid in orphans]
    assert all(exists(cid) for cid in orphans)
    ready_count = run('docker', 'logs', manager).count('MANAGER_READY')
    dc('up', '-d', 'manager')
    until(lambda: run('docker', 'logs', manager).count('MANAGER_READY') > ready_count)
    for cid in orphans:
        assert not exists(cid)
        assert 'RECOVER_REMOVED ' + cid in run('docker', 'logs', manager)
    dc('run', '--rm', '-T', 'app', 'recovered-multi')
    assert exists(foreign)
    REPORT['liveRecovery'] = {'containersAliveBeforeRestart': orphans, 'childHeartbeats': before_recovery,
                               'managerRemovedEach': True, 'foreignContainerUntouched': True}
    print('Two live orphan containers recovered; foreign sentinel untouched', flush=True)

    # Fail actual manager-side Docker CLI calls, not the business cancellation endpoint.
    for fault in ['deny-cleanup', 'daemon-offline']:
        for filename in ['client-failure.local.json', 'cancel-now', 'cleanup-pending.json', 'cleanup-retried.json']:
            (OUT / 'app' / filename).unlink(missing_ok=True)
        main = app('cleanup-failure', fault)
        lease_file = OUT / 'app/client-failure.local.json'
        until(lambda: lease_file.exists() and lease_file.stat().st_size)
        lease = json.loads(lease_file.read_text())
        worker = until(lambda: run('docker', 'ps', '-q', '--filter', 'label=iwork.lease=' + lease['id']))
        until(lambda: run('docker', 'exec', worker, 'sh', '-c', 'test -f /workspace/child-heartbeat && echo ready || true') == 'ready')
        (OUT / 'faults' / fault).touch()
        (OUT / 'app/cancel-now').touch()
        until(lambda: (OUT / 'app/cleanup-pending.json').exists())
        pending = json.loads((OUT / 'app/cleanup-pending.json').read_text())
        persisted = json.loads((OUT / 'registry' / (lease['id'] + '.json')).read_text())
        assert persisted['state'] == 'CANCELLED' and not persisted['cleaned']
        assert persisted['cleanupState'] == 'RETRY_PENDING' and persisted['processState'] == 'UNKNOWN'
        heartbeats = progression(worker)
        (OUT / 'faults' / fault).unlink()
        code = run('docker', 'wait', main)
        logs = run('docker', 'logs', main)
        assert code == '0', logs
        retried = json.loads((OUT / 'app/cleanup-retried.json').read_text())
        assert not exists(worker)
        REPORT[fault] = {'pending': pending, 'persistedPending': True,
                         'processStillProgressed': heartbeats, 'retried': retried}
    print('Cleanup refusal and manager-to-daemon outage retained pending state, then retried', flush=True)

    # Kill both Java application processes; worker-local changes cannot extend daemon creation time.
    main, lease, worker = slow_task('external-deadline')
    created = json.loads(run('docker', 'inspect', worker))[0]['Created']
    run('docker', 'exec', worker, 'sh', '-c',
        'test ! -e /opt/task-init && test ! -e /var/run/docker.sock && test ! -e /watchdog && echo 9999999999999 > /workspace/deadline')
    before = progression(worker)
    run('docker', 'kill', '--signal=KILL', manager)
    run('docker', 'kill', '--signal=KILL', main)
    after = progression(worker)
    begin = time.monotonic()
    until(lambda: not exists(worker), timeout=24)
    watchdog_logs = run('docker', 'logs', watchdog)
    receipts = [json.loads(line) for line in watchdog_logs.splitlines() if line.startswith('{')]
    receipt = next(r for r in receipts if r['removed'].startswith(worker))
    assert 15000 <= receipt['ageMs'] <= receipt['ageAtConfirmedRemovalMs'] <= 25000, receipt
    assert exists(foreign)
    REPORT['externalDeadline'] = {'childBeforeKill': before, 'childAfterKill': after,
                                  'removedWithoutMainOrManagerAfterSeconds': time.monotonic() - begin,
                                  'dockerCreated': created, 'watchdogReceipt': receipt,
                                  'workerLocalDeadlineTamperIgnored': True, 'workerTimerAbsent': True,
                                  'foreignContainerUntouched': True}
    ready_count = run('docker', 'logs', manager).count('MANAGER_READY')
    dc('up', '-d', 'manager')
    until(lambda: run('docker', 'logs', manager).count('MANAGER_READY') > ready_count)
    dc('run', '--rm', '-T', 'app', 'recovered')

    # Fail closed when the external supervisor is deliberately unavailable.
    count_before = len(list((OUT / 'registry').glob('*.json')))
    dc('stop', 'watchdog')
    time.sleep(2.3)
    dc('run', '--rm', '-T', 'app', 'admission-denied')
    assert count_before == len(list((OUT / 'registry').glob('*.json')))
    old_health = json.loads((OUT / 'watchdog/health.json').read_text())['at']
    dc('start', 'watchdog')
    until(lambda: json.loads((OUT / 'watchdog/health.json').read_text())['at'] > old_health)
    dc('run', '--rm', '-T', 'app', 'admission-restored')
    REPORT['watchdogAdmission'] = {'staleHeartbeatRejected': True, 'noTaskCreatedWhileStopped': True,
                                    'resumedAfterWatchdogRecovery': True}
    assert (OUT / 'app/managed-version.md').read_bytes() == old
    assert not run('docker', 'ps', '-aq', '--filter', 'label=iwork.manager=' + RUN)
    REPORT.update(probePassed=True, productionAccepted=False, remainingTaskContainers=0,
                  scope='live-orphan recovery, late publication, failed cleanup and independent external deadline')
    (OUT / 'result.json').write_text(json.dumps(REPORT, indent=2))
    print('External deadline and fail-closed admission passed; all gap checks complete', flush=True)
finally:
    if watchdog:
        run('docker', 'logs', watchdog, check=False)
    if foreign:
        run('docker', 'rm', '-f', foreign, check=False)
    if manager:
        run('docker', 'logs', manager, check=False)
    for cid in run('docker', 'ps', '-aq', '--filter', 'label=iwork.manager=' + RUN, check=False).splitlines():
        if len(cid) == 12 and all(c in '0123456789abcdef' for c in cid):
            run('docker', 'rm', '-f', cid, check=False)
    for cid in APPS:
        run('docker', 'rm', '-f', cid, check=False)
    dc('down', '--remove-orphans', check=False)
    print('Evidence retained:', OUT, flush=True)
