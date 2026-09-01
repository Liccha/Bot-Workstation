package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.botstation.features.MczCloudSongService;
import com.mcz.MczEmbedBridge;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Files;

final class MczWorkspacePanel extends JPanel {
    MczWorkspacePanel(BotPaths paths, LogBus log, TaskRunner tasks) {
        super(new BorderLayout());
        setBackground(DesignTokens.PAPER);
        JPanel strip = new JPanel(new BorderLayout(12, 0));
        strip.setBackground(DesignTokens.SURFACE);
        strip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, DesignTokens.BORDER),
            BorderFactory.createEmptyBorder(8, 16, 8, 16)));
        JLabel title = new JLabel("谱面录入与图片设计");
        title.setFont(DesignTokens.BODY_MEDIUM);
        title.setForeground(DesignTokens.INK);
        strip.add(title, BorderLayout.WEST);
        add(strip, BorderLayout.NORTH);
        JPanel loading = UiKit.card();
        loading.add(UiKit.section("正在载入谱面库…"), BorderLayout.NORTH);
        loading.add(UiKit.muted("正在初始化音频、制图与日历组件。"), BorderLayout.CENTER);
        add(loading, BorderLayout.CENTER);
        tasks.run(() -> {
            Files.createDirectories(paths.mczMaker);
            return MczEmbedBridge.create(paths.mczMaker, new MczCloudSongService(paths),
                message -> log.info("谱面曲库", message));
        }, embedded -> {
            remove(loading);
            add(embedded, BorderLayout.CENTER);
            revalidate(); repaint();
            log.info("MczMaker", "谱面录入与图片设计已载入");
        }, error -> {
            remove(loading);
            JPanel failed = UiKit.card();
            failed.add(UiKit.section("MczMaker 嵌入失败"), BorderLayout.NORTH);
            failed.add(UiKit.muted(error.getClass().getSimpleName() + " · " + error.getMessage()), BorderLayout.CENTER);
            failed.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(24, 24, 24, 24), DesignTokens.cardBorder()));
            add(failed, BorderLayout.CENTER);
            revalidate(); repaint();
            log.error("MczMaker", "嵌入失败：" + error.getMessage());
        });
    }
}
