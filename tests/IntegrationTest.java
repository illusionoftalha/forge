package forge;

import forge.analysis.Dump;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class IntegrationTest {
    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Object sample = Map.of("text", "quotes \" slash \\ newline\nemoji 😀", "values",
                               List.of(true, 42, "C:\\files"));
        check(Json.write(Json.parse(Json.write(sample))).equals(Json.write(sample)),
              "JSON roundtrip");
        for (String malformed :
             List.of("{\"a\":1,}", "[1,]", "true false", "{\"a\":1,\"a\":2}", "\"\\uQQQQ\"")) {
            boolean rejected = false;
            try {
                Json.parse(malformed);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "malformed JSON rejected");
        }
        Path home = Path.of("").toAbsolutePath(),
             workspace = Files.createTempDirectory("forge-integration-");
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                var area = new javax.swing.JTextArea("first\r\nsecond\nthird");
                Main.highlightLine(area, 2);
                check(area.getCaretPosition() == 7, "search navigation selects correct line");
                check(area.getHighlighter().getHighlights().length == 1, "line highlight visible");
                Main.highlightLine(area, 999);
                check(area.getCaretPosition() == 14, "line navigation clamps to document");
            });
            Bridge bridge = new Bridge(home);
            Map<String, Object> state = bridge.call(workspace, "state", Map.of());
            check(state.get("repository").equals(""), "new workspace");
            boolean structuredError = false;
            try {
                bridge.call(workspace, "read", Map.of("path", "missing"));
            } catch (Bridge.WorkerException error) {
                structuredError = error.code.equals("ValueError") && error.action.equals("read");
            }
            check(structuredError, "structured worker errors preserve action and code");
            String raw = Files.readString(home.resolve("fixtures/incident.txt"));
            String report = Dump.parse(raw).report();
            check(report.contains("1 monitor cycles"), "detect incident cycle");
            bridge.call(
                workspace, "capture",
                Map.of("kind", "jvm-thread-dump", "source", "fixture", "content", report + raw));
            bridge.call(workspace, "notes",
                        Map.of("body", "A lock-order inversion blocks two workers."));
            Bridge reopened = new Bridge(home);
            state = reopened.call(workspace, "state", Map.of());
            check(((List<?>)state.get("evidence")).size() == 1, "persistent captured evidence");
            check(state.get("notes").toString().contains("inversion"), "persistent notes");
            Path exported = workspace.resolve("report.html");
            reopened.call(workspace, "export", Map.of("path", exported.toString()));
            check(Files.readString(exported).contains("settlement-worker"),
                  "export includes evidence");
            Path fakeHome = workspace.resolve("fake-home");
            Files.createDirectories(fakeHome.resolve("analysis"));
            Path fakeWorker = fakeHome.resolve("analysis/worker.py");
            Files.writeString(fakeWorker, "print('{\"version\":99,\"ok\":true,\"data\":{}}')\n");
            Bridge fake = new Bridge(fakeHome);
            boolean versionRejected = false;
            try {
                fake.call(workspace, "state", Map.of());
            } catch (java.io.IOException expected) {
                versionRejected = expected.getMessage().contains("protocol version");
            }
            check(versionRejected, "reject unsupported worker protocol");
            Files.writeString(fakeWorker,
                "import sys,time\nsys.stdin.read()\n" +
                "print('{\"phase\":\"Indexing\",\"completed\":1,\"total\":2}',file=sys.stderr,flush=True)\n" +
                "time.sleep(.2)\nprint('{\"version\":1,\"ok\":true,\"data\":{}}')\n");
            List<Map<?, ?>> events = new ArrayList<>();
            fake.onProgress(events::add);
            fake.call(workspace, "files", Map.of());
            check(events.size() == 1 && events.get(0).get("phase").equals("Indexing"),
                  "worker progress arrives separately from response");
            Files.writeString(fakeWorker,
                              "import " +
                              "sys,time,pathlib\nsys.stdin.read()\npathlib.Path(__file__).with_" +
                              "suffix('.started').touch()\ntime.sleep(60)\n");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> pending = executor.submit(() -> {
                    try {
                        fake.call(workspace, "state", Map.of());
                        throw new AssertionError("cancelled worker succeeded");
                    } catch (java.io.IOException expected) {
                        return;
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(fakeHome.resolve("analysis/worker.started")) &&
                       System.nanoTime() < deadline)
                    Thread.sleep(20);
                check(Files.exists(fakeHome.resolve("analysis/worker.started")),
                      "test worker started");
                fake.cancel();
                pending.get(5, TimeUnit.SECONDS);
                try (var children = Files.list(workspace)) {
                    check(children.noneMatch(
                              p -> p.getFileName().toString().startsWith("forge-worker-")),
                          "cancel cleans up response file");
                }
            } finally {
                fake.cancel();
                executor.shutdownNow();
            }
            System.out.println("Java protocol, workspace restore, incident export and worker " +
                               "cancellation passed.");
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
