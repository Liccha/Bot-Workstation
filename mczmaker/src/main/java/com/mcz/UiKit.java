package com.mcz;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.*;

/** 通用 UI 组件：ModernButton, ModernRoundedBorder, EmojiIcon, CapsuleLabel */
public class UiKit {

    public static class ModernButton extends JButton {
        private Color hoverBackgroundColor = new Color(225, 225, 225);
        private Color pressedBackgroundColor = new Color(210, 210, 210);
        private int cornerRadius = -1;
        private Color borderColor = new Color(200, 200, 200);

        public ModernButton(String text) {
            super(text);
            super.setContentAreaFilled(false);
            setFocusPainted(false);
            setBackground(new Color(245, 245, 245));
            setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
            setVerticalTextPosition(SwingConstants.CENTER);
            setHorizontalTextPosition(SwingConstants.CENTER);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        }

        public void setCustomColors(Color bg, Color hover, Color pressed) {
            setBackground(bg);
            this.hoverBackgroundColor = hover;
            this.pressedBackgroundColor = pressed;
        }

        public void setCornerRadius(int r) { this.cornerRadius = r; }
        public void setBorderColor(Color c) { this.borderColor = c; }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isPressed()) g2.setColor(pressedBackgroundColor);
            else if (getModel().isRollover()) g2.setColor(hoverBackgroundColor);
            else g2.setColor(getBackground());
            int arc = cornerRadius > 0 ? cornerRadius : getHeight();
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(borderColor);
            int arc = cornerRadius > 0 ? cornerRadius : getHeight();
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
        }
    }

    public static class ModernRoundedBorder extends AbstractBorder {
        private Color color;
        private int thickness;
        private int radius;

        public ModernRoundedBorder(Color color, int thickness, int radius) {
            this.color = color;
            this.thickness = thickness;
            this.radius = radius;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(thickness));
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(radius / 2, radius / 2, radius / 2, radius / 2);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(radius / 2, radius / 2, radius / 2, radius / 2);
            return insets;
        }
    }

    public static class EmojiIcon implements Icon {
        private String emoji;
        private int size;
        private Font font;

        public EmojiIcon(String emoji, int size) {
            this.emoji = emoji;
            this.size = size;
            this.font = new Font("Segoe UI Emoji", Font.PLAIN, size);
        }

        public int getIconWidth() { return size + 4; }
        public int getIconHeight() { return size; }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(font);
            g2.setColor(c.getForeground());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(emoji, x + 2, y + fm.getAscent() - (size / 8));
            g2.dispose();
        }
    }

    public static class CapsuleLabel extends JLabel {
        private Color bgColor;
        private String prefix;
        private String content;
        private boolean isRainbow;

        public CapsuleLabel(String prefix, String rawContent, Color bg, Color fg) {
            this(prefix, rawContent, bg, fg, false, true);
        }

        public CapsuleLabel(String prefix, String rawContent, Color bg, Color fg, boolean isRainbow, boolean isCopyable) {
            this(prefix, rawContent, bg, fg, isRainbow, isCopyable, null);
        }

        public CapsuleLabel(String prefix, String rawContent, Color bg, Color fg, boolean isRainbow, boolean isCopyable, Font customFont) {
            super(prefix + (rawContent == null ? "" : rawContent));
            this.bgColor = bg;
            this.prefix = prefix;
            this.content = (rawContent == null ? "" : rawContent);
            this.isRainbow = isRainbow;

            setForeground(fg);
            setOpaque(false);
            if (customFont != null) {
                Font compositeFont = javax.swing.text.StyleContext.getDefaultStyleContext().getFont(customFont.getFamily(), Font.PLAIN, 14);
                setFont(compositeFont);
            } else {
                setFont(new Font(Font.DIALOG, Font.BOLD, 13));
            }

            setBorder(BorderFactory.createEmptyBorder(4, 15, 4, 15));

            if (isCopyable) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText("点击复制: " + this.content);
                addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                                .setContents(new java.awt.datatransfer.StringSelection(content), null);
                        setText("已复制！");
                        javax.swing.Timer t = new javax.swing.Timer(1000, ev -> setText(prefix + content));
                        t.setRepeats(false); t.start();
                    }
                });
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (isRainbow) {
                LinearGradientPaint lgp = new LinearGradientPaint(
                        0, 0, getWidth(), getHeight(),
                        new float[]{0.0f, 0.25f, 0.5f, 0.75f, 1.0f},
                        new Color[]{
                                new Color(255, 215, 215),
                                new Color(255, 245, 195),
                                new Color(215, 255, 215),
                                new Color(215, 245, 255),
                                new Color(235, 215, 255)
                        }
                );
                g2.setPaint(lgp);
            } else {
                g2.setColor(bgColor);
            }

            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
