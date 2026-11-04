package forge.analysis;

import java.util.*;
import java.util.regex.*;

/** Parser and monitor-ownership analysis for HotSpot text thread dumps. */
public final class Dump {
    private static final Pattern HEADER = Pattern.compile("^\"(.*)\"\\s+(.*)$");
    private static final Pattern ID = Pattern.compile("(?:^|\\s)tid=(\\S+)");
    private static final Pattern STATE = Pattern.compile("java\\.lang\\.Thread\\.State:\\s+(\\w+)");
    private static final Pattern LOCK =
        Pattern.compile("- (locked|waiting to lock|waiting to re-lock in wait\\(\\)|parking to "
                        + "wait for|waiting on)\\s+<(0x[0-9a-fA-F]+)>");

    public record ThreadInfo(String id, String name, String state, List<String> frames,
                             Set<String> held, String waiting, String raw) {
        public String topFrame() { return frames.isEmpty() ? "(no Java frames)" : frames.get(0); }
    }
    public record Edge(String from, String to, String monitor) {}
    public final List<ThreadInfo> threads;
    public final List<Edge> edges;
    public final List<List<String>> cycles;
    public final List<String> warnings;
    private final Map<String, ThreadInfo> byId;

    private Dump(List<ThreadInfo> threads, List<String> warnings) {
        this.threads = List.copyOf(threads);
        Map<String, ThreadInfo> index = new HashMap<>();
        for (ThreadInfo t : threads)
            index.put(t.id(), t);
        byId = Map.copyOf(index);
        Map<String, Set<String>> owners = new HashMap<>();
        for (ThreadInfo t : threads)
            for (String lock : t.held())
                owners.computeIfAbsent(lock, k -> new LinkedHashSet<>()).add(t.id());
        List<Edge> links = new ArrayList<>();
        for (ThreadInfo t : threads)
            if (t.waiting() != null) {
                Set<String> candidates = owners.getOrDefault(t.waiting(), Set.of());
                if (candidates.size() == 1) {
                    String owner = candidates.iterator().next();
                    if (!owner.equals(t.id()))
                        links.add(new Edge(t.id(), owner, t.waiting()));
                } else if (candidates.size() > 1)
                    warnings.add("Ambiguous owner for " + t.waiting());
            }
        edges = List.copyOf(links);
        cycles = findCycles(edges);
        this.warnings = List.copyOf(warnings);
    }

    public static Dump parse(String text) {
        List<ThreadInfo> threads = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        String name = null, header = null;
        for (String line : text.split("\\R", -1)) {
            // The JVM's own deadlock appendix repeats thread names without tid=.
            if (line.startsWith("Found one Java-level deadlock") ||
                line.matches("Found \\d+ Java-level deadlocks.*"))
                break;
            Matcher m = HEADER.matcher(line);
            if (m.matches() && (m.group(2).contains("tid=") || m.group(2).matches("#\\d+.*"))) {
                if (name != null)
                    threads.add(parseThread(name, header, block.toString(), threads.size()));
                name = m.group(1);
                header = m.group(2);
                block.setLength(0);
            }
            if (name != null)
                block.append(line).append('\n');
        }
        if (name != null)
            threads.add(parseThread(name, header, block.toString(), threads.size()));
        Set<String> seen = new HashSet<>();
        for (ThreadInfo t : threads)
            if (!seen.add(t.id()))
                throw new IllegalArgumentException("Repeated thread ID " + t.id() +
                                                   ". Open one snapshot at a time.");
        if (threads.isEmpty())
            throw new IllegalArgumentException(
                "No HotSpot thread headers found. Use jcmd <pid> Thread.print -l.");
        warnings.add("Monitor cycles only: parked synchronizers, native locks and virtual-thread "
                     + "JSON dumps are not analyzed.");
        return new Dump(threads, warnings);
    }

