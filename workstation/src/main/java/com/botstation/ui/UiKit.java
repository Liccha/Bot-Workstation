package com.botstation.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.AbstractBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

public final class UiKit {
    private UiKit() {}

    public static JButton primaryButton(String text) {
        JButton button = button(text);
        button.setBackground(DesignTokens.ACCENT);
        button.setForeground(DesignTokens.SURFACE);
        sizeButton(button, text);
        return button;
    }

    public static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(DesignTokens.forText(text, DesignTokens.BUTTON));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(true);
        button.setBackground(DesignTokens.SURFACE_ALT);
        button.setForeground(DesignTokens.INK);
        button.setMargin(new Insets(8, 16, 8, 16));
        sizeButton(button, text);
        return button;
    }

    public static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(DesignTokens.forText(text, DesignTokens.TITLE));
        label.setForeground(DesignTokens.INK);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(JLabel.LEFT);
        return label;
    }

    public static JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setFont(DesignTokens.forText(text, DesignTokens.SECTION));
        label.setForeground(DesignTokens.INK);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(JLabel.LEFT);
        return label;
    }

    public static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setFont(DesignTokens.forText(text, DesignTokens.BODY_SMALL));
        label.setForeground(DesignTokens.MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setHorizontalAlignment(JLabel.LEFT);
        return label;
    }

    public static JPanel pageHeader(String title, String description, JComponent action) {
        JPanel panel = new JPanel(new BorderLayout(20, 0));
        panel.setOpaque(false);
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new javax.swing.BoxLayout(copy, javax.swing.BoxLayout.Y_AXIS));
        copy.add(UiKit.title(title));
        copy.add(javax.swing.Box.createVerticalStrut(7));
        copy.add(UiKit.muted(description));
        panel.add(copy, BorderLayout.CENTER);
        if (action != null) {
            JPanel actionBox = new JPanel(new java.awt.GridBagLayout());
            actionBox.setOpaque(false);
            actionBox.add(action);
            panel.add(actionBox, BorderLayout.EAST);
        }
        return panel;
    }

    public static JPanel card() {
        return card(DesignTokens.SURFACE, 14, new Insets(18, 18, 18, 18));
    }

    public static JPanel card(Color fill, int arc, Insets padding) {
        JPanel panel = new SurfacePanel(new BorderLayout(), fill, arc);
        panel.setBorder(BorderFactory.createCompoundBorder(
            new RoundedLineBorder(DesignTokens.BORDER, 1, arc),
            BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right)));
        return panel;
    }

    public static JComponent iconBadge(Icon icon, Color foreground, Color fill) {
        JPanel badge = new SurfacePanel(new BorderLayout(), fill, 10);
        badge.setPreferredSize(new Dimension(36, 36));
        badge.setMinimumSize(new Dimension(36, 36));
        badge.setMaximumSize(new Dimension(36, 36));
        JLabel label = new JLabel(icon, JLabel.CENTER);
        label.setForeground(foreground);
        badge.add(label, BorderLayout.CENTER);
        return badge;
    }

    public static JPanel flow(Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        for (Component component : components) panel.add(component);
        return panel;
    }

    public static JTextField field(int columns) {
        JTextField field = new JTextField(columns);
        field.setFont(DesignTokens.BODY);
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, 40));
        return field;
    }

    public static JScrollPane tableScroll(JTable table) {
        table.setFont(DesignTokens.BODY);
        table.setRowHeight(32);
        table.setShowGrid(true);
        table.setShowVerticalLines(true);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(1, 1));
        table.setGridColor(DesignTokens.BORDER);
        table.setSelectionBackground(new Color(235, 241, 251));
        table.setSelectionForeground(DesignTokens.INK);
        table.setDefaultRenderer(Object.class, new MultilingualCenteredRenderer());
        table.getTableHeader().setFont(DesignTokens.BODY_MEDIUM);
        ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(JLabel.CENTER);
        table.getTableHeader().setPreferredSize(new Dimension(10, 34));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(DesignTokens.BORDER));
        scroll.getViewport().setBackground(DesignTokens.SURFACE);
        return scroll;
    }

    /** Applies compact, semantic widths after a table model is installed. */
    public static void applySemanticColumnWidths(JTable table) {
        for (int index = 0; index < table.getColumnCount(); index++) {
            TableColumn column = table.getColumnModel().getColumn(index);
            String name = table.getColumnName(index).trim().toLowerCase(java.util.Locale.ROOT);
            int width;
            if (name.equals("id") || name.equals("sid")) width = 68;
            else if (name.equals("bpm")) width = 78;
            else if (name.equals("duration") || name.equals("length")) width = 88;
            else if (name.contains("song_name") || name.equals("title") || name.equals("name")) width = 238;
            else if (name.contains("author") || name.contains("artist") || name.contains("charter") || name.contains("creator")) width = 156;
            else if (name.contains("album")) width = 160;
            else if (name.contains("cover") || name.contains("path")) width = 260;
            else width = 132;
            column.setMinWidth(name.equals("id") || name.equals("sid") ? 52 : 70);
            column.setPreferredWidth(width);
        }
    }

    public static JPanel page() {
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.setBackground(DesignTokens.PAPER);
        panel.setBorder(new EmptyBorder(24, 24, 20, 24));
        return panel;
    }

    private static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static void sizeButton(JButton button, String text) {
        java.awt.FontMetrics metrics = button.getFontMetrics(button.getFont());
        Insets insets = button.getInsets();
        int width = Math.max(76, metrics.stringWidth(text) + insets.left + insets.right + 10);
        int height = Math.max(40, metrics.getHeight() + insets.top + insets.bottom);
        Dimension size = new Dimension(width, height);
        button.setMinimumSize(size);
        button.setPreferredSize(size);
    }

    private static final class SurfacePanel extends JPanel {
        private final Color fill;
        private final int arc;
        SurfacePanel(java.awt.LayoutManager layout, Color fill, int arc) {
            super(layout); this.fill = fill; this.arc = arc; setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc); g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedLineBorder extends AbstractBorder {
        private final Color color; private final int thickness; private final int arc;
        RoundedLineBorder(Color color, int thickness, int arc) { this.color = color; this.thickness = thickness; this.arc = arc; }
        @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color); g.drawRoundRect(x, y, width - thickness, height - thickness, arc, arc); g.dispose();
        }
        @Override public Insets getBorderInsets(Component component) { return new Insets(thickness, thickness, thickness, thickness); }
    }

    private static final class MultilingualCenteredRenderer extends DefaultTableCellRenderer {
        MultilingualCenteredRenderer() {
            setHorizontalAlignment(JLabel.CENTER);
            setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 7));
        }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focused, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            String text = value == null ? "" : String.valueOf(value);
            component.setFont(DesignTokens.forText(text, DesignTokens.BODY));
            if (table.getColumnName(column).equalsIgnoreCase("cover") && text.contains("& A")) {
                Object sid = table.getColumnCount() == 0 ? "" : table.getValueAt(row, 0);
                setToolTipText(text);
                setText("stable_cover\\" + sid + ".webp");
            } else {
                setToolTipText(text.isEmpty() ? null : text);
            }
            if (!selected) component.setBackground(row % 2 == 0 ? DesignTokens.SURFACE : new Color(253, 251, 253));
            return component;
        }
    }
}
