import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Throwaway integration probe for #8; synthetic identities and fixed commands only. */
public final class SandboxProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EVIDENCE = Path.of("/evidence");
    static final String RUN = System.getenv("PROBE_RUN");
    static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    static final List<Task> TASKS = new ArrayList<>();

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static void record(String name, Object evidence) throws Exception {
        RESULTS.add(Map.of("name", name, "evidence", evidence));
        JSON.writerWithDefaultPrettyPrinter().writeValue(EVIDENCE.resolve("suite.json").toFile(), RESULTS);
        System.out.println("PASS " + name);
    }
    static String cli(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { return new String(p.getInputStream().readAllBytes()); }
            catch (IOException e) { throw new CompletionException(e); }
        });
        if (!p.waitFor(40, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("CLI timeout: " + command[0]);
        }
        String text = output.get(5, TimeUnit.SECONDS).trim();
        if (p.exitValue() != 0) throw new IOException(text);
        return text;
    }
    static JsonNode inspect(Task t) throws Exception {
        return JSON.readTree(cli("docker", "inspect", t.id())).get(0);
    }
    static boolean exists(String id) throws Exception {
        return !cli("docker", "ps", "-aq", "--filter", "id=" + id).isBlank();
    }
    static void eventually(Callable<Boolean> test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!test.call()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition timed out");
            Thread.sleep(100);
        }
    }
    static Task task(String name, String owner, String source) throws Exception {
        Task t = new Task(name, owner);
        TASKS.add(t);
        t.sandbox.start();
        byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(archive)) {
            var entry = new TarArchiveEntry("source.txt");
            entry.setSize(bytes.length); entry.setMode(0444);
            tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry();
        }
        t.sandbox.hydrateWorkspace(new ByteArrayInputStream(archive.toByteArray()));
        return t;
    }
    static String draft(Task t) throws Exception {
        try (var tar = new TarArchiveInputStream(t.sandbox.persistWorkspace())) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.getName().equals("./draft.md")) {
                    check(e.isFile() && !e.isSymbolicLink() && !e.isLink() && e.getSize() <= 20000, "unsafe draft");
                    return new String(tar.readNBytes((int)e.getSize()), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("missing draft");
    }
    static final class Task {
        final String name, owner;
        final DockerSandboxState config = new DockerSandboxState();
        final DockerSandbox sandbox;
        String state = "RUNNING";
        Task(String name, String owner) {
            this.name = name; this.owner = owner;
            config.setSessionId(RUN + "-" + name);
            config.setWorkspaceRoot("/workspace");
            config.setWorkspaceSpec(new WorkspaceSpec());
            config.setImage("iwork-sandbox-worker:local");
            config.setMemorySizeBytes(64L * 1024 * 1024);
            // Leave network unset to test the actual SDK default.
            config.setAdditionalRunArgs(List.of("--label=iwork.probe=" + RUN,
                    "--label=iwork.task=" + name, "--user=1000:1000", "--read-only",
                    "--cap-drop=ALL", "--security-opt=no-new-privileges", "--pids-limit=32",
                    "--cpus=0.5", "--memory-swap=67108864",
                    "--tmpfs=/workspace:rw,noexec,nosuid,size=8m,uid=1000,gid=1000,mode=0700",
                    "--tmpfs=/tmp/codex:rw,noexec,nosuid,size=4m,uid=1000,gid=1000,mode=0700"));
            sandbox = new DockerSandbox(config);
        }
        String id() { return config.getContainerId(); }
        String exec(String command, int timeout) throws Exception { return sandbox.exec(null, command, timeout).stdout().trim(); }
        void authorize(String caller) {
            if (!owner.equals(caller)) throw new SecurityException("not found");
        }
        synchronized boolean publish(String body, boolean fail) throws Exception {
            if (!state.equals("RUNNING")) return false;
            if (fail) { state = "FAILED"; throw new IOException("injected save failure"); }
            // shortcut: single-JVM file commit, production must use the agreed DB task/version boundary.
            Path stage = EVIDENCE.resolve("version.next");
            Files.writeString(stage, body);
            Files.move(stage, EVIDENCE.resolve("version.md"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = "COMPLETED";
            return true;
        }
        synchronized void cancel() { if (state.equals("RUNNING")) state = "CANCELLED"; }
        void remove() throws Exception {
            sandbox.shutdown();
            eventually(() -> !exists(id()));
        }
    }
    public static final class Converter {
        final Task task;
        Converter(Task task) { this.task = task; }
        @Tool(name="convert_document", description="Convert this task's authorized synthetic source to a draft")
        public String convert() throws Exception { return task.exec("python /opt/worker.py convert", 10); }
    }
    static FutureTask<String> heartbeat(Task t) throws Exception {
        FutureTask<String> work = new FutureTask<>(() -> t.exec("python /opt/worker.py heartbeat", 60));
        Thread.ofVirtual().start(work);
        eventually(() -> t.exec("test -f pids.json && test -f child-heartbeat && echo ready || true", 3).equals("ready"));
        return work;
    }
    static Map<String, Object> progression(Task t) throws Exception {
        String pids = t.exec("cat pids.json", 3);
        int first = Integer.parseInt(t.exec("cat heartbeat", 3));
        int childFirst = Integer.parseInt(t.exec("cat child-heartbeat", 3));
        Thread.sleep(350);
        int second = Integer.parseInt(t.exec("cat heartbeat", 3));
        int childSecond = Integer.parseInt(t.exec("cat child-heartbeat", 3));
        check(second > first && childSecond > childFirst, "both container processes must still be progressing");
        return Map.of("pids", JSON.readTree(pids), "parent", List.of(first, second), "child", List.of(childFirst, childSecond),
                "top", cli("docker", "top", t.id()));
    }
    static void suite() throws Exception {
        Files.writeString(EVIDENCE.resolve("private.txt"), "synthetic-private-main-only");
        Files.writeString(EVIDENCE.resolve("version.md"), "OLD VERSION");
        record("versions", Map.of("java", System.getProperty("java.version"), "docker", cli("docker", "version"),
                "sdk", "2.0.4", "os", cli("uname", "-a")));
        Task alice = task("alice-a", "alice", "ALICE_A_ONLY");
        try {
            alice.sandbox.uploadFile("cp-probe.txt", "synthetic".getBytes());
            throw new AssertionError("docker cp unexpectedly accepted read-only rootfs; re-evaluate transfer finding");
        } catch (SandboxException.SandboxRuntimeException expected) {
            check(expected.getMessage().contains("read-only"), "unexpected upload error");
            record("sdk-upload-readonly-root-failure", Map.of("exception", expected.toString(), "workingAlternative", "hydrateWorkspace/persistWorkspace"));
        }
        Task bob = task("bob-b", "bob", "BOB_B_ONLY");
        Task alice2 = task("alice-c", "alice", "ALICE_C_ONLY");
        for (Task t : List.of(alice, bob, alice2)) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new Converter(t));
            check(toolkit.getToolSchemas().size() == 1 && toolkit.getToolSchemas().get(0).getName().equals("convert_document"), "only controlled tool");
            var reply = toolkit.callTool(ToolCallParam.builder().toolUseBlock(new ToolUseBlock("convert", "convert_document", Map.of(), "{}", null)).build()).block(Duration.ofSeconds(20));
            check(reply != null && reply.getState() != io.agentscope.core.message.ToolResultState.ERROR, "SDK tool failed: " + JSON.writeValueAsString(reply));
            String draft = draft(t);
            String expected = t == alice ? "ALICE_A_ONLY" : t == bob ? "BOB_B_ONLY" : "ALICE_C_ONLY";
            check(draft.equals("# Synthetic document\n\n" + expected), "task data crossed");
            record("controlled-tool-" + t.name, Map.of("draft", draft, "sdkReply", JSON.valueToTree(reply), "container", t.id()));
        }
        try { alice.authorize("bob"); throw new AssertionError("foreign identity allowed"); }
        catch (SecurityException expected) { /* synthetic authorization boundary */ }
        for (String path : List.of("../source.txt", "/evidence/private.txt", "../bob-b/source.txt", "../alice-c/source.txt", "..\\source.txt")) {
            try { alice.sandbox.downloadFile(path); throw new AssertionError("traversal accepted: " + path); }
            catch (IllegalArgumentException expected) { /* SDK lexical path boundary */ }
        }
        var unknown = new Toolkit();
        unknown.registerTool(new Converter(alice));
        var denied = unknown.callTool(ToolCallParam.builder().toolUseBlock(new ToolUseBlock("shell", "shell", Map.of("command", "id"))).build()).block(Duration.ofSeconds(5));
        check(denied != null && denied.getState() == io.agentscope.core.message.ToolResultState.ERROR, "unregistered shell accepted");
        record("authorization-and-tool-denials", Map.of("foreignIdentity", "denied", "pathTraversal", "denied", "shell", JSON.valueToTree(denied)));
        alice2.exec("rm draft.md && ln -s /etc/passwd draft.md", 3);
        try { draft(alice2); throw new AssertionError("symlink export accepted"); }
        catch (AssertionError expected) { check(expected.getMessage().equals("unsafe draft"), "wrong symlink failure"); }
        record("symlink-export-denied", Map.of("appValidation", "only regular non-link draft <=20000 bytes"));
        JsonNode config = inspect(alice);
        check(config.path("HostConfig").path("NetworkMode").asText().equals("none"), "SDK network default");
        check(config.path("Mounts").isEmpty(), "unexpected host mounts");
        JsonNode audit = JSON.readTree(alice.exec("python /opt/worker.py audit", 10));
        record("container-isolation", Map.of("audit", audit, "hostConfig", config.path("HostConfig"), "mounts", config.path("Mounts")));
        try { alice.exec("python /opt/worker.py memory", 10); throw new AssertionError("memory not enforced"); }
        catch (SandboxException.ExecException expected) {
            check(expected.getMessage().contains("137"), "unexpected memory failure: " + expected);
            String events = alice.exec("cat /sys/fs/cgroup/memory.events", 3);
            check(!events.contains("oom_kill 0\n"), "no OOM kill recorded");
            record("memory-enforced", Map.of("exception", expected.toString(), "events", events));
        }
        check(alice.publish(draft(alice), false), "normal publish");
        String old = Files.readString(EVIDENCE.resolve("version.md"));
        alice.remove(); bob.remove(); alice2.remove();
        record("normal-cleanup", Map.of("containersRemoved", true, "workspace", "container tmpfs destroyed", "published", old));

        Task stop = task("stop-only", "alice", "stop");
        var stopWork = heartbeat(stop);
        stop.sandbox.stop();
        check(!stop.sandbox.isRunning(), "SDK should report stopped");
        record("sdk-stop-keeps-processing", progression(stop));
        stop.remove();
        try { stopWork.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException expected) { check(expected.getCause() instanceof SandboxException.ExecException, "unexpected stop failure"); }
        record("sdk-shutdown-removes-processes", Map.of("containerRemoved", true));
    }
    static void lifecycle() throws Exception {
        String old = Files.readString(EVIDENCE.resolve("version.md"));
        Task cancel = task("cancel", "alice", "cancel");
        var work = heartbeat(cancel);
        cancel.cancel();
        work.cancel(true);
        record("interrupt-alone-keeps-processing", progression(cancel));
        check(!cancel.publish("LATE", false), "late result published");
        long begin = System.nanoTime();
        cancel.remove();
        record("cancel-with-shutdown", Map.of("removed", true, "elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin), "latePublishRejected", true));

        Task timeout = task("timeout", "alice", "timeout");
        try { timeout.exec("python /opt/worker.py heartbeat", 1); throw new AssertionError("timeout missing"); }
        catch (SandboxException.ExecTimeoutException expected) {
            timeout.state = "FAILED";
            record("sdk-timeout-keeps-processing", progression(timeout));
        }
        check(!timeout.publish("LATE", false), "timeout published");
        timeout.remove();
        record("timeout-with-shutdown", Map.of("removed", true, "latePublishRejected", true));

        Task fail = task("processor-failure", "alice", "failure");
        try { fail.exec("python /opt/worker.py fail", 5); throw new AssertionError("failure missing"); }
        catch (SandboxException.ExecException expected) { fail.state = "FAILED"; }
        check(!fail.publish("PARTIAL", false), "failed task published");
        fail.remove();
        Task save = task("save-failure", "alice", "save");
        new Converter(save).convert();
        try { save.publish("NEW", true); throw new AssertionError("save failure missing"); }
        catch (IOException expected) { check(save.state.equals("FAILED"), "save state"); }
        save.remove();
        check(Files.readString(EVIDENCE.resolve("version.md")).equals(old), "old version changed");
        record("failures-preserve-version", Map.of("processorExit", 23, "saveFailureInjected", true, "oldVersion", old));
    }
    static void crash() throws Exception {
        Task t = task("crash", "alice", "crash");
        var work = heartbeat(t);
        JSON.writerWithDefaultPrettyPrinter().writeValue(EVIDENCE.resolve("crash.json").toFile(),
                Map.of("state", "RUNNING", "container", t.id(), "run", RUN));
        System.out.println("CRASH_READY " + t.id());
        System.out.flush();
        work.get();
    }
    static void recover() throws Exception {
        JsonNode state = JSON.readTree(EVIDENCE.resolve("crash.json").toFile());
        check(state.path("run").asText().equals(RUN), "recovery run mismatch");
        String id = state.path("container").asText();
        JsonNode config = JSON.readTree(cli("docker", "inspect", id)).get(0);
        check(config.path("Config").path("Labels").path("iwork.probe").asText().equals(RUN), "refuse foreign cleanup");
        cli("docker", "rm", "-f", id);
        check(!exists(id), "orphan remains");
        JSON.writerWithDefaultPrettyPrinter().writeValue(EVIDENCE.resolve("recovery.json").toFile(),
                Map.of("state", "INTERRUPTED", "removed", id, "autoResumed", false,
                        "preservedVersion", Files.readString(EVIDENCE.resolve("version.md"))));
        System.out.println("RECOVERY_OK");
    }
    public static void main(String[] args) throws Exception {
        check(RUN != null && RUN.matches("[a-z0-9-]{1,60}"), "invalid run ID");
        switch (args[0]) {
            case "crash" -> crash(); // SIGKILL deliberately bypasses finally/shutdown hooks.
            case "recover" -> recover();
            case "suite" -> {
                try { suite(); lifecycle(); }
                finally {
                    for (Task t : TASKS) if (t.id() != null && exists(t.id())) t.remove();
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
