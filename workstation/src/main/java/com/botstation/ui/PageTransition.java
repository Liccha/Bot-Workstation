package com.botstation.ui;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.Timer;
import javax.swing.plaf.LayerUI;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;

/** Short, non-blocking page entrance used consistently across workstation modules. */
final class PageTransition extends LayerUI<JComponent> {
    private static final int DURATION_MS = 300;
    private float progress = 1f;
    private Timer timer;

    static JLayer<JComponent> wrap(JComponent component) {
        return new JLayer<>(component, new PageTransition());
    }

    static void play(JComponent component) {
        if (!(component instanceof JLayer)) return;
        @SuppressWarnings("unchecked") JLayer<JComponent> layer = (JLayer<JComponent>) component;
        if (layer.getUI() instanceof PageTransition) ((PageTransition) layer.getUI()).start(layer);
    }

    private void start(JLayer<JComponent> layer) {
        if (reduceMotion()) { progress = 1f; layer.repaint(); return; }
        if (timer != null) timer.stop();
        final long started = System.nanoTime();
        progress = 0f;
        timer = new Timer(16, event -> {
            float elapsed = (System.nanoTime() - started) / 1_000_000f;
            float linear = Math.min(1f, elapsed / DURATION_MS);
            progress = 1f - (float) Math.pow(1f - linear, 3);
            layer.repaint();
            if (linear >= 1f) ((Timer) event.getSource()).stop();
        });
        timer.start();
    }

    private static boolean reduceMotion() {
        if (Boolean.getBoolean("botstation.reduceMotion")) return true;
        Object highContrast = Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on");
        return Boolean.TRUE.equals(highContrast);
    }

    @Override public void paint(Graphics graphics, JComponent component) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setComposite(AlphaComposite.SrcOver.derive(0.22f + progress * 0.78f));
        g.translate(0, Math.round((1f - progress) * 13f));
        super.paint(g, component);
        g.dispose();
    }
}
