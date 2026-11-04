package forge;

import forge.analysis.Dump;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Forge 0.2: a complete repository-to-incident workflow. */
public final class Main {
    private static final Color BG = Theme.BACKGROUND, PANEL = Theme.SURFACE, TEXT = Theme.INK,
                               MUTED = Theme.MUTED, ACCENT = Theme.INK;
    private final Path home =
        Path.of(System.getProperty("forge.home", System.getProperty("user.dir"))).toAbsolutePath();
    private Path workspace = home.resolve("forge-workspace");
    private final Bridge bridge = new Bridge(home);
    private final JFrame frame = new JFrame("Forge — Software Investigation Workbench");
    private final DefaultListModel<String> filesModel = new DefaultListModel<>(),
                                           commitsModel = new DefaultListModel<>(),
                                           evidenceModel = new DefaultListModel<>();
    private final JList<String> files = new JList<>(filesModel),
                                commits = new JList<>(commitsModel),
                                evidence = new JList<>(evidenceModel);
    private final List<Map<String, Object>> commitData = new ArrayList<>(), evidenceData =
                                                                                new ArrayList<>();
    private final JTextArea viewer = area(false), notes = area(true);
    private final JTextField query = new JTextField();
    private final JLabel context = new JLabel("No repository selected"),
                         status = new JLabel("Ready"), source = new JLabel("EVIDENCE VIEWER");
    private final JTabbedPane navigation = new JTabbedPane(), content = new JTabbedPane();
    private final DefaultListModel<String> resultsModel = new DefaultListModel<>();
    private final JList<String> results = new JList<>(resultsModel);
    private final List<Map<String, Object>> resultData = new ArrayList<>();
    private final JTextArea jobs = area(false);
    private final JProgressBar progressBar = new JProgressBar();
    private final JPanel metrics = new JPanel(new GridLayout(1, 3, 1, 0));
    private final JLabel documentTitle = new JLabel("Open a repository to begin");
    private final JButton cancel = new JButton("Cancel");
    private final JButton capture = new JButton("Capture evidence");
    private SwingWorker<?, ?> task;
    private volatile boolean busy;
    private volatile boolean cancelled;
    private boolean dirty, loadingNotes;
    private String activeKind = "", activeSource = "", activeText = "";

