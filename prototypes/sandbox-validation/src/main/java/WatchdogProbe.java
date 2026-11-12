import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** External task deadline; no HTTP endpoint, model input or business credentials. */
public final class WatchdogProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path HEALTH = Path.of("/watchdog/health.json");
    static final long TTL_SECONDS = 15;
    static String docker(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/usr/local/bin/docker"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try { return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IOException error) { throw new CompletionException(error); }
        });
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("daemon timeout"); }
        String text = output.get(1, TimeUnit.SECONDS).trim();
        if (p.exitValue() != 0) throw new IOException("daemon operation failed");
        return text;
    }
    static void tick() throws Exception {
        String ids = docker("ps", "-aq", "--filter", "label=iwork.manager=" + SandboxProbe.RUN);
        if (!ids.isBlank()) {
            // Inspect one by one: another owner may remove a task between list and inspect.
            for (String id : ids.lines().toList()) {
                JsonNode item;
                try { item = JSON.readTree(docker("inspect", id)).get(0); }
                catch (Exception error) {
                    if (docker("ps", "-aq", "--filter", "id=" + id).isBlank()) continue;
                    throw error;
                }
                SandboxProbe.check(item.path("Config").path("Labels").path("iwork.manager").asText().equals(SandboxProbe.RUN), "refuse foreign task");
                Instant created = Instant.parse(item.path("Created").asText());
                long ageMs = Duration.between(created, Instant.now()).toMillis();
                if (ageMs >= TTL_SECONDS * 1000) {
                    docker("rm", "-f", id);
                    SandboxProbe.check(docker("ps", "-aq", "--filter", "id=" + id).isBlank(), "deletion unconfirmed");
                    Instant removedAt = Instant.now();
                    System.out.println(JSON.writeValueAsString(Map.of("removed", id, "ageMs", ageMs,
                            "ageAtConfirmedRemovalMs", Duration.between(created, removedAt).toMillis(),
                            "created", created.toString(), "confirmedAbsentAt", removedAt.toString())));
                }
            }
        }
        Path stage = HEALTH.resolveSibling("health.next");
        JSON.writeValue(stage.toFile(), Map.of("run", SandboxProbe.RUN, "at", System.currentTimeMillis(), "ttlSeconds", TTL_SECONDS));
        Files.move(stage, HEALTH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static void main(String[] args) throws Exception {
        SandboxProbe.check(SandboxProbe.RUN != null && SandboxProbe.RUN.matches("[a-z0-9-]{1,60}"), "invalid run");
        Files.createDirectories(HEALTH.getParent());
        while (true) {
            try { tick(); }
            catch (Exception | AssertionError error) {
                Files.deleteIfExists(HEALTH);
                System.err.println("WATCHDOG_UNHEALTHY " + error.getClass().getSimpleName());
            }
            Thread.sleep(250);
        }
    }
}
