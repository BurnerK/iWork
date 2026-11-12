"""Explicit, bounded live run. Reads model.local.json; never prints its contents."""
import http.client
import json
import os
from pathlib import Path
import select
import subprocess
import tempfile
import time
import argparse

HERE = Path(__file__).resolve().parent


def verify():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resume-save-failure", type=Path, metavar="EVIDENCE_DIR")
    args = parser.parse_args()
    os.chdir(HERE)
    config = HERE / "model.local.json"
    settings = json.loads(config.read_text())
    assert all(isinstance(settings.get(k), str) and settings[k].strip()
               for k in ("baseUrl", "model", "apiKey")), "Incomplete local model configuration"
    parent = Path(tempfile.gettempdir()) / "codex"
    parent.mkdir(exist_ok=True)
    resume = args.resume_save_failure is not None
    root = args.resume_save_failure.resolve() if resume else Path(tempfile.mkdtemp(prefix="iwork-real-model-", dir=parent))
    prior_report = json.loads((root / "report.json").read_text()) if resume else None
    if resume:
        assert all(name in prior_report["cases"] and "taskId" not in prior_report["cases"][name]
                   for name in ("baseline", "cancel-before-content", "cancel-during-output",
                                "cancel-before-save", "client-stream-failure")), "Incomplete prior scenarios"
    log = (root / "jvm.log").open("a")
    process = subprocess.Popen(["java", "-cp", "target/classes:" + Path("target/classpath").read_text().strip(),
                                "RuntimeProbe", str(root), str(config)],
                               stdout=subprocess.PIPE, stderr=log, text=True)
    report = {"model": settings["model"], "sdk": "2.0.4", "maxHttpRequests": 16,
              "maxOutputTokensPerRequest": 512, "cases": {},
              "providerExecutionAfterCancel": "unverified", "providerBilling": "unverified"}
    if resume:
        report = prior_report
    try:
        assert select.select([process.stdout], [], [], 15)[0], "JVM startup timeout"
        port = int(process.stdout.readline())

        def request(method, suffix, body=None):
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            try:
                conn.request(method, "/tasks" + suffix,
                             json.dumps(body) if body is not None else None,
                             {"X-Probe-Token": "alice-token"})
                response = conn.getresponse()
                data = json.loads(response.read())
                assert response.status in (200, 202), f"HTTP {response.status}"
                return data
            finally:
                conn.close()

        def get(task):
            return request("GET", "/" + task)

        def wait(task, predicate, timeout=125):
            until = time.monotonic() + timeout
            while time.monotonic() < until:
                value = get(task)
                if predicate(value):
                    return value
                if value["state"] in ("FAILED", "INTERRUPTED"):
                    raise AssertionError(f"Task failed: {value.get('phase')}")
                time.sleep(.02)
            raise AssertionError("Task observation timeout")

        def action(task, name):
            return request("POST", "/" + task + "/" + name)

        def submit(name, mode="normal", base=None):
            body = {"key": name, "session": name, "source":
                    "合成测试资料：iWork 内部试用反馈。三位测试者希望关闭网页后任务继续，"
                    "取消后旧版文档保留，用量缺失明确标注。请整理成三段简短中文文档。",
                    "mode": mode}
            if base:
                body["base"] = base
            task = request("POST", "", body)["id"]
            report["cases"][name] = {"taskId": task}
            return task

        def capture(name, task):
            value = wait(task, lambda t: t["state"] != "RUNNING" and
                         all("transportEndedAt" in a and "modelEndedAt" in a
                             for a in t.get("attempts", {}).values()), timeout=15)
            # Request content is synthetic; omit it to keep review evidence compact.
            value.pop("request", None)
            report["cases"][name] = value
            (root / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(name, value["state"], "requests", len(value.get("attempts", {})),
                  "knownUsage", sum(n for n in value["calls"].values() if n is not None),
                  "missingUsage", sum(n is None for n in value["calls"].values()), flush=True)
            return value

        if resume:
            completed = report["cases"]["baseline"]
            baseline = completed["id"]
        else:
            baseline = submit("baseline")
            wait(baseline, lambda t: t["phase"] == "staged")
            action(baseline, "release")
            completed = capture("baseline", baseline)
            assert completed["state"] == "COMPLETED" and completed.get("document"), "Baseline not saved"
            assert "read_source" in completed["sdkToolCalls"] and "draft_document" in completed["sdkToolCalls"]
            for attempt in completed["attempts"].values():
                assert attempt["providerUsage"] is not None, "Baseline raw usage missing"
                assert attempt["adapterUsage"] is not None, "Baseline adapter usage missing"
                assert attempt["providerUsage"]["total_tokens"] == attempt["adapterUsage"]["totalTokens"]
                assert completed["calls"][attempt["sdkReplyId"]] == attempt["providerUsage"]["total_tokens"]

            for name, field in (("cancel-before-content", "httpStartedAt"), ("cancel-during-output", "firstContentAt")):
                task = submit(name)
                wait(task, lambda t: any(field in a for a in t.get("attempts", {}).values()))
                action(task, "cancel")
                cancelled = capture(name, task)
                assert cancelled["state"] == "CANCELLED" and "document" not in cancelled
                assert any(a.get("modelSignal") == "CANCEL" for a in cancelled["attempts"].values()), "Cancellation missed active model"
                if name == "cancel-before-content":
                    assert not any(a.get("firstContentAt", float("inf")) <= cancelled["cancelAcceptedAt"]
                                   for a in cancelled["attempts"].values()), "Before-content timing missed"

            task = submit("cancel-before-save", base=baseline)
            wait(task, lambda t: t["phase"] == "staged")
            action(task, "cancel")
            action(task, "release")
            wait(task, lambda t: t.get("latePublishRejected") is True)
            cancelled = capture("cancel-before-save", task)
            assert cancelled["state"] == "CANCELLED" and "document" not in cancelled
            assert get(baseline)["document"] == completed["document"], "Old version changed"

            task = submit("client-stream-failure", mode="stream-fail")
            wait(task, lambda t: t["state"] == "FAILED")
            failed = capture("client-stream-failure", task)
            assert "document" not in failed and any(v is None for v in failed["calls"].values())

        save_case = "save-failure-retry" if resume else "save-failure"
        task = submit(save_case, mode="save-fail", base=baseline)
        wait(task, lambda t: t["phase"] == "staged")
        action(task, "release")
        wait(task, lambda t: t["state"] == "FAILED")
        failed = capture(save_case, task)
        assert "document" not in failed and get(baseline)["document"] == completed["document"]
        report["checks"] = "passed-after-targeted-retry" if resume else "passed"
        report.pop("failure", None)
    except Exception as error:
        report["checks"] = "incomplete"
        report["failure"] = type(error).__name__
        raise
    finally:
        process.kill()
        process.wait(timeout=10)
        process.stdout.close()
        log.close()
        # Preserve partial task evidence even when a check fails.
        for name, value in list(report["cases"].items()):
            if "taskId" in value:
                record = root / "records" / (value["taskId"] + ".json")
                if record.exists():
                    task = json.loads(record.read_text())
                    task.pop("request", None)
                    report["cases"][name] = task
        (root / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print("Evidence:", root / "report.json", flush=True)


if __name__ == "__main__":
    verify()