    private static JTextArea area(boolean editable) {
        JTextArea text = new JTextArea();
        text.setEditable(editable);
        text.setFont(Theme.preferredFont(16, Font.MONOSPACED, "Cascadia Mono", "Consolas", "DejaVu Sans Mono"));
        text.setBackground(BG);
        text.setForeground(TEXT);
        text.setCaretColor(ACCENT);
        text.setMargin(new Insets(18, 18, 18, 18));
        text.setTabSize(4);
        return text;
    }
    private Main() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(Math.min(880, screen.width), Math.min(580, screen.height)));
        frame.setSize(Math.min(1320, screen.width), Math.min(840, screen.height));
        frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(new EmptyBorder(18, 22, 12, 22));
        root.setBackground(BG);
        JPanel top = new JPanel(new BorderLayout(20, 0));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(0, 0, 14, 0)));
        JLabel brand = new JLabel("Forge", new Theme.Mark(), SwingConstants.LEFT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 22f));
        brand.setIconTextGap(10);
        top.add(brand, BorderLayout.WEST);
        context.setForeground(MUTED);
        top.add(context, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        button(actions, "Workspace", this::chooseWorkspace);
        button(actions, "Open repository", this::chooseRepository);
        button(actions, "Export report", this::export);
        top.add(actions, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);
        navigation.addTab("Files", new JScrollPane(files));
        navigation.addTab("Commits", new JScrollPane(commits));
        navigation.addTab("Evidence", new JScrollPane(evidence));
        navigation.addTab("Results", new JScrollPane(results));
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        query.setToolTipText("Literal case-insensitive repository search; Enter to run");
        query.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(8, 10, 8, 10)));
        query.getAccessibleContext().setAccessibleName("Search repository text");
        query.addActionListener(e -> search());
        JPanel find = new JPanel(new BorderLayout(6, 0));
        find.setOpaque(false);
        find.add(query, BorderLayout.CENTER);
        JButton search = new JButton("Search");
        search.addActionListener(e -> search());
        find.add(search, BorderLayout.EAST);
        JPanel explorer = new JPanel(new BorderLayout(0, 12));
        JLabel explorerTitle = new JLabel("Repository");
        explorerTitle.setFont(explorerTitle.getFont().deriveFont(Font.BOLD, 18f));
        JPanel explorerHeading = new JPanel(new BorderLayout());
        explorerHeading.add(explorerTitle, BorderLayout.WEST);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        explorerHeading.add(refresh, BorderLayout.EAST);
        explorer.add(explorerHeading, BorderLayout.NORTH);
        explorer.add(find, BorderLayout.SOUTH);
        left.setOpaque(true);
        left.setBackground(PANEL);
        left.setBorder(new EmptyBorder(14, 14, 14, 14));
        left.setMinimumSize(new Dimension(270, 180));
        left.setPreferredSize(new Dimension(325, 400));
        left.add(explorer, BorderLayout.NORTH);
        left.add(navigation, BorderLayout.CENTER);
        JPanel display = new JPanel(new BorderLayout(0, 8));
        display.setOpaque(false);
        source.setForeground(MUTED);
        source.setFont(source.getFont().deriveFont(14f));
        documentTitle.setFont(documentTitle.getFont().deriveFont(Font.BOLD, 24f));
        JPanel identity = new JPanel(new GridLayout(2, 1));
        identity.add(documentTitle);
        identity.add(source);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        button(tools, "Import thread dump", this::importDump);
        capture.putClientProperty("primary", true);
        capture.addActionListener(e -> capture());
        capture.setEnabled(false);
        tools.add(capture);
        JPanel sheet = new JPanel(new BorderLayout(10, 12));
        sheet.setBorder(new EmptyBorder(14, 18, 14, 14));
        sheet.add(identity, BorderLayout.CENTER);
        sheet.add(tools, BorderLayout.EAST);
        metrics.setVisible(false);
        metrics.setBackground(Theme.BORDER);
        sheet.add(metrics, BorderLayout.SOUTH);
        display.add(sheet, BorderLayout.NORTH);
        display.add(new JScrollPane(viewer), BorderLayout.CENTER);
        content.addTab("Inspector", display);
        JPanel notebook = new JPanel(new BorderLayout(0, 8));
        JPanel noteActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        button(noteActions, "Save notes", this::saveNotes);
        notebook.add(noteActions, BorderLayout.NORTH);
        notebook.add(new JScrollPane(notes), BorderLayout.CENTER);
        content.addTab("Investigator notes", notebook);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, content);
        content.setMinimumSize(new Dimension(320, 200));
        split.setResizeWeight(.20);
        split.setDividerSize(10);
        split.setBorder(null);
        split.setDividerLocation(325);
        root.add(split, BorderLayout.CENTER);
        status.setForeground(MUTED);
        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setOpaque(false);
        progressBar.setPreferredSize(new Dimension(180, 6));
        footer.add(status, BorderLayout.CENTER);
        JPanel activity = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        activity.setOpaque(false);
        progressBar.setForeground(TEXT);
        progressBar.setVisible(false);
        activity.add(progressBar);
        cancel.setVisible(false);
        cancel.addActionListener(e -> {
            cancelled = true;
            bridge.cancel();
            status.setText("Cancelling current job…");
        });
        activity.add(cancel);
        footer.add(activity, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        JPanel jobPanel = new JPanel(new BorderLayout(0, 8));
        JPanel indexActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        indexActions.setOpaque(false);
        jobPanel.setOpaque(false);
        button(indexActions, "View changes", this::changes);
        button(indexActions, "Rebuild index", () -> refresh(true));
        jobPanel.add(indexActions, BorderLayout.NORTH);
        jobPanel.add(new JScrollPane(jobs), BorderLayout.CENTER);
        content.addTab("Jobs & index", jobPanel);
        bridge.onProgress(event -> SwingUtilities.invokeLater(() -> {
            if (!busy || cancelled) return;
            if (event.get("completed") instanceof Number completed &&
                event.get("total") instanceof Number total) {
                progressBar.setIndeterminate(false);
                progressBar.setMaximum(Math.max(1, total.intValue()));
                progressBar.setValue(completed.intValue());
                status.setText(event.get("phase") + " · " + completed + " / " + total);
            }
        }));
        frame.setContentPane(root);
        for (JList<String> list : List.of(files, commits, evidence, results)) {
            list.setBackground(PANEL);
            list.setForeground(TEXT);
            list.setSelectionBackground(new Color(47, 49, 55));
            list.setSelectionForeground(TEXT);
            list.setFixedCellHeight(37);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(
                    JList<?> owner, Object value, int index, boolean selected, boolean focused) {
                    JLabel label = (JLabel)super.getListCellRendererComponent(owner, value, index,
                                                                              selected, focused);
                    label.setBorder(new EmptyBorder(0, 12, 0, 12));
                    return label;
                }
            });
        }
        results.addListSelectionListener(e -> {
            int i = results.getSelectedIndex();
            if (!e.getValueIsAdjusting() && i >= 0 && i < resultData.size() && idle()) {
                Map<String, Object> hit = resultData.get(i);
                run("Opening search result",
                    () -> bridge.call(workspace, "read", Map.of("path", str(hit, "path"))), r -> {
                        inspect("source", str(r, "source"), str(r, "text"));
                        if (!str(hit, "sha256").equals(str(r, "sha256"))) {
                            status.setText("File changed since search. Search again to update line locations.");
                            return;
                        }
                        highlightLine(viewer, ((Number)hit.get("line")).intValue());
                    });
            }
        });
        files.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && files.getSelectedValue() != null && idle()) {
                String path = files.getSelectedValue();
                run("Reading file",
                    ()
                        -> bridge.call(workspace, "read", Map.of("path", path)),
                    r -> inspect("source", str(r, "source"), str(r, "text")));
            }
        });
        commits.addListSelectionListener(e -> {
            int i = commits.getSelectedIndex();
            if (!e.getValueIsAdjusting() && i >= 0 && i < commitData.size() && idle()) {
                String sha = str(commitData.get(i), "sha");
                run("Reading commit",
                    ()
                        -> bridge.call(workspace, "diff", Map.of("sha", sha)),
                    r -> inspect("git-diff", str(r, "source"), str(r, "text")));
            }
        });
        evidence.addListSelectionListener(e -> {
            int i = evidence.getSelectedIndex();
            if (!e.getValueIsAdjusting() && i >= 0 && i < evidenceData.size() && idle()) {
                Object id = evidenceData.get(i).get("id");
                run("Reading captured evidence",
                    ()
                        -> bridge.call(workspace, "evidence", Map.of("id", id)),
                    r -> inspect(str(r, "kind"), str(r, "source"), str(r, "text")));
            }
        });
        notes.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            private void changed() {
                if (!loadingNotes) {
                    dirty = true;
                    content.setTitleAt(1, "Investigator notes • unsaved");
                }
            }
        });
        Theme.apply(root);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                if (!idle()) {
                    JOptionPane.showMessageDialog(
                        frame, "Cancel or finish the current operation before closing.");
                    return;
                }
                if (confirmDiscard()) {
                    frame.dispose();
                }
            }
        });
    }
    private static void button(JPanel panel, String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        panel.add(b);
    }
    private static String str(Map<String, Object> map, String key) {
        return Objects.toString(map.get(key), "");
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>)map.getOrDefault(key, List.of());
    }
    private boolean idle() { return !busy; }
    private <T> void run(String label, Callable<T> work, Consumer<T> apply) {
        if (!idle()) {
            status.setText("An operation is running. Use Cancel before starting another.");
            return;
        }
        status.setText(label + " …");
        busy = true;
        cancel.setVisible(true);
        progressBar.setVisible(true);
        long started = System.nanoTime();
        appendJob("RUNNING  " + label);
        progressBar.setIndeterminate(true);
        cancelled = false;
        task = new SwingWorker<T, Void>() {
            protected T doInBackground() throws Exception {
                if (cancelled)
                    return null;
                return work.call();
            }
            protected void done() {
                try {
                    if (task != this)
                        return;
                    if (cancelled) {
                        status.setText("Cancelled. Saved data remains available.");
                        appendJob("CANCELLED  " + label);
                        return;
                    }
                    T result = get();
                    status.setText(label + " complete");
                    apply.accept(result);
                    appendJob("COMPLETE  " + label + " · " +
                              ((System.nanoTime() - started) / 1_000_000) + " ms");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    status.setText("Failed: " + cause.getMessage());
                    String detail = cause instanceof Bridge.WorkerException error
                        ? error.code + " / " + error.action : cause.getClass().getSimpleName();
                    appendJob("FAILED  " + label + "\n  " + detail + "\n  " + cause.getMessage());
                    JOptionPane.showMessageDialog(frame, cause.getMessage(), "Forge",
                                                  JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (task == this) {
                        busy = false;
                        cancel.setVisible(false);
                        progressBar.setVisible(false);
                        progressBar.setIndeterminate(false);
                        progressBar.setValue(0);
                    }
                }
            }
        };
        task.execute();
    }
    private boolean confirmDiscard() {
        return !dirty ||
            JOptionPane.showConfirmDialog(
                frame, "Discard unsaved notebook changes? Use Save notes to keep them.",
                "Unsaved notes", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }
    private Path chooseDirectory(String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION
            ? chooser.getSelectedFile().toPath().toAbsolutePath()
            : null;
    }
    private void chooseWorkspace() {
        if (!idle() || !confirmDiscard())
            return;
        Path selected = chooseDirectory("Choose a Forge workspace folder");
        if (selected != null) {
            workspace = selected;
            restore();
        }
    }
    private void chooseRepository() {
        if (!idle())
            return;
        Path selected = chooseDirectory("Open a Git repository");
        if (selected != null)
            run("Opening repository", () -> {
                Map<String, Object> r =
                    bridge.call(workspace, "open", Map.of("repository", selected.toString()));
                r.put("history", bridge.call(workspace, "history", Map.of()));
                return r;
            }, this::showRepository);
    }
    private void restore() {
        run("Restoring workspace",
            ()
                -> {
                Map<String, Object> r = bridge.call(workspace, "state", Map.of());
                if (!str(r, "repository").isEmpty()) {
                    try {
                        r.put("listing", bridge.call(workspace, "files", Map.of()));
                        r.put("history", bridge.call(workspace, "history", Map.of()));
                    } catch (Exception error) {
                        r.remove("listing");
                        r.put("warning", error.getMessage());
                    }
                }
                return r;
            },
            r -> {
                loadingNotes = true;
                notes.setText(str(r, "notes"));
                loadingNotes = false;
                dirty = false;
                content.setTitleAt(1, "Investigator notes");
                showEvidence(r);
                filesModel.clear();
                resultsModel.clear();
                resultData.clear();
                commitsModel.clear();
                commitData.clear();
                activeKind = "";
                activeSource = "";
                activeText = "";
                viewer.setText("");
                source.setText("Choose Open repository or import a thread dump.");
                documentTitle.setText("Start an investigation");
                metrics.setVisible(false);
                capture.setEnabled(false);
                context.setText("Workspace: " + workspace);
        if(r.get("listing") instanceof Map<?,?> listing){
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>)listing;
            data.put("history", r.get("history"));
            showRepository(data);
        }
        if (r.containsKey("warning"))
            status.setText("Saved notes and evidence restored; repository unavailable: " +
                           r.get("warning"));
            });
    }
    private void appendJob(String message) {
        if (jobs.getDocument().getLength() > 32000) jobs.setText("");
        jobs.append(java.time.LocalTime.now().withNano(0) + "  " + message + "\n");
    }
    static void highlightLine(JTextArea target, int number) {
        try {
            int line = Math.max(0, Math.min(number - 1, target.getLineCount() - 1));
            int start = target.getLineStartOffset(line), end = target.getLineEndOffset(line);
            target.getHighlighter().removeAllHighlights();
            target.getHighlighter().addHighlight(start, end,
                new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(61, 64, 71)));
            target.setCaretPosition(start);
        } catch (javax.swing.text.BadLocationException error) {
            throw new IllegalStateException(error);
        }
    }
    private void changes() {
        run("Reading index changes", () -> bridge.call(workspace, "changes", Map.of()), r -> {
            StringBuilder out = new StringBuilder("Latest nonempty index changes\nExact-content renames only. Refresh to inspect current files.\n\n");
            for (Map<String, Object> change : maps(r, "changes")) {
                out.append(str(change, "kind")).append("  ").append(str(change, "path"));
                if (!str(change, "previous").isEmpty()) out.append("  ← ").append(str(change, "previous"));
                out.append('\n');
            }
            inspect("index-changes", context.getText(), out.toString());
        });
    }
    private void refresh() { refresh(false); }
    private void refresh(boolean rebuild) {
        if (!idle())
            return;
        run("Refreshing repository", () -> {
            Map<String, Object> r = bridge.call(workspace, "files", Map.of("rebuild", rebuild));
            r.put("history", bridge.call(workspace, "history", Map.of()));
            return r;
        }, this::showRepository);
    }
    @SuppressWarnings("unchecked")
    private void showRepository(Map<String, Object> r) {
        filesModel.clear();
        resultsModel.clear();
        resultData.clear();
        for (Object path : (List<?>)r.get("files"))
            filesModel.addElement(path.toString());
        commitData.clear();
        commitsModel.clear();
        Map<String, Object> history = (Map<String, Object>)r.get("history");
        commitData.addAll(maps(history, "commits"));
        for (Map<String, Object> commit : commitData)
            commitsModel.addElement(str(commit, "sha").substring(0, 7) + "  " +
                                    str(commit, "subject"));
        context.setText(str(r, "repository") + "   /   " + filesModel.size() + " files   /   " +
                        commitData.size() + " recent commits");
        status.setText(
            Boolean.TRUE.equals(r.get("limited"))
                ? "File list capped at 20,000; inspect a smaller repository."
                : "Repository loaded. Double-check evidence before drawing conclusions.");
        if (r.containsKey("index_warning")) {
            status.setText("Browsing available · " + str(r, "index_warning"));
            appendJob("INDEX  " + str(r, "index_warning"));
        }
        if (r.get("index") instanceof Map<?, ?> stats)
            appendJob("INDEX  " + stats.get("reused") + " reused · " + stats.get("updated") +
                      " read · " + stats.get("skipped") + " skipped · " + stats.get("bytes") + " bytes");
        inspect("git-status", str(r, "repository"),
                "Working tree changes (tracked files):\n\n" +
                    (str(r, "status").isBlank() ? "Clean" : str(r, "status")));
    }
    private static final java.util.regex.Pattern SNAPSHOT_COUNTS = java.util.regex.Pattern.compile(
        "THREADSCOPE / snapshot analysis\n\n([0-9]+) threads · ([0-9]+) monitor waits · ([0-9]+) monitor cycles\n\n");
    private void inspect(String kind, String origin, String text) {
        activeKind = kind;
        activeSource = origin;
        activeText = text;
        String title = switch (kind) {
            case "jvm-thread-dump" -> "Thread snapshot";
            case "git-status" -> "Working tree";
            case "git-diff" -> "Commit patch";
            case "index-changes" -> "Index changes";
            case "search" -> "Search results";
            case "source" -> "Source file";
            default -> kind;
        };
        documentTitle.setText(title);
        source.setText(origin);
        source.setToolTipText(origin);
        capture.setEnabled(true);
        metrics.removeAll();
        var counts = SNAPSHOT_COUNTS.matcher(text);
        boolean summary = kind.equals("jvm-thread-dump") && counts.lookingAt();
        if (summary) {
            for (int i = 1; i <= 3; i++) {
                JPanel metric = new JPanel(new BorderLayout(0, 3));
                metric.setBorder(new EmptyBorder(10, 14, 10, 10));
                metric.setBackground(BG);
                JLabel value = new JLabel(counts.group(i));
                value.setFont(value.getFont().deriveFont(Font.BOLD, 23f));
                String noun = List.of("Thread", "Monitor wait", "Monitor cycle").get(i - 1);
                JLabel label = new JLabel(counts.group(i).equals("1") ? noun : noun + "s");
                label.setForeground(MUTED);
                metric.add(value, BorderLayout.NORTH);
                metric.add(label, BorderLayout.SOUTH);
                metrics.add(metric);
            }
        }
        metrics.setVisible(summary);
        metrics.revalidate();
        viewer.getHighlighter().removeAllHighlights();
        viewer.setLineWrap(summary);
        viewer.setWrapStyleWord(summary);
        viewer.setText(summary ? text.substring(counts.end()) : text);
        viewer.setCaretPosition(0);
        content.setSelectedIndex(0);
    }
    private void search() {
        String term = query.getText();
        run("Searching repository",
            () -> bridge.call(workspace, "search", Map.of("query", term)), r -> {
                resultsModel.clear();
                resultData.clear();
                resultData.addAll(maps(r, "hits"));
                for (Map<String, Object> hit : resultData)
                    resultsModel.addElement(str(hit, "path") + ":" + hit.get("line") + "  " + str(hit, "text"));
                navigation.setSelectedIndex(3);
                StringBuilder out = new StringBuilder("Literal search: " + term + "\n\n");
                for (Map<String, Object> hit : maps(r, "hits"))
                    out.append(str(hit, "path"))
                        .append(':')
                        .append(hit.get("line"))
                        .append("  ")
                        .append(str(hit, "text"))
                        .append('\n');
                out.append("\nTruncated: ")
                    .append(r.get("limited"))
                    .append(" · Skipped files: ")
                    .append(r.get("skipped"));
                inspect("search", context.getText() + " / query=" + term, out.toString());
                status.setText(resultData.size() + " matches · select a result to jump to its line" +
                    (Boolean.TRUE.equals(r.get("limited")) ? " · truncated at 300" : "") +
                    " · skipped files: " + r.get("skipped"));
            });
    }
    private void importDump() {
        if (!idle())
            return;
        JFileChooser chooser = new JFileChooser(home.resolve("fixtures").toFile());
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION)
            return;
        Path path = chooser.getSelectedFile().toPath();
        run("Analyzing thread dump", () -> {
            if (Files.size(path) > 2L * 1024 * 1024)
                throw new IOException("Thread dump exceeds 2 MiB");
            String raw = Files.readString(path);
            return Dump.parse(raw).report() + "\n\nORIGINAL SNAPSHOT\n\n" + raw;
        }, text -> inspect("jvm-thread-dump", path.toAbsolutePath().toString(), text));
    }
    private void capture() {
        if (activeKind.isEmpty()) {
            status.setText("Inspect a file, commit or thread dump first.");
            return;
        }
        Map<String, Object> payload =
            Map.of("kind", activeKind, "source", activeSource, "content", activeText);
        run("Capturing evidence",
            ()
                -> {
                bridge.call(workspace, "capture", payload);
                return bridge.call(workspace, "state", Map.of());
            },
            r -> {
                showEvidence(r);
                navigation.setSelectedIndex(2);
                status.setText(
                    "Evidence saved with a UTC timestamp and SHA-256 of the captured text.");
            });
    }
    private void showEvidence(Map<String, Object> r) {
        evidenceData.clear();
        evidenceModel.clear();
        evidenceData.addAll(maps(r, "evidence"));
        for (Map<String, Object> item : evidenceData)
            evidenceModel.addElement("#" + item.get("id") + "  " + str(item, "kind") + "  ·  " +
                                     str(item, "source"));
    }
    private void saveNotes() {
        String body = notes.getText();
        run("Saving notebook", () -> bridge.call(workspace, "notes", Map.of("body", body)), r -> {
            dirty = !notes.getText().equals(body);
            content.setTitleAt(1, dirty ? "Investigator notes • unsaved" : "Investigator notes");
        });
    }
    private void export() {
        if (!idle())
            return;
        JFileChooser chooser = new JFileChooser(workspace.toFile());
        chooser.setSelectedFile(new File("investigation.html"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION)
            return;
        Path path = chooser.getSelectedFile().toPath();
        String body = notes.getText();
        run("Exporting investigation",
            ()
                -> {
                bridge.call(workspace, "notes", Map.of("body", body));
                return bridge.call(workspace, "export", Map.of("path", path.toString()));
            },
            r -> {
                dirty = !notes.getText().equals(body);
                content.setTitleAt(1,
                                   dirty ? "Investigator notes • unsaved" : "Investigator notes");
                status.setText("Report saved: " + r.get("path"));
            });
    }
    private static void layout(Container c) {
        c.doLayout();
        if (c instanceof JSplitPane split) {
            split.setDividerLocation(310);
            split.doLayout();
        }
        for (Component child : c.getComponents())
            if (child instanceof Container container)
                layout(container);
    }
    public static void main(String[] args) throws Exception {
        boolean render = args.length == 2 && args[0].equals("--render");
        // Export grayscale glyphs. LCD subpixel colors are specific to a physical screen.
        if (render) System.setProperty("awt.useSystemAAFontSettings", "on");
        Theme.install();
        if (render) {
            SwingUtilities.invokeAndWait(() -> {
                Main app = new Main();
                try {
                    String dump = Files.readString(app.home.resolve("fixtures/incident.txt"));
                    app.inspect("jvm-thread-dump", "fixtures/incident.txt",
                                Dump.parse(dump).report() + "\nORIGINAL SNAPSHOT\n" + dump);
                    app.context.setText(
                        "Local investigation  /  Settlement service · demo fixture");
                    app.filesModel.addElement("src/Settlement.java");
                    app.filesModel.addElement("src/Journal.java");
                    app.filesModel.addElement("README.md");
                    app.evidenceModel.addElement("#2  jvm-thread-dump · incident.txt");
                    app.evidenceModel.addElement("#1  git-diff · regression commit");
                    app.navigation.setSelectedIndex(2);
                    app.evidence.setSelectedIndex(0);
                    app.notes.setText(
                        "Two workers acquire Account and Journal in opposite order.\nThe "
                        + "imported snapshot shows a two-thread monitor cycle.\nNext step: enforce "
                        + "one lock acquisition order and retest.");
                    Container pane = app.frame.getContentPane();
                    pane.setSize(1040, 710);
                    layout(pane);
                    BufferedImage image = new BufferedImage(pane.getWidth() * 2, pane.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    g.scale(2, 2);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                       RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    pane.printAll(g);
                    g.dispose();
                    ImageIO.write(image, "png", new File(args[1]));
                    if (!app.activeText.contains("1 monitor cycles"))
                        throw new AssertionError("incident analysis failed");
                    System.out.println(
                        "Rendered actual Swing components; incident assertion passed.");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    app.frame.dispose();
                }
            });
            return;
        }
        SwingUtilities.invokeLater(() -> {
            Main app = new Main();
            if (args.length == 1)
                app.workspace = Path.of(args[0]).toAbsolutePath();
            app.frame.setVisible(true);
            app.restore();
        });
    }
}
