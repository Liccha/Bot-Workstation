package com.botstation.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;

/** One dependency-free, consistent 2px line icon family for the workstation rail. */
final class NavIcon implements Icon {
    enum Kind { BRAND, OVERVIEW, MUSIC, MCZ, STABLE, ADMIN, OPERATIONS, MOBILE, LOG }

    private final Kind kind;
    private final int size;

    NavIcon(Kind kind, int size) { this.kind = kind; this.size = size; }
    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (kind == Kind.BRAND) { paintBrand(g); g.dispose(); return; }
        g.setColor(component == null ? DesignTokens.NAV_TEXT : component.getForeground());
        g.setStroke(new BasicStroke(Math.max(1.7f, size / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double scale = size / 24.0;
        g.scale(scale, scale);
        switch (kind) {
            case OVERVIEW:
                rounded(g, 3, 3, 7, 7, 2); rounded(g, 14, 3, 7, 7, 2);
                rounded(g, 3, 14, 7, 7, 2); rounded(g, 14, 14, 7, 7, 2); break;
            case MUSIC:
                g.drawLine(9, 5, 19, 3); g.drawLine(9, 5, 9, 17); g.drawLine(19, 3, 19, 15);
                g.drawOval(4, 16, 5, 4); g.drawOval(14, 14, 5, 4); break;
            case MCZ:
                g.drawLine(4, 7, 20, 7); g.drawLine(4, 17, 20, 17);
                g.drawLine(8, 4, 8, 10); g.drawLine(16, 14, 16, 20);
                g.drawOval(6, 5, 4, 4); g.drawOval(14, 15, 4, 4); break;
            case STABLE:
                g.drawOval(4, 3, 16, 6); g.drawArc(4, 7, 16, 7, 180, 180);
                g.drawArc(4, 13, 16, 7, 180, 180); g.drawLine(4, 6, 4, 17); g.drawLine(20, 6, 20, 17); break;
            case ADMIN:
                GeneralPath shield = new GeneralPath(); shield.moveTo(12, 3); shield.lineTo(20, 6);
                shield.lineTo(19, 14); shield.curveTo(18, 18, 15, 20, 12, 21);
                shield.curveTo(9, 20, 6, 18, 5, 14); shield.lineTo(4, 6); shield.closePath(); g.draw(shield);
                g.drawOval(9, 9, 6, 5); g.drawLine(12, 14, 12, 17); break;
            case OPERATIONS:
                g.drawOval(5, 5, 14, 14); g.drawLine(12, 2, 12, 6); g.drawLine(12, 18, 12, 22);
                g.drawLine(2, 12, 6, 12); g.drawLine(18, 12, 22, 12); g.fillOval(10, 10, 4, 4); break;
            case MOBILE:
                rounded(g, 7, 2, 10, 20, 2); g.drawLine(10, 5, 14, 5); g.fillOval(11, 18, 2, 2); break;
            case LOG:
                rounded(g, 5, 3, 14, 18, 2); g.drawLine(9, 8, 16, 8);
                g.drawLine(9, 12, 16, 12); g.drawLine(9, 16, 14, 16); break;
            default: break;
        }
        g.dispose();
    }

    private void paintBrand(Graphics2D g) {
        g.setColor(DesignTokens.ACCENT);
        float inset = Math.max(1f, size * .04f);
        float arc = size * .34f;
        g.fill(new RoundRectangle2D.Float(inset, inset, size - inset * 2, size - inset * 2, arc, arc));

        g.setColor(DesignTokens.SURFACE);
        g.setStroke(new BasicStroke(Math.max(1.4f, size / 15f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float cx = size * .50f, cy = size * .52f;
        float ax = size * .31f, ay = size * .33f;
        float bx = size * .69f, by = size * .32f;
        float dx = size * .67f, dy = size * .72f;
        g.drawLine(Math.round(ax), Math.round(ay), Math.round(cx), Math.round(cy));
        g.drawLine(Math.round(bx), Math.round(by), Math.round(cx), Math.round(cy));
        g.drawLine(Math.round(dx), Math.round(dy), Math.round(cx), Math.round(cy));
        float node = Math.max(3.2f, size * .16f);
        g.fill(new RoundRectangle2D.Float(ax - node / 2, ay - node / 2, node, node, node * .45f, node * .45f));
        g.fill(new RoundRectangle2D.Float(bx - node / 2, by - node / 2, node, node, node * .45f, node * .45f));
        g.fill(new RoundRectangle2D.Float(dx - node / 2, dy - node / 2, node, node, node * .45f, node * .45f));
        float hub = node * 1.15f;
        g.fill(new RoundRectangle2D.Float(cx - hub / 2, cy - hub / 2, hub, hub, hub * .42f, hub * .42f));
    }

    private static void rounded(Graphics2D g, double x, double y, double w, double h, double arc) {
        g.draw(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
    }
}
