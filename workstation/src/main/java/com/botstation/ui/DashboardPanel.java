package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.NapCatConfigService;
import com.botstation.core.ProcessSupervisor;
import com.botstation.core.ServiceState;
import com.botstation.core.TaskRunner;
import com.botstation.features.OperationsSettings;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

final class DashboardPanel extends JPanel {
    private final TaskRunner tasks;
    private final ProcessSupervisor services;
    private final NapCatConfigService napCatConfig;
    private final OperationsSettings operations;
    private final LogBus log;
    private final Timer refreshTimer;
    private final ServiceCard songBot = new ServiceCard("SongBot", "群消息、公告调度、猜歌与接口服务 · 8080");
    private final ServiceCard napCat = new ServiceCard("NapCat", "正在读取连接参数…");
    private final ServiceCard dailyAutomation = new ServiceCard("每日歌曲竞猜", "两群每日推荐、竞猜与自动榜单结算");
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    DashboardPanel(BotPaths paths, LogBus log, TaskRunner tasks, ProcessSupervisor services,
                   Runnable openMcz, Runnable openLibrary, Runnable openStable, Runnable openAnnouncement,
                   Runnable openOperations) {
        super(new BorderLayout(0, 16));
        this.tasks = tasks; this.services = services; this.log = log;
        this.napCatConfig = new NapCatConfigService(paths);
        this.operations = new OperationsSettings(paths.songBot);
        setOpaque(false);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(28, 28, 20, 28));

        JButton all = UiKit.primaryButton("全部启用");
        all.addActionListener(event -> execute("全部启用", services::startAll));
        add(UiKit.pageHeader("运行总览", "查看运行状态，或直接打开所需模块。", all), BorderLayout.NORTH);

