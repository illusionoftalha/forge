package forge;

import java.awt.*;
import java.util.Collections;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.*;

/** Small monochrome theme; no external look-and-feel dependency. */
final class Theme {
    static final Color BACKGROUND = new Color(16, 17, 19);
    static final Color SURFACE = new Color(23, 24, 27);
    static final Color BORDER = new Color(46, 48, 53);
    static final Color INK = new Color(231, 232, 235);
    static final Color MUTED = new Color(182, 186, 194);

    static final class Mark implements Icon {
        public int getIconWidth() { return 34; }
        public int getIconHeight() { return 34; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(INK);
            g.fillRoundRect(0, 0, 34, 34, 10, 10);
            g.setColor(BACKGROUND);
            g.fillPolygon(new int[]{7, 25, 23, 10}, new int[]{8, 8, 12, 12}, 4);
            g.fillPolygon(new int[]{10, 23, 21, 15, 15, 10}, new int[]{15, 15, 19, 19, 26, 26}, 6);
            g.dispose();
        }
    }

    static Font preferredFont(int size, String fallback, String... families) {
        for (String family : families) {
            Font candidate = new Font(family, Font.PLAIN, size);
            if (candidate.getFamily().equalsIgnoreCase(family)) return candidate;
        }
        return new Font(fallback, Font.PLAIN, size);
    }

    static void install() {
        Font ui = preferredFont(15, Font.SANS_SERIF, "Segoe UI", "Noto Sans", "DejaVu Sans");
        var font = new javax.swing.plaf.FontUIResource(ui);
        for (Object key : Collections.list(UIManager.getDefaults().keys()))
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)
                UIManager.put(key, font);
        for (String widget : new String[] {"Panel", "Button", "ComboBox", "TabbedPane", "TextField",
                                           "ScrollPane", "Viewport", "List", "OptionPane"}) {
            UIManager.put(widget + ".background", SURFACE);
            UIManager.put(widget + ".foreground", INK);
        }
        UIManager.put("Label.foreground", INK);
        UIManager.put("TextField.caretForeground", INK);
        UIManager.put("TabbedPane.selected", SURFACE);
        UIManager.put("ScrollPane.border", BorderFactory.createLineBorder(BORDER));
    }

    static void apply(Component component) {
        if (component instanceof JButton button) {
            boolean primary = Boolean.TRUE.equals(button.getClientProperty("primary"));
            button.setUI(new BasicButtonUI() {
                public void paint(Graphics graphics, JComponent component) {
                    Graphics2D g = (Graphics2D)graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    boolean hover = button.getModel().isRollover();
                    boolean pressed = button.getModel().isPressed();
                    Color fill = primary && button.isEnabled() ? (pressed ? MUTED : INK)
                        : pressed ? BORDER : hover ? new Color(48, 50, 55) : new Color(34, 35, 39);
                    g.setColor(fill);
                    g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 9, 9);
                    g.setColor(button.isFocusOwner() ? MUTED : primary && button.isEnabled() ? fill : BORDER);
                    g.drawRoundRect(0, 0, button.getWidth()-1, button.getHeight()-1, 9, 9);
                    g.dispose();
                    super.paint(graphics, component);
                }
            });
            button.setRolloverEnabled(true);
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setForeground(primary ? BACKGROUND : INK);
            button.setBorder(new EmptyBorder(8, 12, 8, 12));
        }
        if (component instanceof JTabbedPane tabs) {
            tabs.setUI(new Tabs());
            tabs.setBackground(BACKGROUND);
            tabs.setForeground(MUTED);
            tabs.setOpaque(true);
        }
        if (component instanceof JSplitPane split) {
            split.setBackground(BACKGROUND);
            split.setOpaque(true);
            split.setUI(new BasicSplitPaneUI() {
                public BasicSplitPaneDivider createDefaultDivider() {
                    BasicSplitPaneDivider divider = new BasicSplitPaneDivider(this);
                    divider.setBorder(null);
                    divider.setBackground(BACKGROUND);
                    return divider;
                }
            });
        }
        if (component instanceof JSplitPane split) split.setBorder(null);
        if (component instanceof JScrollPane scroll) {
            scroll.setBorder(BorderFactory.createLineBorder(BORDER));
            scroll.getViewport().setBackground(BACKGROUND);
        }
        if (component instanceof JScrollBar scroll) {
            scroll.setUI(new BasicScrollBarUI() {
                protected void configureScrollBarColors() {
                    thumbColor = new Color(66, 69, 75);
                    trackColor = BACKGROUND;
                }
                protected JButton createDecreaseButton(int orientation) { return hidden(); }
                protected JButton createIncreaseButton(int orientation) { return hidden(); }
                private JButton hidden() {
                    JButton button = new JButton();
                    button.setPreferredSize(new Dimension(0, 0));
                    return button;
                }
            });
            scroll.setPreferredSize(new Dimension(9, 9));
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents())
                apply(child);
    }

    private static final class Tabs extends BasicTabbedPaneUI {
        protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(9, 9, 9, 9);
            tabAreaInsets = new Insets(0, 0, 8, 0);
            contentBorderInsets = new Insets(0, 0, 0, 0);
        }
        protected void paintContentBorder(Graphics g, int placement, int selected) {}
        protected void paintFocusIndicator(Graphics g, int placement, Rectangle[] rectangles,
                                           int index, Rectangle icon, Rectangle text,
                                           boolean selected) {
            if (tabPane.hasFocus() && selected) {
                g.setColor(MUTED);
                Rectangle r = rectangles[index];
                g.drawRect(r.x + 2, r.y + 2, r.width - 5, r.height - 5);
            }
        }
        protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w,
                                          int h, boolean selected) {
            g.setColor(selected ? SURFACE : BACKGROUND);
            g.fillRect(x, y, w, h);
        }
        protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w,
                                      int h, boolean selected) {
            g.setColor(selected ? INK : BORDER);
            g.fillRect(x, y + h - 2, w, selected ? 2 : 1);
        }
        protected void paintText(Graphics g, int placement, Font font, FontMetrics metrics,
                                 int index, String title, Rectangle rect, boolean selected) {
            g.setFont(font);
            g.setColor(selected ? INK : MUTED);
            BasicGraphicsUtils.drawString(tabPane, (Graphics2D)g, title, rect.x, rect.y + metrics.getAscent());
        }
    }
}
