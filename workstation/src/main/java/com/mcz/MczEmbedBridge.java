package com.mcz;

import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.dnd.DropTarget;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adapts the existing JFrame implementation into a workbench page without
 * copying or reimplementing any of its high-complexity controls.
 */
public final class MczEmbedBridge {
    private MczEmbedBridge() {}

    public static JComponent create(Path mczDirectory) {
        Path base = mczDirectory.toAbsolutePath().normalize();
        System.setProperty("user.dir", base.toString());
        Path ffmpeg = base.resolve("ffmpeg.exe");
        MczTool.ffmpegCommand = Files.isRegularFile(ffmpeg) ? ffmpeg.toString() : "ffmpeg";

        MczTool source = new MczTool();
        source.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        JMenuBar menu = source.getJMenuBar();
        Container body = source.getContentPane();
        InputMap keys = source.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = source.getRootPane().getActionMap();
        DropTarget dropTarget = source.getDropTarget();

        source.setJMenuBar(null);
        source.setContentPane(new JPanel());
        source.setDropTarget(null);
        source.dispose();

        JPanel embedded = new JPanel(new BorderLayout());
        if (menu != null) embedded.add(menu, BorderLayout.NORTH);
        embedded.add(body, BorderLayout.CENTER);
        embedded.setInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW, keys);
        embedded.setActionMap(actions);
        if (dropTarget != null) dropTarget.setComponent(embedded);
        embedded.putClientProperty("botstation.mcz.owner", source);
        return embedded;
    }
}
