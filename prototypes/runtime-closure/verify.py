"""Run real loopback HTTP and JVM restart checks against the disposable runtime."""
import http.client
import json
import os
from pathlib import Path
import select
import subprocess
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor

HERE = Path(__file__).resolve().parent


def check(condition, detail):
    if not condition:
        raise AssertionError(detail)


def eventually(read, matches):
    until = time.monotonic() + 15
    while time.monotonic() < until:
        value = read()
        if matches(value):
            return value
        time.sleep(.05)
    raise AssertionError(f"condition not reached: {value}")


def verify(root):
    java = ["java", "-cp", f"target/classes{os.pathsep}{Path('target/classpath').read_text().strip()}",
            "RuntimeProbe", str(root)]

    def start():
        process = subprocess.Popen(java, stdout=subprocess.PIPE, text=True)
        try:
            check(select.select([process.stdout], [], [], 10)[0], "JVM did not announce a port")
            return process, int(process.stdout.readline())
        except BaseException:
            stop(process)
            raise

    def request(method, path, expected=200, body=None, user="alice", disconnect=False):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        try:
            conn.request(method, "/tasks" + path, body=json.dumps(body) if body is not None else None,
                         headers={"X-Probe-Token": f"{user}-token", "Connection": "close"})
            if disconnect:
                return
            response = conn.getresponse()
            payload = json.loads(response.read())
            check(response.status == expected, (expected, response.status, payload))
            return payload
        finally:
            conn.close()

    def submit(key, expected=202, user="alice", session="s", **extra):
        return request("POST", "", expected,
                       {"key": key, "session": session, "source": f"{user}-fixture", **extra}, user)

    def get(task, **kwargs):
        return request("GET", "/" + task, **kwargs)

    def action(task, name, **kwargs):
        return request("POST", f"/{task}/{name}", **kwargs)

    def await_task(task, **fields):
        return eventually(lambda: get(task), lambda t: all(t.get(k) == v for k, v in fields.items()))

    process, port = start()
    try:
        page = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        try:
            page.request("GET", "/")
            response = page.getresponse()
            check(response.status == 200 and b"/tasks" in response.read(), "review page unavailable")
        finally:
            page.close()
        request("POST", "", body={"key": "first", "session": "s", "source": "alice-fixture"},
                disconnect=True)
        path = eventually(lambda: list((root / "records").glob("*.json")), bool)[0]
        first = path.stem
        stage = await_task(first, phase="staged")
        check(stage["sdkToolCalls"] == ["read_source", "draft_document"], stage)
        check(sorted(stage["calls"].values()) == [11, 12, 13], stage)
        with ThreadPoolExecutor(max_workers=4) as clients:
            repeated = list(clients.map(lambda _: submit("first", 200)["id"], range(4)))
        check(repeated == [first] * 4, repeated)
        submit("first", 409, source="changed payload")
        submit("busy", 409)
        print("PASS 断开提交连接后进入 staged，四次并发重提均返回同一任务，同会话新请求被拒绝")

        bob = submit("first", user="bob")["id"]
        eventually(lambda: get(bob, user="bob"), lambda t: t["phase"] == "staged")
        check((root / "work" / first / "source.txt").read_text() == "alice-fixture", first)
        check((root / "work" / bob / "source.txt").read_text() == "bob-fixture", bob)
        get(first, expected=404, user="bob")
        action(first, "cancel", expected=404, user="bob")
        action(first, "release")
        action(bob, "release", user="bob")
        original = await_task(first, state="COMPLETED")["document"]
        bob_doc = eventually(lambda: get(bob, user="bob"),
                             lambda t: t["state"] == "COMPLETED")["document"]
        check("alice-fixture" in original and "bob-fixture" not in original, original)
        check("bob-fixture" in bob_doc and "alice-fixture" not in bob_doc, bob_doc)
        get(first, expected=404, user="bob")
        submit("steal", 404, user="bob", base=first)
        print("PASS 不同用户同名会话可并行，输入/工作文件/文档分开，跨用户查看、取消和引用旧版返回 404")

        revise = submit("revise", base=first, source="补充材料")["id"]
        await_task(revise, phase="staged")
        action(revise, "release")
        revised = await_task(revise, state="COMPLETED")["document"]
        check(original in revised and "补充材料" in revised and get(first)["document"] == original, revised)

        late = submit("late", base=revise)["id"]
        await_task(late, phase="staged")
        cancelled = action(late, "cancel")
        check(cancelled["state"] == "CANCELLED" and cancelled["execution"] == "stop-unconfirmed", cancelled)
        action(late, "release")
        rejected = await_task(late, latePublishRejected=True)
        check(rejected["state"] == "CANCELLED" and "document" not in rejected
              and sorted(rejected["calls"].values()) == [11, 12, 13], rejected)

        failed = submit("failed-save", mode="save-fail", base=revise)["id"]
        await_task(failed, phase="staged")
        action(failed, "release")
        failed_save = await_task(failed, state="FAILED")
        check("injected save failure" in failed_save["phase"] and "document" not in failed_save, failed_save)
        completed_after_cancel = action(revise, "cancel")
        check(completed_after_cancel["state"] == "COMPLETED", completed_after_cancel)
        check(get(revise)["document"] == revised and get(first)["document"] == original, "old versions changed")
        print("PASS 保存后可从旧版继续修改，取消后迟到发布被拒，保存失败不产生新版本且原版本完整")

        pending = submit("cancel-model", mode="cancel-model")["id"]
        eventually(lambda: get(pending), lambda t: len(t["calls"]) == 2 and None in t["calls"].values())
        action(pending, "cancel")
        ended = await_task(pending, execution="sdk-subscription-ended", modelSubscriptionCancelled=True)
        check(ended["state"] == "CANCELLED" and "document" not in ended
              and sorted(v for v in ended["calls"].values() if v is not None) == [11]
              and None in ended["calls"].values(), ended)

        failure = submit("model-fail", mode="model-fail")["id"]
        error = await_task(failure, state="FAILED")
        check("injected model failure" in error["phase"] and None in error["calls"].values()
              and sorted(v for v in error["calls"].values() if v is not None) == [11], error)
        print("PASS SDK 用量按任务和调用 ID 归档，失败与取消均保留已知 11 并将未收到的用量记为 null")

        interrupted = submit("restart", base=revise)["id"]
        await_task(interrupted, phase="staged")
        in_model = submit("restart-model", session="other", mode="cancel-model")["id"]
        eventually(lambda: get(in_model), lambda t: len(t["calls"]) == 2 and None in t["calls"].values())
        process.kill()
        process.wait(timeout=5)
        process, port = start()
        restarted = get(interrupted)
        check(restarted["state"] == "INTERRUPTED" and "document" not in restarted
              and sorted(restarted["calls"].values()) == [11, 12, 13], restarted)
        in_model_restarted = get(in_model)
        check(in_model_restarted["state"] == "INTERRUPTED"
              and None in in_model_restarted["calls"].values()
              and "document" not in in_model_restarted, in_model_restarted)
        check(submit("restart", 200, base=revise)["id"] == interrupted, restarted)
        check(get(first)["document"] == original and get(revise)["document"] == revised, "restart lost data")
        print("PASS 强制终止 JVM 后重启标为 INTERRUPTED，重提仍返回原任务，已保存版本和用量可读")
    finally:
        stop(process)


def stop(process):
    if process.poll() is None:
        process.kill()
    process.wait(timeout=5)
    process.stdout.close()


if __name__ == "__main__":
    os.chdir(HERE)
    temp_root = Path(tempfile.gettempdir()) / "codex"
    temp_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="iwork-runtime-", dir=temp_root) as directory:
        verify(Path(directory))
