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
    static final Color MUTED = new Color(149, 153, 162);

    static void install() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        var font = new javax.swing.plaf.FontUIResource("SansSerif", Font.PLAIN, 13);
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
            button.setUI(new BasicButtonUI());
            button.setBackground(SURFACE);
            button.setForeground(INK);
            button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(8, 12, 8, 12)));
            button.addFocusListener(new java.awt.event.FocusAdapter() {
                public void focusGained(java.awt.event.FocusEvent e) {
                    button.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(MUTED), new EmptyBorder(8, 12, 8, 12)));
                }
                public void focusLost(java.awt.event.FocusEvent e) {
                    button.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(BORDER), new EmptyBorder(8, 12, 8, 12)));
                }
            });
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
            tabInsets = new Insets(10, 15, 10, 15);
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
            g.drawString(title, rect.x, rect.y + metrics.getAscent());
        }
    }
}
