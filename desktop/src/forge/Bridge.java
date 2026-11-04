package forge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One request per isolated worker process. The desktop owns process lifetime. */
public final class Bridge {
    private final Path home;
    private volatile Process active;
    public Bridge(Path home) { this.home = home; }
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(Path workspace, String action, Map<String, Object> arguments)
        throws Exception {
        Files.createDirectories(workspace);
        Map<String, Object> request = new LinkedHashMap<>(arguments);
        request.put("version", 1);
        request.put("workspace", workspace.toString());
        request.put("action", action);
        Path output = Files.createTempFile(workspace, "forge-worker-", ".json");
        Process process = null;
        try {
            ProcessBuilder builder =
                new ProcessBuilder(System.getenv().getOrDefault("FORGE_PYTHON", "python"), "-I",
                                   home.resolve("analysis/worker.py").toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            process = builder.start();
            active = process;
            try (OutputStream input = process.getOutputStream()) {
                input.write(Json.write(request).getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(30, TimeUnit.SECONDS))
                throw new IOException("Analysis timed out after 30 seconds");
            if (Files.size(output) > 16L * 1024 * 1024)
                throw new IOException("Worker output exceeds 16 MiB");
            String raw = Files.readString(output);
            if (process.exitValue() != 0)
                throw new IOException("Worker stopped: " +
                                      raw.substring(0, Math.min(raw.length(), 600)));
            Object parsed = Json.parse(raw);
            if (!(parsed instanceof Map<?, ?> response))
                throw new IOException("Invalid worker response");
            if (!Objects.equals(response.get("version"), java.math.BigDecimal.ONE))
                throw new IOException("Unsupported worker protocol version");
            if (!Boolean.TRUE.equals(response.get("ok")))
                throw new IOException(String.valueOf(response.get("error")));
            if (!(response.get("data") instanceof Map<?, ?> data))
                throw new IOException("Worker response must contain an object");
            return (Map<String, Object>)data;
        } finally {
            if (process != null) {
                stop(process);
                process.waitFor(3, TimeUnit.SECONDS);
            }
            active = null;
            Files.deleteIfExists(output);
        }
    }
    private static void stop(Process process) {
        process.descendants().forEach(p -> p.destroyForcibly());
        if (process.isAlive())
            process.destroyForcibly();
    }
    public void cancel() {
        Process process = active;
        if (process != null)
            stop(process);
    }
}
