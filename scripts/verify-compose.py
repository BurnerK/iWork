#!/usr/bin/env python3
"""Exercise a disposable Compose project; never stop an existing iWork instance."""
import http.cookiejar
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
import urllib.parse

root = Path(__file__).resolve().parents[1]
scratch = Path(tempfile.gettempdir()) / "codex"
scratch.mkdir(exist_ok=True)
work = Path(tempfile.mkdtemp(prefix="iwork-smoke-", dir=scratch))
(work / "files").mkdir()
with socket.socket() as port:
    port.bind(("127.0.0.1", 0))
    http_port = port.getsockname()[1]
env = dict(os.environ, IWORK_DATA_DIR=str(work), IWORK_HTTP_PORT=str(http_port),
           IWORK_DB_PASSWORD=secrets.token_hex(24), IWORK_MYSQL_ROOT_PASSWORD=secrets.token_hex(24),
           IWORK_BOOTSTRAP_PASSWORD=secrets.token_urlsafe(24), IWORK_COOKIE_SECURE="false")
key_file = work / "sandbox.key"
key_file.write_text(secrets.token_hex(32))
key_file.chmod(0o444)
env.update(IWORK_SANDBOX_KEY_FILE=str(key_file), IWORK_SANDBOX_INSTANCE=work.name)
env_file = work / ".env"
env_file.touch(mode=0o600)
command = ["docker", "compose", "--env-file", str(env_file), "-f", str(root / "compose.yaml"),
           "-p", work.name]


def compose(*args, capture=False, input=None):
    return subprocess.run(command + list(args), env=env, cwd=root, check=True,
                          text=True, input=input, stdout=subprocess.PIPE if capture else None).stdout


def request(path):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{http_port}{path}", timeout=10) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def health(path, status, body):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            code, content = request(path)
            if code == status and json.loads(content) == {"status": body}:
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError(f"{path} did not reach {status}/{body}")


def sandbox_health():
    manager = compose("ps", "-q", "sandbox-manager", capture=True).strip()
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        result = subprocess.run(
            ["docker", "exec", manager, "sh", "-c",
             'curl -fsS -H "Authorization: Bearer $(cat /run/secrets/sandbox_key)" http://127.0.0.1:8090/health'],
            capture_output=True, text=True)
        try:
            if result.returncode == 0 and json.loads(result.stdout).get("ready") is True:
                return
        except ValueError:
            pass
        time.sleep(1)
    raise AssertionError("Sandbox manager and watchdog did not become ready")