        ScrollablePanel content = new ScrollablePanel();
        content.setOpaque(false); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JPanel cards = new JPanel(new GridLayout(1, 3, 12, 0));
        cards.setOpaque(false); cards.add(songBot); cards.add(napCat); cards.add(dailyAutomation);
        cards.setMinimumSize(new Dimension(200, 144));
        cards.setPreferredSize(new Dimension(200, 144));
        cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 144));
        content.add(cards); content.add(Box.createVerticalStrut(20));

        JPanel modules = new JPanel(new GridBagLayout()); modules.setOpaque(false);
        addModule(modules, module(NavIcon.Kind.MCZ, "谱面录入与图片设计", "谱面、Combo、音频、波形、封面与日历模板。", "进入制作", openMcz, true), 0, 0, 1);
        addModule(modules, module(NavIcon.Kind.MUSIC, "歌曲信息", "查询和修改 SongBot 歌曲元数据。", "打开曲库", openLibrary, false), 1, 0, 1);
        addModule(modules, module(NavIcon.Kind.STABLE, "Stable 曲库", "维护 XLSX 并同步 CSV 与数据库。", "打开 Stable", openStable, false), 0, 1, 1);
        addModule(modules, module(NavIcon.Kind.ADMIN, "公告及网站管理", "编辑云端公告、图片、附件和网站文章。", "进入管理", openAnnouncement, false), 1, 1, 1);
        addModule(modules, module(NavIcon.Kind.OPERATIONS, "运营中心", "管理每日推荐、竞猜、正式站和 Stable 抓取。", "进入运营", openOperations, false), 0, 2, 2);
        modules.setPreferredSize(new Dimension(300, 316));
        modules.setMinimumSize(new Dimension(300, 316));
        modules.setMaximumSize(new Dimension(Integer.MAX_VALUE, 316));
        content.add(modules); content.add(Box.createVerticalStrut(20));
        content.add(health(paths));
        content.add(Box.createVerticalStrut(8));
        JScrollPane scroll = new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);

        bind(songBot, services::startSongBot, services::stopSongBot, "SongBot");
        bind(napCat, services::startNapCat, services::stopNapCat, "NapCat");
        bind(dailyAutomation,
            () -> operations.setDailyAutomationEnabled(true),
            () -> operations.setDailyAutomationEnabled(false), "每日歌曲竞猜");
        JButton napCatSettings = UiKit.button("连接设置");
        napCatSettings.addActionListener(event -> NapCatConfigDialog.show(this, napCatConfig, this::refreshNapCatDescription));
        napCat.addAction(napCatSettings);
        refreshNapCatDescription();
        refreshTimer = new Timer(6000, event -> refresh());
        refreshTimer.setInitialDelay(50);
        refreshTimer.start();
    }

    private JPanel health(BotPaths paths) {
        JPanel panel = UiKit.card();
        JPanel lines = new JPanel(new GridLayout(0, 2, 16, 8)); lines.setOpaque(false);
        lines.add(UiKit.muted("歌曲数据库")); lines.add(value(paths.songDatabase, Files.exists(paths.songDatabase)));
        lines.add(UiKit.muted("Stable 工作簿")); lines.add(value(paths.stableWorkbook, Files.exists(paths.stableWorkbook)));
        lines.add(UiKit.muted("NapCat 启动器")); lines.add(value(paths.napCat.resolve("napcat.bat"), Files.exists(paths.napCat.resolve("napcat.bat"))));
        lines.add(UiKit.muted("Editor 公网服务")); lines.add(cloudValue());
        panel.add(lines); return panel;
    }

    private JLabel cloudValue() {
        JLabel label = new JLabel("云端托管 · 不依赖本机公网转发");
        label.setFont(DesignTokens.CAPTION); label.setForeground(DesignTokens.SUCCESS);
        return label;
    }

    private JLabel value(java.nio.file.Path path, boolean okay) {
        JLabel label = new JLabel((okay ? "可用 · " : "缺失 · ") + path);
        label.setFont(DesignTokens.CAPTION); label.setForeground(okay ? DesignTokens.SUCCESS : DesignTokens.DANGER);
        return label;
    }

    private JPanel module(NavIcon.Kind icon, String title, String detail, String action, Runnable run, boolean primary) {
        JPanel card = UiKit.card(primary ? DesignTokens.SURFACE_TINT : DesignTokens.SURFACE,
            14, new Insets(16, 16, 16, 16));
        JPanel identity = new JPanel(new BorderLayout(13, 0));
        identity.setOpaque(false);
        identity.add(UiKit.iconBadge(new NavIcon(icon, 21),
            primary ? DesignTokens.ACCENT : DesignTokens.NAV_TEXT,
            primary ? DesignTokens.ACCENT_SOFT : DesignTokens.ICON_SURFACE), BorderLayout.WEST);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(DesignTokens.forText(title, DesignTokens.CARD_TITLE));
        titleLabel.setForeground(DesignTokens.INK);
        copy.add(titleLabel);
        copy.add(Box.createVerticalStrut(7));
        copy.add(UiKit.muted(detail));
        identity.add(copy, BorderLayout.CENTER);
        card.add(identity, BorderLayout.CENTER);

        JButton button = primary ? UiKit.primaryButton(action) : UiKit.button(action);
        button.addActionListener(event -> run.run());
        JPanel actionBox = new JPanel(new GridBagLayout());
        actionBox.setOpaque(false);
        actionBox.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 0));
        actionBox.add(button);
        card.add(actionBox, BorderLayout.EAST);
        return card;
    }

    private void addModule(JPanel modules, JPanel module, int column, int row, int width) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column; constraints.gridy = row; constraints.gridwidth = width;
        constraints.weightx = width; constraints.weighty = 1; constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(row == 0 ? 0 : 12, column == 0 ? 0 : 6, 0, column + width >= 2 ? 0 : 6);
        modules.add(module, constraints);
    }

    private static final class ScrollablePanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) { return Math.max(120, visible.height - 60); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private void bind(ServiceCard card, CheckedAction start, CheckedAction stop, String source) {
        card.start.addActionListener(event -> execute("启用 " + source, start));
        card.stop.addActionListener(event -> execute("停用 " + source, stop));
    }

    private void execute(String label, CheckedAction action) {
        log.info("工作站", label + "…");
        tasks.run(() -> { action.run(); return null; }, value -> refresh(), error -> {
            log.error("工作站", label + "失败：" + error.getMessage());
            JOptionPane.showMessageDialog(this, label + "失败\n" + error.getMessage(), "操作失败", JOptionPane.ERROR_MESSAGE);
            refresh();
        });
    }

    private void refresh() {
        if (stopped.get()) return;
        if (!refreshing.compareAndSet(false, true)) return;
        tasks.run(() -> new DashboardState(services.songBotState(), services.napCatState(),
            operations.dailyAutomationEnabled()), value -> {
            songBot.setState(value.songBot);
            napCat.setState(value.napCat);
            dailyAutomation.setState(value.dailyAutomation ? ServiceState.RUNNING : ServiceState.STOPPED);
            refreshNapCatDescription(); refreshing.set(false);
        }, error -> { refreshing.set(false); log.warn("状态检测", error.getMessage()); });
    }

    private void refreshNapCatDescription() {
        try { napCat.setDescription(napCatConfig.load().summary()); }
        catch (Exception error) { napCat.setDescription("连接配置不可读 · 请打开设置检查"); }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static final class DashboardState {
        final ServiceState songBot;
        final ServiceState napCat;
        final boolean dailyAutomation;
        DashboardState(ServiceState songBot, ServiceState napCat, boolean dailyAutomation) {
            this.songBot = songBot;
            this.napCat = napCat;
            this.dailyAutomation = dailyAutomation;
        }
    }

    void stopRefresh() { stopped.set(true); refreshTimer.stop(); }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        g.setColor(DesignTokens.GRADIENT_CENTER);
        g.fillRect(0, 0, getWidth(), getHeight());
        paintLight(g, width, height, new Point2D.Float(width * .14f, height * .18f), Math.max(width, height) * .72f,
            new Color(226, 235, 255, 175));
        paintLight(g, width, height, new Point2D.Float(width * .86f, height * .27f), Math.max(width, height) * .66f,
            new Color(255, 226, 240, 155));
        paintLight(g, width, height, new Point2D.Float(width * .55f, height * .88f), Math.max(width, height) * .58f,
            new Color(242, 232, 255, 120));
        g.dispose();
        super.paintComponent(graphics);
    }

    private static void paintLight(Graphics2D g, int width, int height, Point2D center, float radius, Color color) {
        g.setPaint(new RadialGradientPaint(center, radius, new float[]{0f, 1f},
            new Color[]{color, new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)}));
        g.fillRect(0, 0, width, height);
    }
}
