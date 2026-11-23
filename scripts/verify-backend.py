#!/usr/bin/env python3
"""Run backend integration tests with a disposable, isolated parsing sandbox."""
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
scratch = Path(tempfile.gettempdir()) / "codex"
scratch.mkdir(exist_ok=True)
with socket.socket() as server:
    server.bind(("127.0.0.1", 0))
    port = server.getsockname()[1]
work = Path(tempfile.mkdtemp(prefix="iwork-ci-", dir=scratch))
name = "iwork-ci-" + secrets.token_hex(6)
key = secrets.token_hex(32)
key_file = work / "sandbox.key"
key_file.write_text(key)
# Compose bind-mounts this file into a container without DAC override; work remains private (0700).
key_file.chmod(0o444)
env_file = work / ".env"
env_file.touch(mode=0o600)
env = dict({key: value for key, value in os.environ.items() if not key.startswith("IWORK_")},
           IWORK_SANDBOX_KEY_FILE=str(key_file), IWORK_SANDBOX_INSTANCE=name,
           IWORK_TEST_SANDBOX_PORT=str(port), IWORK_TEST_MANAGER_IMAGE=f"{name}-manager:local",
           IWORK_TEST_PARSER_IMAGE=f"{name}-parser:local", IWORK_DB_PASSWORD=secrets.token_hex(24),
           IWORK_MYSQL_ROOT_PASSWORD=secrets.token_hex(24))
compose = ["docker", "compose", "--env-file", str(env_file), "-p", name,
           "-f", str(root / "compose.yaml"), "-f", str(root / "scripts/compose.test-sandbox.yaml")]


def wait_for_sandbox(request):
    deadline = time.monotonic() + 90
    while True:
        try:
            with urllib.request.urlopen(request, timeout=2) as response:
                if response.status == 200 and json.load(response).get("ready") is True:
                    break
        except (OSError, ValueError, urllib.error.HTTPError):
            pass
        if time.monotonic() >= deadline:
            subprocess.run(compose + ["ps", "-a"], cwd=root, env=env, check=False)
            subprocess.run(compose + ["logs", "--no-color", "--tail", "50", "sandbox-manager", "sandbox-watchdog"],
                           cwd=root, env=env, check=False)
            raise RuntimeError("Sandbox manager and watchdog did not become ready")
        time.sleep(1)


try:
    subprocess.run(compose + ["build", "sandbox-manager", "text-parser-image"], cwd=root, env=env, check=True)
    subprocess.run(compose + ["up", "-d", "sandbox-manager", "sandbox-watchdog"], cwd=root, env=env, check=True)
    url = f"http://127.0.0.1:{port}"
    request = urllib.request.Request(url + "/health", headers={"Authorization": "Bearer " + key})
    wait_for_sandbox(request)
    env.update(IWORK_SANDBOX_TEST_URL=url, IWORK_SANDBOX_TEST_KEY_FILE=str(key_file))
    try:
        subprocess.run([str(root / "backend/mvnw"), "-B", "-f", str(root / "backend/pom.xml"),
                        "-Pdistribution", "-DreuseForks=false", "clean", "verify"], cwd=root, env=env, check=True)
    except subprocess.CalledProcessError:
        subprocess.run(compose + ["logs", "--no-color", "--tail", "100", "sandbox-manager", "sandbox-watchdog"],
                       cwd=root, env=env, check=False)
        raise
    for test in (
        "io.iwork.sandbox.MaterialParsingTest",
        "io.iwork.agent.PersonalAgentTest",
        "io.iwork.conversation.ProjectSpaceTest",
    ):
        report = ET.parse(root / "backend/target/surefire-reports" / f"TEST-{test}.xml").getroot()
        if int(report.get("tests", "0")) == 0 or int(report.get("skipped", "0")) != 0:
            raise RuntimeError(f"{test} did not run fully")
    registry_stats = compose + [
        "exec", "-T", "sandbox-manager", "sh", "-c",
        """for file in /registry/*.json; do
               if grep -q '"cleanup":"CONFIRMED"' "$file" &&
                  ! grep -q '"status":"RUNNING"' "$file"; then
                   stat -c '%n %i %y' "$file"
               fi
           done""",
    ]
    before = subprocess.check_output(registry_stats, cwd=root, env=env, text=True).splitlines()
    assert before, "No sandbox receipts were produced"
    subprocess.run(compose + ["restart", "sandbox-manager"], cwd=root, env=env, check=True)
    wait_for_sandbox(request)
    after = subprocess.check_output(registry_stats, cwd=root, env=env, text=True).splitlines()
    assert set(before).issubset(after), "Sandbox startup rewrote settled receipts"
    print("PASS: settled sandbox receipts survived restart without writes", flush=True)
    print("PASS: all three sandbox-backed integration tests ran", flush=True)
finally:
    cleanup = subprocess.run(compose + ["down", "--volumes", "--remove-orphans"], cwd=root, env=env)
    if cleanup.returncode:
        raise RuntimeError(f"Sandbox cleanup failed; configuration retained at {work}")
    workers = subprocess.check_output(
        ["docker", "ps", "-aq", "--filter", f"label=iwork.sandbox={name}"], text=True
    ).split()
    if workers:
        subprocess.run(["docker", "rm", "-f", *workers], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["docker", "image", "rm", env["IWORK_TEST_MANAGER_IMAGE"], env["IWORK_TEST_PARSER_IMAGE"]],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    shutil.rmtree(work)
