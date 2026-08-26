package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.mcz.DailySongManager;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ToolsPanel extends JPanel {
    ToolsPanel(BotPaths paths, LogBus log, TaskRunner tasks) {
        super(new BorderLayout(0, 18));
        setBackground(DesignTokens.PAPER);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 26, 20, 26));
        add(UiKit.pageHeader("运营中心", "这里仅保留发布后的日常运营工具；内容制作和数据维护已归入左侧专属模块。", null), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout()); body.setOpaque(false);
        JPanel grid = new JPanel(new GridBagLayout()); grid.setOpaque(false);
        addTool(grid, tool("每日歌曲与竞猜", "管理推荐歌曲、竞猜队列并预览实际推送内容。", "打开管理",
            () -> DailySongManager.showManager(owner())), 0, 0, 1.18, 1);
        addTool(grid, tool("Editor 正式网站", "打开面向用户的歌曲查询站，检查线上页面和公开内容。", "打开网站",
            () -> browse(paths.editorUrl)), 1, 0, .82, 1);
        addTool(grid, stableTool(paths.stableGrabber, tasks, log), 0, 1, 1, 2);
        body.add(grid); add(body, BorderLayout.CENTER);
    }

    private void addTool(JPanel grid, JPanel tool, int x, int y, double weight, int width) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x; constraints.gridy = y; constraints.gridwidth = width;
        constraints.weightx = weight; constraints.weighty = y == 1 ? .7 : 1;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(y == 0 ? 0 : 12, x == 0 ? 0 : 6, 0, x + width >= 2 ? 0 : 6);
        grid.add(tool, constraints);
    }

    private JPanel tool(String title, String detail, String action, Runnable runnable) {
        JPanel card = UiKit.card();
        javax.swing.JLabel heading = new javax.swing.JLabel(title); heading.setFont(DesignTokens.SECTION); heading.setForeground(DesignTokens.INK);
        card.add(heading, BorderLayout.NORTH); card.add(UiKit.muted(detail), BorderLayout.CENTER);
        JButton button = UiKit.primaryButton(action); button.addActionListener(event -> runnable.run());
        card.add(UiKit.flow(button), BorderLayout.SOUTH); return card;
    }

    private JPanel stableTool(java.nio.file.Path path, TaskRunner tasks, LogBus log) {
        JPanel card = UiKit.card();
        JLabel heading = new JLabel("RM Stable 抓取"); heading.setFont(DesignTokens.SECTION); heading.setForeground(DesignTokens.INK);
        JLabel detail = UiKit.muted("调用 rm_stable_info.exe；状态会持续显示，完成后可到 Stable 曲库检查并保存。");
        JLabel state = UiKit.muted("未启动");
        JButton launch = UiKit.primaryButton("启动抓取");
        JButton stop = UiKit.button("结束进程"); stop.setEnabled(false);
        AtomicReference<ProcessHandle> active = new AtomicReference<>();
        launch.addActionListener(event -> launchExe(path, launch, stop, state, active, tasks, log));
        stop.addActionListener(event -> stopExe(path, launch, stop, state, active, tasks, log));
        card.add(heading, BorderLayout.NORTH); card.add(detail, BorderLayout.CENTER);
        card.add(UiKit.flow(launch, stop, state), BorderLayout.SOUTH);
        exactProcess(path).ifPresent(process -> {
            active.set(process);
            launch.setEnabled(false); stop.setEnabled(true);
            state.setText("检测到后台残留");
            monitor(process, launch, stop, state, active, tasks, log);
        });
        return card;
    }

    static void browse(String url) {
        try { Desktop.getDesktop().browse(URI.create(url)); }
        catch (Exception error) { javax.swing.JOptionPane.showMessageDialog(null, "无法打开 " + url + "\n" + error.getMessage()); }
    }

    private void launchExe(java.nio.file.Path path, JButton launch, JButton stop, JLabel state,
                           AtomicReference<ProcessHandle> active, TaskRunner tasks, LogBus log) {
        if (!Files.isRegularFile(path)) {
            javax.swing.JOptionPane.showMessageDialog(this,
                "未找到 RM Stable 抓取程序。\n\n请将新版程序放到：\n" + path
                    + "\n\n也可通过 BOT_WORKSTATION_STABLE_EXE 指定其他位置。",
                "缺少 rm_stable_info.exe", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        Optional<ProcessHandle> existing = exactProcess(path);
        if (existing.isPresent()) {
            active.set(existing.get()); launch.setEnabled(false); stop.setEnabled(true);
            state.setText("检测到后台残留");
            monitor(existing.get(), launch, stop, state, active, tasks, log);
            return;
        }
        launch.setEnabled(false); stop.setEnabled(false); state.setText("启动中…");
        tasks.run(() -> {
            ProcessBuilder builder = new ProcessBuilder(path.toAbsolutePath().normalize().toString());
            builder.directory(path.getParent().toFile());
            Process bootstrap = builder.start();
            Thread.sleep(700);
            return exactProcess(path).orElse(bootstrap.toHandle());
        }, process -> {
            active.set(process);
            stop.setEnabled(process.isAlive());
            state.setText(process.isAlive() ? "已启动" : "已结束");
            log.info("Stable", "新版抓取程序已启动：" + path + " · PID " + process.pid());
            monitor(process, launch, stop, state, active, tasks, log);
        }, error -> {
            active.set(null); launch.setEnabled(true); stop.setEnabled(false); state.setText("启动失败");
            javax.swing.JOptionPane.showMessageDialog(this, "无法启动抓取程序\n" + error.getMessage());
        });
    }

    private void stopExe(java.nio.file.Path path, JButton launch, JButton stop, JLabel state,
                         AtomicReference<ProcessHandle> active, TaskRunner tasks, LogBus log) {
        ProcessHandle process = Optional.ofNullable(active.get()).filter(ProcessHandle::isAlive)
            .orElseGet(() -> exactProcess(path).orElse(null));
        if (process == null) {
            active.set(null); launch.setEnabled(true); stop.setEnabled(false); state.setText("未启动");
            return;
        }
        launch.setEnabled(false); stop.setEnabled(false); state.setText("正在结束…");
        tasks.run(() -> {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            try { process.onExit().get(4, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) {
                process.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.onExit().get(3, TimeUnit.SECONDS);
            }
            return null;
        }, ignored -> {
            active.compareAndSet(process, null); launch.setEnabled(true); stop.setEnabled(false); state.setText("已结束");
            log.info("Stable", "抓取程序已结束 · PID " + process.pid());
        }, error -> {
            launch.setEnabled(false); stop.setEnabled(true); state.setText("结束失败");
            javax.swing.JOptionPane.showMessageDialog(this, "无法结束抓取程序\n" + error.getMessage());
        });
    }

    private static Optional<ProcessHandle> exactProcess(java.nio.file.Path path) {
        String expected = path.toAbsolutePath().normalize().toString();
        return ProcessHandle.allProcesses()
            .filter(handle -> handle.info().command().map(command -> command.equalsIgnoreCase(expected)).orElse(false))
            .max(Comparator.comparingLong(ProcessHandle::pid));
    }

    private static void monitor(ProcessHandle process, JButton launch, JButton stop, JLabel state,
                                AtomicReference<ProcessHandle> active, TaskRunner tasks, LogBus log) {
        tasks.execute(() -> {
            try {
                process.onExit().get();
                SwingUtilities.invokeLater(() -> {
                    if (active.compareAndSet(process, null)) {
                        state.setText("已完成"); launch.setEnabled(true); stop.setEnabled(false);
                    }
                });
                log.info("Stable", "抓取程序已结束 · PID " + process.pid());
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.util.concurrent.ExecutionException error) {
                SwingUtilities.invokeLater(() -> {
                    if (active.compareAndSet(process, null)) {
                        state.setText("状态异常"); launch.setEnabled(true); stop.setEnabled(false);
                    }
                });
            }
        });
    }

    private java.awt.Frame owner() {
        return (java.awt.Frame) javax.swing.SwingUtilities.getWindowAncestor(this);
    }
}
