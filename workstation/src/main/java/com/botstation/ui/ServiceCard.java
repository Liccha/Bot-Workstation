package com.botstation.ui;

import com.botstation.core.ServiceState;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

final class ServiceCard extends JPanel {
    private final JLabel stateLabel = UiKit.muted("检测中");
    private final JLabel descriptionLabel;
    private final Dot dot = new Dot();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    final JButton start = UiKit.primaryButton("启用");
    final JButton stop = UiKit.button("停用");

    ServiceCard(String name, String description) {
        super(new BorderLayout(12, 12));
        setOpaque(false);
        setBorder(DesignTokens.serviceCardBorder());
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(DesignTokens.forText(name, DesignTokens.SECTION));
        nameLabel.setForeground(DesignTokens.INK);
        heading.add(nameLabel, BorderLayout.CENTER);
        JPanel state = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        state.setOpaque(false);
        state.add(dot); state.add(stateLabel);
        heading.add(state, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);
        descriptionLabel = UiKit.muted(description);
        add(descriptionLabel, BorderLayout.CENTER);
        actions.setOpaque(false);
        actions.add(start);
        actions.add(stop);
        add(actions, BorderLayout.SOUTH);
    }

    void addAction(JButton button) { actions.add(button); }
    void setDescription(String description) { descriptionLabel.setText(description); }

    void setState(ServiceState state) {
        stateLabel.setText(state.label);
        Color color;
        switch (state) {
            case RUNNING: color = DesignTokens.SUCCESS; break;
            case DEGRADED: case STARTING: case STOPPING: color = DesignTokens.WARNING; break;
            case STOPPED: color = DesignTokens.DANGER; break;
            default: color = DesignTokens.UNKNOWN;
        }
        dot.color = color; dot.repaint();
        start.setEnabled(state != ServiceState.RUNNING && state != ServiceState.STARTING);
        stop.setEnabled(state != ServiceState.STOPPED && state != ServiceState.STOPPING);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(DesignTokens.SURFACE); g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14); g.dispose();
        super.paintComponent(graphics);
    }

    private static final class Dot extends JPanel {
        private Color color = DesignTokens.UNKNOWN;
        Dot() { setOpaque(false); setPreferredSize(new Dimension(10, 10)); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); graphics.setColor(color); graphics.fillOval(1, 1, 8, 8);
        }
    }
}
