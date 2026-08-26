package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.ProcessSupervisor;
import com.botstation.core.TaskRunner;
import com.botstation.core.UpdateService;
import com.botstation.features.SongLibraryPanel;
import com.botstation.features.StableWorkbookPanel;
import com.botstation.mobile.MobileControlServer;
import com.botstation.security.AdminGate;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public final class WorkbenchFrame extends JFrame {
    private final BotPaths paths;
    private final LogBus log;
    private final TaskRunner tasks;
    private final ProcessSupervisor services;
    private final AdminGate adminGate;
    private final MobileControlServer mobileServer;
    private final UpdateService updates;
    private MczWorkspacePanel mczWorkspace;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JPanel rail = new RailPanel();
    private final JLabel brand = new JLabel("Bot 工作站");
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final java.util.List<NavButton> navButtons = new ArrayList<>();
    private String current = "overview";
    private boolean compact;
    private boolean resourcesClosed;

    public WorkbenchFrame(BotPaths paths, LogBus log, TaskRunner tasks, ProcessSupervisor services) {
        super("Bot 工作站");
        this.paths = paths;
        this.log = log;
        this.tasks = tasks;
        this.services = services;
        this.adminGate = new AdminGate(paths, log);
        this.updates = new UpdateService(paths, log);
        this.mobileServer = new MobileControlServer(paths, log, services, updates,
            key -> javax.swing.SwingUtilities.invokeLater(() -> showPage(key)));
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        if (AppIcon.image() != null) setIconImage(AppIcon.image());
        setMinimumSize(new Dimension(980, 680));
        setSize(1420, 860);
        setLocationRelativeTo(null);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().setBackground(DesignTokens.PAPER);
        buildPages();
        buildRail();
        getContentPane().add(rail, BorderLayout.WEST);
        getContentPane().add(content, BorderLayout.CENTER);
        showPage("overview");
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateCompact(getWidth() < 1080); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) {
                ensureMczWorkspace();
                startMobileCompanion();
                checkForUpdates();
            }
            @Override public void windowClosing(WindowEvent event) {
                closeResources();
                dispose();
                // MczMaker owns legacy non-daemon workers. The robot and NapCat are
                // separate processes, so exiting this UI JVM does not stop services.
                System.exit(0);
            }
            @Override public void windowClosed(WindowEvent event) {
                closeResources();
            }
        });
        log.info("工作站", "控制中心已启动；关闭工作站不会停止机器人服务");
    }

    private void startMobileCompanion() {
        try {
            mobileServer.start();
        } catch (Exception error) {
            log.warn("手机端", "自动启用失败：" + error.getMessage());
        }
    }

    private void checkForUpdates() {
        tasks.run(updates::check, release -> {
            if (!updates.available(release)) return;
            String note = release.notes == null || release.notes.trim().isEmpty() ? "" : "\n\n" + release.notes.trim();
            int choice = JOptionPane.showConfirmDialog(this,
                "发现新版本 " + release.version + "（当前 " + UpdateService.CURRENT_VERSION + "）" + note
                    + "\n\n是否下载并一键更新？",
                "Bot 工作站更新", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) installUpdate(release);
        }, error -> log.warn("自动更新", "检查失败：" + error.getMessage()));
    }

    private void installUpdate(UpdateService.ReleaseInfo release) {
        JOptionPane.showMessageDialog(this, "正在后台下载并校验安装包。完成后会自动启动安装程序。",
            "正在更新", JOptionPane.INFORMATION_MESSAGE);
        tasks.run(() -> updates.downloadAndLaunch(release), installer -> {
            closeResources();
            dispose();
            System.exit(0);
        }, error -> JOptionPane.showMessageDialog(this, "更新失败\n" + error.getMessage(),
            "无法更新", JOptionPane.ERROR_MESSAGE));
    }

    private void closeResources() {
        if (resourcesClosed) return;
        resourcesClosed = true;
        for (Page page : pages.values())
            if (page.view instanceof DashboardPanel) ((DashboardPanel) page.view).stopRefresh();
        mobileServer.close();
        tasks.close();
    }

    private void buildPages() {
        register("overview", "总览", NavIcon.Kind.OVERVIEW, () -> new DashboardPanel(paths, log, tasks, services,
            () -> showPage("mcz"),
            () -> showPage("library"),
            () -> showPage("stable"),
            () -> showPage("announcements"),
            () -> showPage("tools")));
        register("library", "歌曲信息", NavIcon.Kind.MUSIC, () -> new SongLibraryPanel(paths, log, tasks));
        register("mcz", "谱面录入与图片设计", NavIcon.Kind.MCZ, this::ensureMczWorkspace);
        register("stable", "Stable 曲库", NavIcon.Kind.STABLE, () -> new StableWorkbookPanel(paths, log, tasks));
        registerAdmin("announcements", "公告及网站管理", NavIcon.Kind.ADMIN,
            session -> new AnnouncementPanel(paths, log, tasks, session));
        register("tools", "运营中心", NavIcon.Kind.OPERATIONS, () -> new ToolsPanel(paths, log, tasks));
        register("mobile", "手机端", NavIcon.Kind.MOBILE, () -> new MobilePanel(mobileServer));
    }

    private void register(String key, String label, NavIcon.Kind icon, Supplier<JComponent> supplier) {
        pages.put(key, new Page(key, label, icon, supplier, false));
    }

    private MczWorkspacePanel ensureMczWorkspace() {
        if (mczWorkspace == null) mczWorkspace = new MczWorkspacePanel(paths, log, tasks);
        return mczWorkspace;
    }

    private void registerAdmin(String key, String label, NavIcon.Kind icon,
                               Function<AdminGate.AdminSession, JComponent> supplier) {
        pages.put(key, new Page(key, label, icon,
            () -> supplier.apply(adminGate.requireCurrent()), true));
    }

    private void buildRail() {
        rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
        rail.setBackground(DesignTokens.NAV);
        rail.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, DesignTokens.BORDER),
            BorderFactory.createEmptyBorder(22, 16, 16, 16)));
        rail.setPreferredSize(new Dimension(224, 10));
        brand.setIcon(AppIcon.image() == null
            ? new NavIcon(NavIcon.Kind.BRAND, 30)
            : new javax.swing.ImageIcon(AppIcon.image().getScaledInstance(30, 30, java.awt.Image.SCALE_SMOOTH)));
        brand.setIconTextGap(12);
        brand.setFont(DesignTokens.forText("Bot 工作站", DesignTokens.BRAND));
        brand.setForeground(DesignTokens.INK);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        rail.add(brand);
        rail.add(Box.createVerticalStrut(24));
        for (Page page : pages.values()) {
            NavButton button = new NavButton(page);
            button.addActionListener(event -> showPage(page.key));
            navButtons.add(button);
            rail.add(button);
            rail.add(Box.createVerticalStrut(6));
        }
        rail.add(Box.createVerticalGlue());
    }

    private void showPage(String key) {
        Page page = pages.get(key);
        if (page == null) return;
        if (page.adminProtected && !adminGate.authorize(this)) return;
        if (page.component == null) {
            page.view = page.supplier.get();
            page.component = PageTransition.wrap(page.view);
            content.add(page.component, page.key);
        }
        current = key;
        cards.show(content, key);
        PageTransition.play(page.component);
        setTitle(page.label + " · Bot 工作站");
        for (NavButton button : navButtons) button.setSelectedState(button.page.key.equals(key));
    }

    private void updateCompact(boolean value) {
        if (compact == value) return;
        compact = value;
        rail.setPreferredSize(new Dimension(value ? 76 : 224, 10));
        brand.setText(value ? null : "Bot 工作站");
        for (NavButton button : navButtons) button.refreshText();
        rail.revalidate();
    }

    private final class NavButton extends JButton {
        private final Page page;
        private boolean selected;
        private boolean hovered;

        NavButton(Page page) {
            this.page = page;
            setHorizontalAlignment(SwingConstants.LEFT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setPreferredSize(new Dimension(190, 44));
            setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 12));
            setFocusPainted(true);
            setFont(DesignTokens.NAV_ITEM);
            setForeground(DesignTokens.NAV_TEXT);
            setBackground(DesignTokens.NAV);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setIcon(new NavIcon(page.icon, 20));
            setIconTextGap(14);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) { hovered = true; refreshVisual(); }
                @Override public void mouseExited(MouseEvent event) { hovered = false; refreshVisual(); }
            });
            refreshText();
            refreshVisual();
        }

        void refreshText() {
            setText(compact ? null : page.label);
            setHorizontalAlignment(compact ? SwingConstants.CENTER : SwingConstants.LEFT);
        }

        void setSelectedState(boolean selected) {
            this.selected = selected;
            refreshVisual();
        }

        private void refreshVisual() {
            setForeground(selected ? DesignTokens.ACCENT : DesignTokens.NAV_TEXT);
            setFont(selected ? DesignTokens.NAV_ACTIVE : DesignTokens.NAV_ITEM);
            setBorder(BorderFactory.createEmptyBorder(0, compact ? 10 : 16, 0, 12));
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected || hovered) {
                g.setColor(selected ? DesignTokens.ACCENT_SOFT : DesignTokens.NAV_HOVER);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
            if (selected && !compact) {
                g.setColor(DesignTokens.ACCENT);
                g.fillRoundRect(0, 10, 4, Math.max(20, getHeight() - 20), 4, 4);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class RailPanel extends JPanel { }

    private static final class Page {
        final String key;
        final String label;
        final NavIcon.Kind icon;
        final Supplier<JComponent> supplier;
        final boolean adminProtected;
        JComponent view;
        JComponent component;

        Page(String key, String label, NavIcon.Kind icon, Supplier<JComponent> supplier, boolean adminProtected) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.supplier = supplier;
            this.adminProtected = adminProtected;
        }

        @Override public String toString() { return label + (adminProtected ? "  · 需管理员验证" : ""); }
    }
}
