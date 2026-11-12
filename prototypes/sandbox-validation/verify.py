#!/usr/bin/env python3
"""Run only this probe's resources. Requires local Docker socket, JDK 21, Maven, Python 3."""
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
OUT = Path(tempfile.mkdtemp(prefix='iwork-sandbox-', dir=TEMP))
RUN = 'iw8-' + uuid.uuid4().hex[:12]
ENV = dict(os.environ, PROBE_RUN=RUN, PROBE_EVIDENCE=str(OUT))
COMMANDS = []


def run(*args, check=True, timeout=180):
    result = subprocess.run(args, cwd=HERE, env=ENV, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    COMMANDS.append({'argv': list(args), 'returncode': result.returncode, 'output': result.stdout})
    (OUT / 'commands.json').write_text(json.dumps(COMMANDS, indent=2))
    if check and result.returncode:
        raise RuntimeError('command failed: ' + repr(args) + '\n' + result.stdout)
    return result.stdout.strip()


def dc(*args, **kwargs):
    return run('docker', 'compose', '-p', RUN, *args, **kwargs)


def wait_until(test, timeout=20):
    deadline = time.monotonic() + timeout
    while True:
        value = test()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise AssertionError('condition timed out')
        time.sleep(.2)


def progression(container):
    def tick(filename):
        return int(run('docker', 'exec', container, 'cat', '/workspace/' + filename))
    first = [tick('heartbeat'), tick('child-heartbeat')]
    time.sleep(.4)
    second = [tick('heartbeat'), tick('child-heartbeat')]
    assert all(b > a for a, b in zip(first, second)), (first, second)
    return {'before': first, 'after': second, 'top': run('docker', 'top', container)}


print('Evidence:', OUT, flush=True)
app = None
try:
    context = json.loads(run('docker', 'context', 'inspect'))[0]
    endpoint = os.environ.get('DOCKER_HOST', context['Endpoints']['docker']['Host'])
    assert endpoint.startswith('unix://'), 'probe requires an explicitly local Unix Docker socket'
    ENV['DOCKER_SOCKET'] = endpoint.removeprefix('unix://')
    run('mvn', '-s', 'settings.xml', '-q', 'compile',
        'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy-dependencies', '-DoutputDirectory=target/lib')
    run('docker', 'build', '-f', 'Dockerfile.worker', '-t', 'iwork-sandbox-worker:local', '.')
    dc('build', 'probe')
    images = json.loads(run('docker', 'image', 'inspect', 'iwork-sandbox-worker:local',
                           'iwork-sandbox-probe:local', 'python:3.13-alpine', 'docker:29-cli', 'eclipse-temurin:21-jre'))
    metadata = {'run': RUN, 'host': platform.platform(), 'compose': run('docker', 'compose', 'version'),
                'docker': run('docker', 'version'), 'images': [
                    {k: image[k] for k in ['Id', 'RepoTags', 'RepoDigests', 'Architecture', 'Os']} for image in images],
                'sourceHashes': {str(p.relative_to(HERE)): hashlib.sha256(p.read_bytes()).hexdigest()
                                 for p in sorted(HERE.rglob('*')) if p.is_file() and
                                 'target' not in p.parts and 'evidence' not in p.parts and '__pycache__' not in p.parts}}
    (OUT / 'environment.json').write_text(json.dumps(metadata, indent=2))
    dc('run', '--rm', '-T', 'probe', 'suite')
    print('SDK/tool/isolation/lifecycle suite passed', flush=True)
    old = (OUT / 'version.md').read_bytes()
    app = dc('run', '-d', '--name', RUN + '-app', 'probe', 'crash').splitlines()[-1]
    wait_until(lambda: (OUT / 'crash.json').exists() and (OUT / 'crash.json').stat().st_size > 0)
    state = json.loads((OUT / 'crash.json').read_text())
    container = state['container']
    before = progression(container)
    run('docker', 'kill', '--signal=KILL', app)
    assert run('docker', 'wait', app) == '137'
    after = progression(container)
    assert (OUT / 'version.md').read_bytes() == old
    (OUT / 'crash-observation.json').write_text(json.dumps(
        {'mainExit': 137, 'beforeKill': before, 'afterKill': after,
         'containerSurvivedMain': True, 'oldVersionPreserved': True}, indent=2))
    dc('run', '--rm', '-T', 'probe', 'recover')
    recovered = json.loads((OUT / 'recovery.json').read_text())
    assert recovered['state'] == 'INTERRUPTED' and not recovered['autoResumed']
    assert (OUT / 'version.md').read_bytes() == old
    assert not run('docker', 'ps', '-aq', '--filter', 'label=iwork.probe=' + RUN)
    (OUT / 'result.json').write_text(json.dumps({'probePassed': True, 'productionAccepted': False,
        'conclusion': 'requires management-permission decision and explicit lifecycle cleanup',
        'remainingTaskContainers': 0}, indent=2))
    print('Crash/restart/cleanup passed; architectural decision still required', flush=True)
finally:
    # Exact run label only; never prune or remove other projects' resources.
    for cid in run('docker', 'ps', '-aq', '--filter', 'label=iwork.probe=' + RUN, check=False).splitlines():
        if len(cid) == 12 and all(c in '0123456789abcdef' for c in cid):
            run('docker', 'rm', '-f', cid, check=False)
    if app:
        run('docker', 'rm', '-f', app, check=False)
    dc('down', '--remove-orphans', check=False)
    print('Evidence retained:', OUT, flush=True)
