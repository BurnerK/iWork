#!/usr/bin/env python3
"""Exercise a disposable Compose project; never stop an existing iWork instance."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

root = Path(__file__).resolve().parents[1]
scratch = Path(tempfile.gettempdir()) / "codex"
scratch.mkdir(exist_ok=True)
work = Path(tempfile.mkdtemp(prefix="iwork-smoke-", dir=scratch))
(work / "files").mkdir()
with socket.socket() as port:
    port.bind(("127.0.0.1", 0))
    http_port = port.getsockname()[1]
env = dict(os.environ, IWORK_DATA_DIR=str(work), IWORK_HTTP_PORT=str(http_port),
           IWORK_DB_PASSWORD=secrets.token_hex(24), IWORK_MYSQL_ROOT_PASSWORD=secrets.token_hex(24))
env_file = work / ".env"
env_file.touch(mode=0o600)
command = ["docker", "compose", "--env-file", str(env_file), "-f", str(root / "compose.yaml"),
           "-p", work.name]


def compose(*args, capture=False):
    return subprocess.run(command + list(args), env=env, cwd=root, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None).stdout


def request(path):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{http_port}{path}", timeout=10) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def health(path, status, body):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            code, content = request(path)
            if code == status and json.loads(content) == {"status": body}:
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError(f"{path} did not reach {status}/{body}")


def migration_count():
    return compose("exec", "-T", "mysql", "sh", "-c",
                   'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u iwork -D iwork -N -e '
                   "'SELECT COUNT(*) FROM flyway_schema_history WHERE version = 1 AND success = 1'",
                   capture=True).strip()


print(f"Isolated project: {work.name}; evidence directory: {work}", flush=True)
try:
    compose("build")
    compose("run", "--rm", "--no-deps", "--user", "0", "--cap-add", "CHOWN", "--entrypoint", "chown",
            "app", "10001:10001", "/data/files")
    compose("up", "-d", "--wait", "--wait-timeout", "180")
    health("/actuator/health/readiness", 200, "UP")
    code, page = request("/")
    assert code == 200 and '<div id="app">' in page
    assert request("/actuator/env")[0] == 403
    assert request("/api/tasks")[0] == 403
    assert migration_count() == "1"
    compose("exec", "-T", "app", "sh", "-c", 'printf retained > /data/files/smoke-marker')
    compose("up", "-d", "--force-recreate", "--wait", "--wait-timeout", "180")
    assert migration_count() == "1"
    assert compose("exec", "-T", "app", "cat", "/data/files/smoke-marker", capture=True) == "retained"
    compose("stop", "mysql")
    health("/actuator/health/readiness", 503, "DOWN")
    health("/actuator/health/liveness", 200, "UP")
    compose("start", "mysql")
    health("/actuator/health/readiness", 200, "UP")
    logs = compose("logs", "--no-color", capture=True)
    assert env["IWORK_DB_PASSWORD"] not in logs and env["IWORK_MYSQL_ROOT_PASSWORD"] not in logs
    (work / "result.txt").write_text("PASS: entry, migration, recreation, file retention, database outage/recovery, log secrets\n")
    print("PASS: Compose startup, migration, persistence and database outage/recovery", flush=True)
finally:
    compose("down", "--timeout", "15")
