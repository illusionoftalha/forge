package forge;

import forge.analysis.Dump;
import java.nio.file.*;
import java.util.*;

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
            Bridge bridge = new Bridge(home);
            Map<String, Object> state = bridge.call(workspace, "state", Map.of());
            check(state.get("repository").equals(""), "new workspace");
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
            System.out.println(
                "Java protocol, worker integration, workspace restore and incident export passed.");
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
