package com.mcz;

import javax.swing.*;
import java.awt.*;

/**
 * 波形绘制面板 — 音频波形可视化与选区标记
 */
public class WaveformPanel extends JPanel {
    private final MczTool parent;
    private final JSlider refSlider;
    private final Color waveColor = new Color(80, 180, 120);
    private final Color selBorder = new Color(220, 80, 80, 180);
    private final Color selFill = new Color(255, 160, 140, 60);

    public WaveformPanel(MczTool parent, JSlider slider) {
        this.parent = parent;
        this.refSlider = slider;
        setLayout(null);
        slider.setOpaque(false);
        add(slider);
    }

    @Override
    public void doLayout() {
        super.doLayout();
        if (refSlider != null) {
            int sw = getWidth();
            int sh = refSlider.getPreferredSize().height;
            int sy = (getHeight() - sh) / 2;
            refSlider.setBounds(0, sy, sw, sh);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (parent.waveformDB == null || parent.waveformDB.length == 0) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int pad = 10;
        int effW = getWidth() - 20;
        if (effW <= 0) { g2.dispose(); return; }

        if (parent.totalAudioSeconds > 0 && parent.audioEndTime > parent.audioStartTime) {
            int sx = pad + (int)(parent.audioStartTime * effW / parent.totalAudioSeconds);
            int ex = pad + (int)(parent.audioEndTime * effW / parent.totalAudioSeconds);
            g2.setColor(selFill);
            g2.fillRect(sx, 5, ex - sx, h-20);
            g2.setColor(selBorder);
            g2.drawLine(sx, 5, sx, h-16);
            g2.drawLine(ex, 5, ex, h-16);
        }

        int bins = parent.waveformDB.length;
        float barW = Math.max(1f, (float) effW / bins);
        int midY = h / 2;
        int maxHalf = midY - 2;
        for (int i = 0; i < bins; i++) {
            float db = parent.waveformDB[i];
            float ratio = (db + 25f) / 25f;
            if (ratio <= 0f) continue;
            if (ratio > 1f) ratio = 1f;
            ratio = (float) Math.pow(ratio, 2.2);
            int barH = (int)(maxHalf * ratio * 0.9f);
            if (barH < 1) barH = 1;
            int x = pad + (int)(i * barW);
            int barWInt = Math.max(2, (int) Math.ceil(barW) - 1);
            g2.setColor(waveColor);
            g2.fillRect(x, midY - barH, barWInt, barH);
            g2.setColor(new Color(140, 210, 170));
            g2.fillRect(x, midY, barWInt, barH);
        }
        g2.setColor(new Color(180, 200, 190, 120));
        g2.drawLine(pad, midY, pad + effW, midY);
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, 105);
    }
}