class Browser:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, path, method="GET", body=None):
        headers = {}
        if method != "GET":
            code, csrf = self.request("/api/auth/csrf")
            assert code == 200
            headers["X-CSRF-TOKEN"] = csrf["token"]
            headers["Content-Type"] = "application/x-www-form-urlencoded" if path.endswith("/login") else "application/json"
        data = (urllib.parse.urlencode(body) if path.endswith("/login") else json.dumps(body)).encode() if body is not None else None
        req = urllib.request.Request(f"http://127.0.0.1:{http_port}{path}", data=data, headers=headers, method=method)
        try:
            with self.http.open(req, timeout=20) as response:
                content = response.read()
                if not content:
                    assert response.status == 204
                    return response.status, None
                envelope = json.loads(content)
                assert envelope["success"] is True and envelope["error"] is None
                return response.status, envelope["data"]
        except urllib.error.HTTPError as error:
            envelope = json.loads(error.read())
            assert envelope["success"] is False and envelope["data"] is None
            assert isinstance(envelope["error"], str)
            return error.code, envelope

    def login(self, password):
        code, body = self.request("/api/auth/login", "POST", {"username": "admin", "password": password})
        assert code == 200, code
        return body


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
    compose("up", "-d")
    health("/actuator/health/readiness", 200, "UP")
    sandbox_health()
    app_id = compose("ps", "-q", "app", capture=True).strip()
    manager_id = compose("ps", "-q", "sandbox-manager", capture=True).strip()
    app_info, manager_info = json.loads(subprocess.check_output(["docker", "inspect", app_id, manager_id]))
    assert app_info["Config"]["User"] == "10001:10001"
    assert all(mount["Destination"] in ("/data/files", "/run/secrets/sandbox_key") for mount in app_info["Mounts"])
    assert not manager_info["HostConfig"]["PortBindings"]
    assert all(not any(marker in value for marker in ("DB_PASSWORD", "MODEL_KEY", "MYSQL_ROOT_PASSWORD")) for value in manager_info["Config"]["Env"])
    code, page = request("/")
    assert code == 200 and '<div id="app">' in page
    assert request("/actuator/env")[0] == 403
    for asset in (root / "frontend/public/assets").rglob("*.svg"):
        path = "/" + asset.relative_to(root / "frontend/public").as_posix()
        with urllib.request.urlopen(f"http://127.0.0.1:{http_port}{path}", timeout=5) as response:
            assert response.status == 200
            assert response.headers.get_content_type() == "image/svg+xml"
    assert request("/api/tasks")[0] == 401
    assert migration_count() == "1"
    browser = Browser()
    assert browser.login(env["IWORK_BOOTSTRAP_PASSWORD"])["mustChangePassword"]
    password = secrets.token_urlsafe(24)
    assert browser.request("/api/auth/password", "POST", {"currentPassword": env["IWORK_BOOTSTRAP_PASSWORD"], "newPassword": password})[0] == 204
    assert browser.request("/api/auth/me")[0] == 401
    browser.login(password)
    code, conversation = browser.request("/api/conversations", "POST", {})
    assert code == 201
    conversation_path = "/api/conversations/" + conversation["id"]
    compose("exec", "-T", "app", "sh", "-c", 'printf retained > /data/files/smoke-marker')
    compose("up", "-d", "--force-recreate")
    health("/actuator/health/readiness", 200, "UP")
    sandbox_health()
    assert migration_count() == "1"
    assert compose("exec", "-T", "app", "cat", "/data/files/smoke-marker", capture=True) == "retained"
    assert browser.request("/api/auth/me")[0] == 200
    assert browser.request(conversation_path)[1]["title"] == "新会话"
    # Bootstrap is idempotent: recreation must not replace the changed password.
    assert not Browser().login(password)["mustChangePassword"]
    assert Browser().request("/api/auth/login", "POST", {"username": "admin", "password": env["IWORK_BOOTSTRAP_PASSWORD"]})[0] == 401
    compose("stop", "mysql")
    assert browser.request("/api/auth/me")[0] == 503
    health("/actuator/health/readiness", 503, "DOWN")
    health("/actuator/health/liveness", 200, "UP")
    compose("start", "mysql")
    health("/actuator/health/readiness", 200, "UP")
    assert browser.request(conversation_path)[0] == 200
    compose("stop", "redis")
    assert browser.request("/api/auth/me")[0] == 503
    health("/actuator/health/readiness", 503, "DOWN")
    health("/actuator/health/liveness", 200, "UP")
    compose("start", "redis")
    health("/actuator/health/readiness", 200, "UP")
    assert browser.request("/api/auth/me")[0] == 200
    # Only this disposable project's session store is cleared.
    compose("exec", "-T", "redis", "redis-cli", "FLUSHDB", capture=True)
    assert browser.request("/api/auth/me")[0] == 401
    browser.login(password)
    assert browser.request(conversation_path)[0] == 200
    recovered_password = secrets.token_urlsafe(24)
    compose("exec", "-T", "app", "sh", "-c", "umask 077; cat > /tmp/recovery-password", input=recovered_password)
    compose("exec", "-T", "app", "chmod", "644", "/tmp/recovery-password")
    try:
        compose("exec", "-T", "-e", "IWORK_RECOVERY_PASSWORD_FILE=/tmp/recovery-password", "app", "java", "-jar", "/app/iwork.jar", "--recover-admin", capture=True)
        raise AssertionError("Recovery accepted a world-readable secret file")
    except subprocess.CalledProcessError:
        pass
    assert browser.request("/api/auth/me")[0] == 200
    compose("exec", "-T", "app", "chmod", "600", "/tmp/recovery-password")
    compose("exec", "-T", "-e", "IWORK_RECOVERY_PASSWORD_FILE=/tmp/recovery-password", "app", "java", "-jar", "/app/iwork.jar", "--recover-admin", capture=True)
    compose("exec", "-T", "app", "rm", "/tmp/recovery-password")
    assert browser.request("/api/auth/me")[0] == 401
    assert browser.login(recovered_password)["mustChangePassword"]
    assert browser.request("/api/conversations")[0] == 403
    logs = compose("logs", "--no-color", capture=True)
    assert all(secret not in logs for secret in [env["IWORK_DB_PASSWORD"], env["IWORK_MYSQL_ROOT_PASSWORD"], env["IWORK_BOOTSTRAP_PASSWORD"], password, recovered_password])
    (work / "result.txt").write_text("PASS: entry, migrations, bootstrap idempotence, app/Redis recreation with login retention, conversations, file retention, MySQL/Redis outage and recovery, Redis loss, admin recovery and revocation, log secrets\n")
    print("PASS: Compose identity, persistence, dependency failures and administrator recovery", flush=True)
finally:
    compose("down", "--volumes", "--timeout", "15")
