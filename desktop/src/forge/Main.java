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

/** Forge 0.1: a complete repository-to-incident workflow. */
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
    private SwingWorker<?, ?> task;
    private volatile boolean busy;
    private volatile boolean cancelled;
    private boolean dirty, loadingNotes;
    private String activeKind = "", activeSource = "", activeText = "";

    private static JTextArea area(boolean editable) {
        JTextArea text = new JTextArea();
        text.setEditable(editable);
        Font codeFont = new Font("Consolas", Font.PLAIN, 14);
        text.setFont(codeFont.getFamily().equalsIgnoreCase("Consolas")
                         ? codeFont
                         : new Font(Font.MONOSPACED, Font.PLAIN, 14));
        text.setBackground(BG);
        text.setForeground(TEXT);
        text.setCaretColor(ACCENT);
        text.setMargin(new Insets(18, 18, 18, 18));
        text.setTabSize(4);
        return text;
    }
    private Main() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setSize(1400, 900);
        frame.setMinimumSize(new Dimension(1100, 700));
        frame.setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(new EmptyBorder(24, 24, 16, 24));
        root.setBackground(BG);
        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        JLabel brand = new JLabel("FORGE");
        brand.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        brand.setForeground(TEXT);
        context.setForeground(MUTED);
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 8));
        heading.setOpaque(false);
        heading.add(brand);
        heading.add(context);
        top.add(heading, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        actions.setOpaque(false);
        button(actions, "Workspace", this::chooseWorkspace);
        button(actions, "Open repository", this::chooseRepository);
        button(actions, "Refresh", this::refresh);
        button(actions, "Import thread dump", this::importDump);
        button(actions, "Capture evidence", this::capture);
        button(actions, "Save notes", this::saveNotes);
        button(actions, "Export report", this::export);
        button(actions, "Cancel", () -> {
            if (busy) {
                cancelled = true;
                bridge.cancel();
                status.setText("Cancelling. Existing saved evidence is preserved.");
            }
        });
        top.add(actions, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        navigation.addTab("Files", new JScrollPane(files));
        navigation.addTab("Commits", new JScrollPane(commits));
        navigation.addTab("Evidence", new JScrollPane(evidence));
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
        left.add(find, BorderLayout.NORTH);
        left.add(navigation, BorderLayout.CENTER);
        JPanel display = new JPanel(new BorderLayout(0, 8));
        display.setOpaque(false);
        source.setForeground(ACCENT);
        display.add(source, BorderLayout.NORTH);
        display.add(new JScrollPane(viewer), BorderLayout.CENTER);
        content.addTab("Inspector", display);
        content.addTab("Investigator notes", new JScrollPane(notes));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, content);
        split.setResizeWeight(.28);
        split.setDividerSize(10);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);
        status.setForeground(MUTED);
        root.add(status, BorderLayout.SOUTH);
        frame.setContentPane(root);
        for (JList<String> list : List.of(files, commits, evidence)) {
            list.setBackground(PANEL);
            list.setForeground(TEXT);
            list.setSelectionBackground(new Color(47, 49, 55));
            list.setSelectionForeground(TEXT);
            list.setFixedCellHeight(32);
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
                        return;
                    }
                    T result = get();
                    status.setText(label + " complete");
                    apply.accept(result);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    status.setText("Failed: " + cause.getMessage());
                    JOptionPane.showMessageDialog(frame, cause.getMessage(), "Forge",
                                                  JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (task == this)
                        busy = false;
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
                commitsModel.clear();
                commitData.clear();
                activeKind = "";
                activeSource = "";
                activeText = "";
                viewer.setText("");
                source.setText("EVIDENCE VIEWER");
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
    private void refresh() {
        if (!idle())
            return;
        run("Refreshing repository", () -> {
            Map<String, Object> r = bridge.call(workspace, "files", Map.of());
            r.put("history", bridge.call(workspace, "history", Map.of()));
            return r;
        }, this::showRepository);
    }
    @SuppressWarnings("unchecked")
    private void showRepository(Map<String, Object> r) {
        filesModel.clear();
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
        inspect("git-status", str(r, "repository"),
                "Working tree changes (tracked files):\n\n" +
                    (str(r, "status").isBlank() ? "Clean" : str(r, "status")));
    }
    private void inspect(String kind, String origin, String text) {
        activeKind = kind;
        activeSource = origin;
        activeText = text;
        source.setText(kind.toUpperCase(Locale.ROOT) + "  /  " + origin);
        viewer.setText(text);
        viewer.setCaretPosition(0);
        content.setSelectedIndex(0);
    }
    private void search() {
        String term = query.getText();
        run("Searching repository",
            () -> bridge.call(workspace, "search", Map.of("query", term)), r -> {
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
            split.setDividerLocation(.28);
            split.doLayout();
        }
        for (Component child : c.getComponents())
            if (child instanceof Container container)
                layout(container);
    }
    public static void main(String[] args) throws Exception {
        Theme.install();
        if (args.length == 2 && args[0].equals("--render")) {
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
                    app.notes.setText(
                        "Two workers acquire Account and Journal in opposite order.\nThe "
                        + "imported snapshot shows a two-thread monitor cycle.\nNext step: enforce "
                        + "one lock acquisition order and retest.");
                    Container pane = app.frame.getContentPane();
                    pane.setSize(1400, 900);
                    layout(pane);
                    BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
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
