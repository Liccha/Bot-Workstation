package com.mcz;

import com.botstation.features.MczCloudSongService;

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
import java.util.function.Consumer;

/**
 * Adapts the existing JFrame implementation into a workbench page without
 * copying or reimplementing any of its high-complexity controls.
 */
public final class MczEmbedBridge {
    private MczEmbedBridge() {}

    public static JComponent create(Path mczDirectory) {
        return create(mczDirectory, null, null);
    }

    public static JComponent create(Path mczDirectory, MczCloudSongService cloud, Consumer<String> status) {
        Path base = mczDirectory.toAbsolutePath().normalize();
        System.setProperty("user.dir", base.toString());
        Path ffmpeg = base.resolve("ffmpeg.exe");
        MczTool.ffmpegCommand = Files.isRegularFile(ffmpeg) ? ffmpeg.toString() : "ffmpeg";

        MczTool source = new MczTool();
        if (cloud != null) {
            source.setCloudSongPublisher(new MczTool.CloudSongPublisher() {
                @Override public void create(String id, java.util.Map<String, String> values,
                                             java.io.File image, java.io.File audio) throws Exception {
                    cloud.createSong(id, values, image == null ? null : image.toPath(),
                        audio == null ? null : audio.toPath());
                }
                @Override public void update(String id, java.util.Map<String, String> values,
                                             java.io.File image, java.io.File audio) throws Exception {
                    cloud.updateSong(id, values, image == null ? null : image.toPath(),
                        audio == null ? null : audio.toPath());
                }
            });
            source.taskQueue.submit(() -> {
                try {
                    MczCloudSongService.Snapshot latest = cloud.loadLatest();
                    int max = ExcelManager.mergeCloudSongs(source, latest.columns, latest.rows);
                    if (status != null) status.accept("云端曲库已同步，当前最大 ID " + max);
                } catch (Exception error) {
                    if (status != null) status.accept("云端曲库暂不可用，已使用本机缓存：" + safeMessage(error));
                }
            });
        }
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

    private static String safeMessage(Throwable error) {
        String value = error == null ? "连接失败" : String.valueOf(error.getMessage());
        value = value.replaceAll("[\\r\\n]", " ").trim();
        return value.isEmpty() ? "连接失败" : value.substring(0, Math.min(160, value.length()));
    }
}
