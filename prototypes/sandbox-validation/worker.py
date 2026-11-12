"""Fixed synthetic converter and fault fixtures; never accepts code from a model."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

root = Path('/workspace')
mode = sys.argv[1]
# Fixed failure fixtures simulate a parser stuck or failing on particular input.
if mode == 'convert':
    fixture = (root / 'source.txt').read_text()
    mode = {'__IW8_SLOW_FIXTURE__': 'heartbeat', '__IW8_FAIL_FIXTURE__': 'fail'}.get(fixture, mode)
if mode == 'convert':
    body = (root / 'source.txt').read_text()
    (root / 'draft.md').write_text('# Synthetic document\n\n' + body)
    print(json.dumps({'hostname': socket.gethostname(), 'pid': os.getpid(),
                      'uid': os.getuid(), 'body': body}))
elif mode == 'heartbeat':
    # A child process makes checking only the exec client's PID insufficient.
    child = subprocess.Popen([sys.executable, __file__, 'child'])
    (root / 'pids.json').write_text(json.dumps([os.getpid(), child.pid]))
    for i in range(600):
        (root / 'heartbeat').write_text(str(i))
        time.sleep(.1)
    child.wait()
elif mode == 'child':
    for i in range(600):
        (root / 'child-heartbeat').write_text(str(i))
        time.sleep(.1)
elif mode == 'fail':
    (root / 'draft.md').write_text('PARTIAL MUST NOT PUBLISH')
    sys.exit(23)
elif mode == 'memory':
    blocks = []
    while True:
        blocks.append(bytearray(8 * 1024 * 1024))
elif mode == 'audit':
    checks = {}
    for p in ['/evidence/private.txt', '/var/run/docker.sock', '/app',
              '/workspace/other-task/source.txt', '/workspace/../other-task/source.txt']:
        try:
            Path(p).read_bytes()
            checks[p] = False
        except OSError:
            checks[p] = True
    checks['fake_credentials_absent'] = not any(k in os.environ for k in ['DB_PASSWORD', 'MODEL_API_KEY'])
    checks['nonroot'] = os.getuid() == 1000
    status = Path('/proc/self/status').read_text()
    checks['no_new_privs'] = 'NoNewPrivs:\t1' in status
    checks['no_caps'] = 'CapEff:\t0000000000000000' in status
    checks['seccomp'] = 'Seccomp:\t2' in status
    try:
        Path('/usr/local/should-not-write').write_text('x')
        checks['readonly_root'] = False
    except OSError:
        checks['readonly_root'] = True
    try:
        socket.create_connection(('192.0.2.1', 443), timeout=.5).close()
        checks['no_egress'] = False
    except OSError:
        checks['no_egress'] = True
    cg = Path('/sys/fs/cgroup')
    limits = {k: (cg / k).read_text().strip() for k in ['memory.max', 'cpu.max', 'pids.max']}
    checks['limits'] = limits == {'memory.max': '67108864', 'cpu.max': '50000 100000', 'pids.max': '32'}
    before = (cg / 'cpu.stat').read_text()
    end = time.monotonic() + 1.5
    while time.monotonic() < end:
        pass
    after = (cg / 'cpu.stat').read_text()
    stat = lambda s: dict(line.split() for line in s.splitlines())
    checks['cpu_throttled'] = int(stat(after)['nr_throttled']) > int(stat(before)['nr_throttled'])
    children = []
    try:
        for _ in range(40):
            children.append(subprocess.Popen(['sleep', '10']))
    except BlockingIOError:
        checks['pids_enforced'] = True
    finally:
        for child in children:
            child.terminate()
        for child in children:
            child.wait()
    checks.setdefault('pids_enforced', False)
    print(json.dumps({'checks': checks, 'limits': limits, 'cpu_before': before,
                      'cpu_after': after, 'children_before_rejection': len(children)}))
    assert all(checks.values()), checks
else:
    raise ValueError(mode)