    private static ThreadInfo parseThread(String name, String header, String raw, int index) {
        Matcher idMatch = ID.matcher(header), stateMatch = STATE.matcher(raw);
        String id = idMatch.find() ? idMatch.group(1) : "thread-" + index;
        String state = stateMatch.find() ? stateMatch.group(1) : "UNKNOWN";
        List<String> frames = new ArrayList<>();
        Set<String> held = new LinkedHashSet<>(), released = new HashSet<>();
        String waiting = null;
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at "))
                frames.add(trimmed.substring(3));
            Matcher m = LOCK.matcher(trimmed);
            if (m.find()) {
                String kind = m.group(1), lock = m.group(2).toLowerCase(Locale.ROOT);
                if (kind.equals("locked"))
                    held.add(lock);
                else if (kind.startsWith("waiting to"))
                    waiting = lock;
                else if (kind.equals("waiting on"))
                    released.add(lock);
            }
        }
        // Object.wait releases its monitor, even if the stack lists it as locked.
        held.removeAll(released);
        if (waiting != null)
            held.remove(waiting);
        return new ThreadInfo(id, name, state, List.copyOf(frames), Set.copyOf(held), waiting,
                              raw.stripTrailing());
    }

    private static List<List<String>> findCycles(List<Edge> edges) {
        Map<String, String> next = new LinkedHashMap<>();
        for (Edge e : edges)
            next.put(e.from(), e.to());
        Set<String> done = new HashSet<>();
        List<List<String>> cycles = new ArrayList<>();
        for (String start : next.keySet()) {
            Map<String, Integer> position = new LinkedHashMap<>();
            String cursor = start;
            while (cursor != null && !done.contains(cursor) && !position.containsKey(cursor)) {
                position.put(cursor, position.size());
                cursor = next.get(cursor);
            }
            if (cursor != null && position.containsKey(cursor)) {
                List<String> path = new ArrayList<>(position.keySet());
                cycles.add(List.copyOf(path.subList(position.get(cursor), path.size())));
            }
            done.addAll(position.keySet());
        }
        return List.copyOf(cycles);
    }

    public ThreadInfo thread(String id) {
        ThreadInfo result = byId.get(id);
        if (result == null)
            throw new NoSuchElementException(id);
        return result;
    }

    public Map<String, List<ThreadInfo>> groups() {
        Map<String, List<ThreadInfo>> result = new TreeMap<>();
        for (ThreadInfo t : threads)
            result.computeIfAbsent(t.topFrame(), k -> new ArrayList<>()).add(t);
        return result;
    }

    public String report() {
        StringBuilder out = new StringBuilder("THREADSCOPE / snapshot analysis\n\n");
        out.append(threads.size())
            .append(" threads · ")
            .append(edges.size())
            .append(" monitor waits · ")
            .append(cycles.size())
            .append(" monitor cycles\n\nSTATES\n");
        Map<String, Integer> states = new TreeMap<>();
        for (ThreadInfo t : threads)
            states.merge(t.state(), 1, Integer::sum);
        states.forEach((s, n) -> out.append("  ").append(s).append(": ").append(n).append('\n'));
        out.append("\nMONITOR CYCLES\n");
        if (cycles.isEmpty())
            out.append("  None identified in the supplied monitor ownership data.\n");
        for (List<String> cycle : cycles) {
            out.append("  ");
            for (String id : cycle)
                out.append(thread(id).name()).append(" [").append(id).append("] -> ");
            out.append(thread(cycle.get(0)).name()).append('\n');
        }
        out.append("\nWAIT EDGES\n");
        for (Edge e : edges)
            out.append("  ")
                .append(thread(e.from()).name())
                .append(" -> ")
                .append(thread(e.to()).name())
                .append(" via ")
                .append(e.monitor())
                .append('\n');
        out.append("\nTOP-FRAME GROUPS\n");
        groups()
            .entrySet()
            .stream()
            .sorted(
                Comparator
                    .<Map.Entry<String, List<ThreadInfo>>>comparingInt(e -> - e.getValue().size())
                    .thenComparing(Map.Entry::getKey))
            .forEach(e
                     -> out.append("  ")
                            .append(e.getValue().size())
                            .append(" × ")
                            .append(e.getKey())
                            .append('\n'));
        out.append("\nSCOPE\n");
        warnings.forEach(w -> out.append("  ").append(w).append('\n'));
        return out.toString();
    }
}
