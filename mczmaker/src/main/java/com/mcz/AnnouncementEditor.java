package com.mcz;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 公告编辑器 — 卡片式分页布局，支持定时发送 QQ 群公告
 */
public class AnnouncementEditor extends JPanel {

    private final File announceFile;
    private final File announceAssetDir;
    private final java.util.List<Map<String, Object>> announcements = new ArrayList<>();
    private CloudAnnouncementStore cloudStore;
    private boolean cloudMode;
    private String loadedRevision = "";
    private final int SLOTS = 12;
    private final int[] curPage = {0};
    private JPanel cardPanel;
    private GridLayout cardGrid;
    private JScrollPane cardsScroll;
    private JLabel pageLabel;
    private UiKit.ModernButton prevBtn, nextBtn;
    private final Runnable onClose;

    private static final class AttachmentRef {
        final File file;
        final String storedToken;
        final String displayName;
        AttachmentRef(File file, String storedToken, String displayName) {
            this.file = file;
            this.storedToken = storedToken == null ? "" : storedToken;
            this.displayName = sanitizeDisplayName(displayName,
                file != null ? file.getName() : AnnouncementEditor.displayName(this.storedToken));
        }
    }

    // --- 现代调色盘 ---
    private static final Color BG_MAIN      = new Color(245, 246, 250);
    private static final Color CARD_BG      = Color.WHITE;
    private static final Color CARD_BORDER  = new Color(225, 228, 235);
    private static final Color ACCENT       = new Color(59, 130, 246);   // 蓝色主调
    private static final Color ACCENT_HOVER = new Color(37, 99, 235);
    private static final Color DANGER       = new Color(239, 68, 68);
    private static final Color TEXT_PRIMARY = new Color(30, 41, 59);
    private static final Color TEXT_MUTED   = new Color(148, 163, 184);
    private static final Color INPUT_BORDER = new Color(203, 213, 225);
    private static final Color INPUT_FOCUS  = new Color(147, 197, 253);
    private static final Font   FONT_LABEL  = new Font("Microsoft YaHei UI", Font.BOLD, 13);
    private static final Font   FONT_INPUT  = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    private static final Font   FONT_CARD   = new Font("Microsoft YaHei UI", Font.PLAIN, 11);
    private static final String[][] CONTENT_TEMPLATES = {
        {"专辑模板", "【更新公告】专辑《》已开放下载!\n——————————\n内含歌曲：\n"},
        {"Event模板", "【茶韵Event轮换公告】\n——————————\n▍Event轮换更新预告🔸\n《》将于当前商店活动中专辑结束后限时上架，届时将开启排行榜进行计分。\n▍Event开放时间🔸\nxx/xx 08:00-xx/xx 08:00\n▍专辑曲目🔸\n\n▍活动说明🔸\n此专辑为xx第x期Event，活动成绩参与排名和热度总计。\n"},
        {"单曲模板", "【吼吼点唱机轮换公告】\n————————\n《》→《》\n"},
        {"观影封面", "//观影封面\n"},
        {"观影定位", "//观影定位\n"}
    };

    public AnnouncementEditor(Runnable onClose) {
        this.onClose = onClose;
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setOpaque(false);

        File desktopDir = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        if (desktopDir == null || !desktopDir.exists())
            desktopDir = new File(System.getProperty("user.home"));
        File songBotDir = new File(desktopDir, "SongBot");
        File dataDir = new File(songBotDir, "data");
        announceAssetDir = new File(songBotDir, "announce_files");
        dataDir.mkdirs();
        announceAssetDir.mkdirs();
        announceFile = new File(dataDir, "announcements.json");

        cloudMode = CloudAnnouncementStore.isCloudMode(songBotDir);
        if (cloudMode) {
            try {
                cloudStore = CloudAnnouncementStore.fromSongBot(songBotDir);
                loadFromCloud();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "云公告服务连接失败，本次不会读取或覆盖本地旧文件：\n" + ex.getMessage(),
                    "公告服务不可用", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            loadFromJson();
        }
        buildMainUI();
        refreshCards();
    }

    // ========== JSON 持久化 ==========

    private void loadFromJson() {
        announcements.clear();
        if (!announceFile.exists()) {
            loadedRevision = revisionOf("[]");
            return;
        }
        try {
            String raw = new String(Files.readAllBytes(announceFile.toPath()), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) raw = "[]";
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                for (String key : object.keySet()) {
                    Object value = object.opt(key);
                    item.put(key, value == null || value == JSONObject.NULL ? "" : String.valueOf(value));
                }
                announcements.add(item);
            }
            loadedRevision = revisionOf(array.toString());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "SongBot 公告文件读取失败，原文件未被修改：\n" + ex.getMessage(),
                "公告数据错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadFromCloud() throws IOException {
        announcements.clear();
        announcements.addAll(cloudStore.load());
        loadedRevision = "cloud";
    }

    boolean saveToJson() {
        try {
            String currentRaw = announceFile.isFile()
                ? new String(Files.readAllBytes(announceFile.toPath()), StandardCharsets.UTF_8).trim() : "[]";
            if (currentRaw.isEmpty()) currentRaw = "[]";
            String currentRevision = revisionOf(new JSONArray(currentRaw).toString());
            if (!loadedRevision.isEmpty() && !loadedRevision.equals(currentRevision)) {
                loadFromJson();
                refreshCards();
                JOptionPane.showMessageDialog(this,
                    "公告已被网页或 SongBot 修改。为防止覆盖新内容，已重新载入，请再次编辑。",
                    "检测到更新", JOptionPane.WARNING_MESSAGE);
                return false;
            }

            JSONArray array = new JSONArray();
            for (Map<String, Object> item : announcements) array.put(new JSONObject(item));
            String json = array.toString(2);
            File parent = announceFile.getCanonicalFile().getParentFile();
            parent.mkdirs();
            File temp = File.createTempFile(".announcements-", ".tmp", parent);
            try {
                Files.write(temp.toPath(), json.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(temp.toPath(), announceFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp.toPath(), announceFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp.toPath());
            }
            loadedRevision = revisionOf(array.toString());
            appendDesktopAudit(array.length(), loadedRevision);
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                "公告保存失败，原文件已保留：\n" + ex.getMessage(),
                "保存失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static String revisionOf(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : digest) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (Exception ex) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private void appendDesktopAudit(int count, String revision) {
        try {
            File logDir = new File(announceFile.getParentFile().getParentFile(), "logs");
            logDir.mkdirs();
            JSONObject line = new JSONObject()
                .put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                .put("event", "DESKTOP_EDITOR_SAVE_COMMITTED")
                .put("actor", new JSONObject().put("kind", "mczmaker").put("device", "desktop-editor").put("ip", "local"))
                .put("detail", new JSONObject().put("count", count).put("revision", revision));
            Files.write(new File(logDir, "announcement-audit.jsonl").toPath(),
                (line.toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    public static String esc(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }

    // ========== 主界面 ==========

    private void buildMainUI() {
        cardGrid = new GridLayout(0, 3, 12, 12);
        cardPanel = new JPanel(cardGrid);
        cardPanel.setOpaque(false);

        pageLabel = new JLabel("", JLabel.CENTER);
        pageLabel.setFont(FONT_INPUT);

        prevBtn = makeOutlineBtn("← 上一页");
        prevBtn.addActionListener(e -> { curPage[0]--; refreshCards(); });

        nextBtn = makeOutlineBtn("下一页 →");
        nextBtn.addActionListener(e -> { curPage[0]++; refreshCards(); });

        UiKit.ModernButton closeBtn = makeOutlineBtn("关闭");
        closeBtn.addActionListener(e -> onClose.run());

        JPanel pageNav = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        pageNav.setOpaque(false);
        pageNav.add(prevBtn); pageNav.add(pageLabel); pageNav.add(nextBtn);

        JPanel botPanel = new JPanel(new BorderLayout(0, 5));
        botPanel.setOpaque(false);
        botPanel.add(pageNav, BorderLayout.NORTH);
        botPanel.add(closeBtn, BorderLayout.SOUTH);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        JPanel cardsTop = new WidthTrackingPanel(new BorderLayout());
        cardsTop.setOpaque(false);
        cardsTop.add(cardPanel, BorderLayout.NORTH);
        cardsScroll = new JScrollPane(cardsTop, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        cardsScroll.setBorder(null);
        cardsScroll.setOpaque(false);
        cardsScroll.getViewport().setOpaque(false);
        cardsScroll.getVerticalScrollBar().setUnitIncrement(18);
        cardsScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateCardColumns(); }
        });
        mainPanel.add(cardsScroll, BorderLayout.CENTER);
        mainPanel.add(botPanel, BorderLayout.SOUTH);
        add(mainPanel, BorderLayout.CENTER);
    }

    void refreshCards() {
        cardPanel.removeAll();
        int totalCards = announcements.size() + 1;
        int totalPages = Math.max(1, (totalCards + SLOTS - 1) / SLOTS);
        if (curPage[0] >= totalPages) curPage[0] = totalPages - 1;
        int start = curPage[0] * SLOTS;
        int end = Math.min(start + SLOTS, totalCards);
        for (int i = start; i < end; i++) {
            JPanel slot = new JPanel(new BorderLayout()) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(CARD_BG);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.dispose();
                }
            };
            slot.setPreferredSize(new Dimension(210, 164));
            slot.setBorder(BorderFactory.createCompoundBorder(
                new UiKit.ModernRoundedBorder(CARD_BORDER, 1, 12),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            slot.setOpaque(false);

            if (i < announcements.size()) {
                Map<String, Object> a = announcements.get(i);
                String fullContent = String.valueOf(a.getOrDefault("content", "")).replace("\\n", " ");
                String cnt = clipCardSummary(fullContent);
                String tm = String.valueOf(a.getOrDefault("time", "?"));
                String gr = String.valueOf(a.getOrDefault("groupId", "?"));
                // 预设群名映射（隐藏群不展示真实ID）
                if ("2000000004".equals(gr)) gr = "绿茶";
                else if ("2000000003".equals(gr)) gr = "红茶";
                else if ("2000000002".equals(gr)) gr = "测试群1";
                else if ("2000000001".equals(gr)) gr = "测试群2";
                boolean sent = "true".equals(a.getOrDefault("sent", ""));
                JPanel info = new JPanel();
                info.setOpaque(false);
                info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
                JLabel group = new JLabel(gr); group.setFont(FONT_LABEL); group.setForeground(TEXT_PRIMARY);
                JLabel time = new JLabel(tm); time.setFont(FONT_CARD); time.setForeground(new Color(100, 116, 139));
                JLabel status = new JLabel(sent ? "已发送" : "待发送"); status.setFont(FONT_INPUT);
                status.setForeground(sent ? new Color(22, 163, 74) : new Color(217, 119, 6));
                JLabel summary = new JLabel(cnt.isEmpty() ? "（无正文）" : cnt); summary.setFont(FONT_CARD); summary.setForeground(new Color(51, 65, 85));
                summary.setToolTipText(String.valueOf(a.getOrDefault("content", "")));
                info.add(group); info.add(Box.createVerticalStrut(8)); info.add(time);
                info.add(Box.createVerticalStrut(5)); info.add(status); info.add(Box.createVerticalStrut(10)); info.add(summary);
                JPanel body = new JPanel(new BorderLayout(14, 0));
                body.setOpaque(false);
                JPanel accentWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                accentWrap.setOpaque(false);
                accentWrap.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
                accentWrap.add(new StatusMark(sent ? new Color(74, 222, 128) : new Color(251, 191, 36)));
                body.add(accentWrap, BorderLayout.WEST);
                body.add(info, BorderLayout.CENTER);
                slot.add(body, BorderLayout.CENTER);

                final int fi = i;
                slot.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent ev) { openEditor(fi); }
                    public void mouseEntered(MouseEvent ev) { slot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
                });

                UiKit.ModernButton delB = new UiKit.ModernButton("×");
                delB.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
                delB.setForeground(TEXT_MUTED);
                delB.setPreferredSize(new Dimension(24, 20));
                delB.setMaximumSize(new Dimension(24, 20));
                delB.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
                delB.setCustomColors(CARD_BG, new Color(254, 226, 226), new Color(254, 202, 202));
                delB.addActionListener(ev -> {
                    if (cloudMode) {
                        try {
                            if (cloudStore == null) throw new IOException("云公告客户端未初始化");
                            cloudStore.delete(a);
                            announcements.remove(fi);
                            refreshCards();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(this, ex.getMessage(), "删除失败", JOptionPane.ERROR_MESSAGE);
                            try { loadFromCloud(); refreshCards(); } catch (Exception ignored) {}
                        }
                    } else {
                        announcements.remove(fi);
                        if (saveToJson()) refreshCards();
                    }
                });
                JPanel topR = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                topR.setOpaque(false);
                topR.add(delB);
                slot.add(topR, BorderLayout.NORTH);
            } else if (i == announcements.size()) {
                UiKit.ModernButton plus = new UiKit.ModernButton("+");
                plus.setFont(new Font("Microsoft YaHei", Font.PLAIN, 24));
                plus.setCustomColors(new Color(255, 240, 245), new Color(255, 228, 236), new Color(252, 205, 220));
                plus.setBorderColor(new Color(244, 143, 177));
                plus.setCornerRadius(50);
                plus.setPreferredSize(new Dimension(48, 48));
                plus.setMaximumSize(new Dimension(48, 48));
                plus.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
                plus.setForeground(ACCENT);
                plus.addActionListener(ev -> openEditor(-1));
                JPanel plusWrap = new JPanel(new GridBagLayout());
                plusWrap.setOpaque(false);
                plusWrap.add(plus);
                slot.add(plusWrap, BorderLayout.CENTER);
            }
            cardPanel.add(slot);
        }
        cardPanel.revalidate();
        cardPanel.repaint();
        updateCardColumns();
        pageLabel.setText("第 " + (curPage[0] + 1) + "/" + totalPages + " 页 (" + announcements.size() + " 个公告)");
        prevBtn.setEnabled(curPage[0] > 0);
        nextBtn.setEnabled(curPage[0] + 1 < totalPages);
    }

    private void updateCardColumns() {
        if (cardsScroll == null || cardGrid == null) return;
        int width = cardsScroll.getViewport().getExtentSize().width;
        if (width <= 0) return;
        int columns = cardColumnsForWidth(width);
        if (cardGrid.getColumns() != columns) {
            cardGrid.setColumns(columns);
            cardPanel.revalidate();
            cardPanel.repaint();
        }
    }

    static int cardColumnsForWidth(int width) {
        final int minimumCardWidth = 300;
        final int gap = 12;
        return Math.max(1, Math.min(4, (Math.max(0, width) + gap) / (minimumCardWidth + gap)));
    }

    private static final class StatusMark extends JPanel {
        private final Color color;
        StatusMark(Color color) {
            this.color = color;
            setOpaque(false);
            setPreferredSize(new Dimension(4, 34));
            setMinimumSize(new Dimension(4, 34));
            setMaximumSize(new Dimension(4, 34));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
            g.dispose();
        }
    }

    private static String clipCardSummary(String text) {
        if (text == null || text.isEmpty()) return "";
        JLabel probe = new JLabel();
        FontMetrics metrics = probe.getFontMetrics(FONT_CARD);
        final String suffix = "…";
        final int maximumWidth = 250;
        if (metrics.stringWidth(text) <= maximumWidth) return text;
        int low = 0, high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metrics.stringWidth(text.substring(0, middle) + suffix) <= maximumWidth) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, low) + suffix;
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(18, orientation == SwingConstants.VERTICAL ? visibleRect.height - 18 : visibleRect.width - 18);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ========== 公告编辑弹窗 ==========

    private void openEditor(int idx) {
        Map<String, Object> existing = (idx >= 0 && idx < announcements.size()) ? announcements.get(idx) : null;
        boolean isNew = existing == null;
        JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                isNew ? "新建公告" : "编辑公告", true);
        dlg.setBackground(BG_MAIN);

        // --- 内容区 ---
        JTextArea cArea = new JTextArea(6, 35);
        cArea.setLineWrap(true); cArea.setWrapStyleWord(true);
        // Logical Dialog keeps Java's CJK/Japanese/Korean/emoji fallback chain,
        // preventing template symbols from becoming square replacement glyphs.
        cArea.setFont(new Font("Dialog", Font.PLAIN, 13));
        cArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(INPUT_BORDER, 1),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        cArea.setBackground(new Color(250, 250, 252));
        if (existing != null) cArea.setText(String.valueOf(existing.getOrDefault("content", "")).replace("\\n", "\n"));
        JScrollPane cScroll = new JScrollPane(cArea);
        cScroll.setBorder(BorderFactory.createEmptyBorder());
        cScroll.setPreferredSize(new Dimension(0, 110));

        JPanel templateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        templateRow.setOpaque(false);
        for (String[] template : CONTENT_TEMPLATES) {
            UiKit.ModernButton templateButton = makeSmallBtn(template[0]);
            templateButton.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            templateButton.addActionListener(event -> cArea.setText(template[1]));
            templateRow.add(templateButton);
        }
        JPanel contentEditor = new JPanel(new BorderLayout(0, 7));
        contentEditor.setOpaque(false);
        contentEditor.add(templateRow, BorderLayout.NORTH);
        contentEditor.add(cScroll, BorderLayout.CENTER);

        // --- 字段输入 ---
        String presetTime = existing != null ? String.valueOf(existing.getOrDefault("time", "")) : "";
        DateTimePicker timePicker = new DateTimePicker(presetTime);
        String[] realGid = {existing != null ? String.valueOf(existing.getOrDefault("groupId", "2000000004")) : "2000000004"};
        JTextField gField = styledField("2000000001".equals(realGid[0]) ? "测试群2" : realGid[0]);

        // 群号快捷预设 (▼ 下拉菜单)
        String[][] GROUP_PRESETS = {
            {"绿茶",   "2000000004"},
            {"红茶",   "2000000003"},
            {"测试群1", "2000000002"},
            {"测试群2", "2000000001"}, // 真实ID写入公告，界面不展示
        };
        JPopupMenu groupMenu = new JPopupMenu();
        groupMenu.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225), 1));
        JPanel menuContent = new JPanel();
        menuContent.setLayout(new BoxLayout(menuContent, BoxLayout.Y_AXIS));
        menuContent.setBackground(Color.WHITE);
        for (int i = 0; i < GROUP_PRESETS.length; i++) {
            String name = GROUP_PRESETS[i][0];
            String gid  = GROUP_PRESETS[i][1];
            boolean hidden = (i == 3);
            String label = hidden ? name : (name + "  " + gid);
            JLabel item = new JLabel(label);
            item.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            item.setOpaque(true);
            item.setBackground(Color.WHITE);
            item.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            final String targetGid = gid;
            final boolean isHidden = hidden;
            item.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { item.setBackground(new Color(241, 245, 249)); }
                public void mouseExited(MouseEvent e) { item.setBackground(Color.WHITE); }
                public void mouseClicked(MouseEvent e) {
                    realGid[0] = targetGid;
                    gField.setText(isHidden ? "测试群2" : targetGid);
                    groupMenu.setVisible(false);
                }
            });
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            menuContent.add(item);
        }
        groupMenu.add(menuContent);
        JButton arrowBtn = new JButton("▼");
        arrowBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 9));
        arrowBtn.setFocusPainted(false);
        arrowBtn.setMargin(new Insets(0, 0, 0, 0));
        arrowBtn.setPreferredSize(new Dimension(30, 30));
        arrowBtn.setBackground(Color.WHITE);
        arrowBtn.setBorder(BorderFactory.createLineBorder(INPUT_BORDER, 1));
        arrowBtn.addActionListener(e -> groupMenu.show(arrowBtn, 0, arrowBtn.getHeight()));

        JPanel gidRow = new JPanel(new BorderLayout(0, 0));
        gidRow.setOpaque(false);
        gidRow.add(gField, BorderLayout.CENTER);
        gidRow.add(arrowBtn, BorderLayout.EAST);

        // --- 开关 ---
        JCheckBox pBox = styledCheck("置顶公告", existing != null && "true".equals(existing.get("pin")));
        JCheckBox cfBox = styledCheck("需群成员确认", existing != null && "true".equals(existing.get("confirm")));

        // --- 图片 ---
        final File[] imgF = {null};
        final String[] existingImageToken = {""};
        JLabel imgLbl = new JLabel("未选择");
        imgLbl.setFont(FONT_CARD);
        imgLbl.setForeground(TEXT_MUTED);
        if (existing != null) {
            String ip = String.valueOf(existing.getOrDefault("image", ""));
            existingImageToken[0] = ip;
            File resolved = resolveStoredAsset(ip);
            if (resolved != null && resolved.isFile()) {
                imgF[0] = resolved;
                imgLbl.setText(resolved.getName());
                imgLbl.setForeground(new Color(34, 197, 94));
            } else if (cloudMode && !ip.isEmpty()) {
                imgLbl.setText(displayName(ip));
                imgLbl.setForeground(new Color(34, 197, 94));
            }
        }
        UiKit.ModernButton imgBtn = makeSmallBtn("选择图片");
        imgBtn.addActionListener(ev -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(dlg) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                if (f.length() < 2 * 1024 * 1024) {
                    imgF[0] = f;
                    existingImageToken[0] = "";
                    imgLbl.setText(f.getName());
                    imgLbl.setForeground(new Color(34, 197, 94));
                }
                else JOptionPane.showMessageDialog(dlg, "图片不能超过 2 MB！", "提示", JOptionPane.WARNING_MESSAGE);
            }
        });

        // --- 附件 ---
        java.util.List<AttachmentRef> aFiles = new ArrayList<>();
        DefaultListModel<String> aMod = new DefaultListModel<>();
        if (existing != null) {
            String[] storedAttachments = String.valueOf(existing.getOrDefault("attach", "")).split("\\|");
            java.util.List<String> storedNames = attachmentNames(existing.get("attachmentNames"));
            for (int attachmentIndex = 0; attachmentIndex < storedAttachments.length; attachmentIndex++) {
                String p = storedAttachments[attachmentIndex];
                File resolved = resolveStoredAsset(p);
                if (!p.isEmpty() && ((resolved != null && resolved.isFile()) || cloudMode)) {
                    String explicitName = attachmentIndex < storedNames.size() ? storedNames.get(attachmentIndex) : "";
                    AttachmentRef ref = new AttachmentRef(resolved, p, explicitName);
                    aFiles.add(ref);
                    aMod.addElement(ref.displayName);
                }
            }
        }
        JList<String> aList = new JList<>(aMod);
        aList.setFont(FONT_CARD);
        aList.setBackground(CARD_BG);
        aList.setSelectionBackground(new Color(219, 234, 254));
        aList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent ev) {
                if (ev.getClickCount() == 2) {
                    int idx = aList.getSelectedIndex();
                    if (idx >= 0) { aFiles.remove(idx); aMod.remove(idx); }
                }
            }
        });
        JScrollPane aScroll = new JScrollPane(aList);
        aScroll.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(INPUT_BORDER, 1),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        aScroll.setPreferredSize(new Dimension(0, 60));
        // 拖拽文件到附件列表
        aScroll.setTransferHandler(new TransferHandler() {
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    java.util.List<File> files = (java.util.List<File>)
                        support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) { aFiles.add(new AttachmentRef(f, "", f.getName())); aMod.addElement(f.getName()); }
                    return true;
                } catch (Exception ex) { return false; }
            }
        });
        aScroll.setToolTipText("拖拽文件到此处添加附件");

        // --- 组装卡片式布局 ---
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        content.add(sectionCard("公告内容", contentEditor));
        content.add(Box.createVerticalStrut(10));
        content.add(sectionCard("发布时间", timePicker));
        content.add(Box.createVerticalStrut(10));
        content.add(sectionCard("目标群号", gidRow));
        content.add(Box.createVerticalStrut(10));
        content.add(sectionCard("图片附件 (≤2MB)",
            hbox(5, imgBtn, imgLbl, Box.createHorizontalGlue())));
        content.add(Box.createVerticalStrut(10));
        content.add(sectionCard("附件列表 (拖拽文件到下方区域)",
            aScroll));
        content.add(Box.createVerticalStrut(10));
        content.add(sectionCard("发送选项",
            hbox(20, pBox, cfBox, Box.createHorizontalGlue())));

        // --- 按钮栏 ---
        UiKit.ModernButton saveB = new UiKit.ModernButton("保存公告");
        saveB.setFont(FONT_LABEL);
        saveB.setCustomColors(Color.WHITE, new Color(241, 245, 249), new Color(226, 232, 240));
        saveB.setBorderColor(INPUT_BORDER);

        UiKit.ModernButton cancelB = new UiKit.ModernButton("取消");
        cancelB.setFont(FONT_LABEL);
        cancelB.setCustomColors(Color.WHITE, new Color(241, 245, 249), new Color(226, 232, 240));
        cancelB.setBorderColor(INPUT_BORDER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnBar.setOpaque(false);
        btnBar.add(cancelB); btnBar.add(saveB);

        // --- 保存逻辑 ---
        saveB.addActionListener(ev -> {
            String ct = cArea.getText().trim();
            if (ct.isEmpty()) { JOptionPane.showMessageDialog(dlg, "公告内容不能为空！"); return; }
            if (ct.length() > 600) { JOptionPane.showMessageDialog(dlg, "公告内容不能超过 600 字！当前 " + ct.length() + " 字"); return; }
            String gid = "测试群2".equals(gField.getText().trim()) ? realGid[0] : gField.getText().trim();
            try {
                Map<String, Object> target = existing;
                if (cloudMode) {
                    target = new LinkedHashMap<>();
                    if (existing != null) target.putAll(existing);
                }
                persist(target, ct, timePicker.getFormatted(), gid,
                        pBox.isSelected(), cfBox.isSelected(), imgF[0], existingImageToken[0], aFiles);
                if (cloudMode) {
                    if (cloudStore == null) throw new IllegalStateException("云公告客户端未初始化");
                    Map<String, Object> saved = cloudStore.save(target, isNew);
                    if (isNew) announcements.add(saved); else announcements.set(idx, saved);
                    dlg.dispose();
                    refreshCards();
                } else if (saveToJson()) {
                    dlg.dispose();
                    refreshCards();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dlg, "公告保存失败：\n" + ex.getMessage(),
                    "保存失败", JOptionPane.ERROR_MESSAGE);
                if (cloudMode) try { loadFromCloud(); refreshCards(); } catch (Exception ignored) {}
            }
        });

        cancelB.addActionListener(ev -> dlg.dispose());

        // --- 主面板 ---
        JPanel wrapper = new JPanel(new BorderLayout(0, 15));
        wrapper.setBackground(BG_MAIN);
        wrapper.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        wrapper.add(content, BorderLayout.CENTER);
        wrapper.add(btnBar, BorderLayout.SOUTH);

        dlg.setContentPane(wrapper);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(520, dlg.getHeight()));
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    // ========== UI 辅助 ==========

    private void persist(Map<String, Object> existing, String ct, String time, String gid,
                         boolean pin, boolean conf, File img, String existingImageToken,
                         java.util.List<AttachmentRef> files) {
        Map<String, Object> m = existing != null ? existing : new HashMap<>();
        m.put("title", ct.split("\n")[0]); m.put("content", ct);
        m.put("time", time); m.put("groupId", gid);
        m.put("pin", String.valueOf(pin)); m.put("confirm", String.valueOf(conf));
        String session = "desktop_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String imageToken = existingImageToken;
        if (img != null) imageToken = storeAsset(img, session, "image", existingImageToken);
        m.put("image", imageToken == null ? "" : imageToken);
        StringBuilder aps = new StringBuilder();
        JSONArray attachmentNames = new JSONArray();
        for (AttachmentRef ref : files) {
            String token = ref.file != null && ref.file.isFile()
                ? storeAsset(ref.file, session, "attach", ref.storedToken)
                : ref.storedToken;
            if (token != null && !token.isEmpty()) {
                if (aps.length() > 0) aps.append("|");
                aps.append(token);
                attachmentNames.put(sanitizeDisplayName(ref.displayName, displayName(token)));
            }
        }
        m.put("attach", aps.toString());
        m.put("attachmentNames", attachmentNames);
        m.put("sent", "false");
        if (existing == null) {
            m.put("id", UUID.randomUUID().toString());
            announcements.add(m);
        }
    }

    private File resolveStoredAsset(String stored) {
        if (stored == null || stored.trim().isEmpty()) return null;
        try {
            String normalized = stored.trim().replace('\\', '/');
            File direct = new File(stored);
            if (direct.isAbsolute() && direct.isFile()) return direct.getCanonicalFile();
            File root = announceAssetDir.getCanonicalFile();
            File candidate = new File(root, normalized).getCanonicalFile();
            String rootPath = root.getPath() + File.separator;
            return candidate.getPath().startsWith(rootPath) ? candidate : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private String tokenForStoredAsset(File file) {
        if (file == null) return "";
        try {
            File root = announceAssetDir.getCanonicalFile();
            File candidate = file.getCanonicalFile();
            String rootPath = root.getPath() + File.separator;
            if (!candidate.getPath().startsWith(rootPath)) return "";
            return root.toPath().relativize(candidate.toPath()).toString().replace('\\', '/');
        } catch (IOException ex) {
            return "";
        }
    }

    private String storeAsset(File source, String session, String type, String existingToken) {
        if (source == null || !source.isFile()) return existingToken == null ? "" : existingToken;
        if (cloudMode) {
            if (cloudStore == null) throw new IllegalStateException("云公告客户端未初始化");
            try { return cloudStore.upload(source, "ann_" + session.replaceAll("[^A-Za-z0-9_-]", "_"), type); }
            catch (IOException ex) { throw new IllegalStateException(source.getName() + ": " + ex.getMessage(), ex); }
        }
        String managedToken = tokenForStoredAsset(source);
        if (!managedToken.isEmpty()) return managedToken;
        try {
            String safeName = source.getName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (safeName.isEmpty()) safeName = "file.bin";
            File targetDir = new File(new File(announceAssetDir, session), type).getCanonicalFile();
            File root = announceAssetDir.getCanonicalFile();
            if (!targetDir.getPath().startsWith(root.getPath() + File.separator)) {
                throw new IOException("非法附件目录");
            }
            targetDir.mkdirs();
            File target = new File(targetDir, safeName);
            if (target.exists()) {
                int dot = safeName.lastIndexOf('.');
                String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
                String ext = dot > 0 ? safeName.substring(dot) : "";
                target = new File(targetDir, stem + "-" + System.currentTimeMillis() + ext);
            }
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            return tokenForStoredAsset(target);
        } catch (IOException ex) {
            throw new IllegalStateException(source.getName() + ": " + ex.getMessage(), ex);
        }
    }

    private static String displayName(String token) {
        if (token == null || token.isEmpty()) return "";
        String normalized = token.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.replaceFirst("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-", "");
    }

    private static java.util.List<String> attachmentNames(Object raw) {
        JSONArray array = null;
        if (raw instanceof JSONArray) array = (JSONArray) raw;
        else if (raw != null) {
            String value = String.valueOf(raw).trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                try { array = new JSONArray(value); } catch (Exception ignored) {}
            }
        }
        java.util.List<String> names = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) names.add(array.optString(i, ""));
        return names;
    }

    private static String sanitizeDisplayName(String requested, String fallback) {
        String value = requested == null ? "" : Normalizer.normalize(requested, Normalizer.Form.NFC);
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        if (value.isEmpty()) {
            value = fallback == null ? "" : Normalizer.normalize(fallback, Normalizer.Form.NFC);
            value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        }
        if (value.isEmpty()) value = "file.bin";
        int count = value.codePointCount(0, value.length());
        return count > 160 ? value.substring(0, value.offsetByCodePoints(0, 160)) : value;
    }

    private JPanel sectionCard(String title, JComponent body) {
        JPanel card = new JPanel(new BorderLayout(0, 8)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createCompoundBorder(
            new UiKit.ModernRoundedBorder(CARD_BORDER, 1, 10),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));

        JLabel header = new JLabel(title);
        header.setFont(FONT_LABEL);
        header.setForeground(TEXT_PRIMARY);
        card.add(header, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel twoColCard(String label1, JComponent field1, String label2, JComponent field2) {
        JPanel card = new JPanel(new BorderLayout(0, 8)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createCompoundBorder(
            new UiKit.ModernRoundedBorder(CARD_BORDER, 1, 10),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));

        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.setOpaque(false);
        JLabel l1 = new JLabel(label1); l1.setFont(FONT_LABEL); l1.setForeground(TEXT_PRIMARY);
        left.add(l1, BorderLayout.NORTH); left.add(field1, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(0, 4));
        right.setOpaque(false);
        JLabel l2 = new JLabel(label2); l2.setFont(FONT_LABEL); l2.setForeground(TEXT_PRIMARY);
        right.add(l2, BorderLayout.NORTH); right.add(field2, BorderLayout.CENTER);

        JPanel cols = new JPanel(new GridLayout(1, 2, 16, 0));
        cols.setOpaque(false);
        cols.add(left); cols.add(right);
        card.add(cols, BorderLayout.CENTER);
        return card;
    }

    private static JTextField styledField(String text) {
        JTextField f = new JTextField(text);
        f.setFont(FONT_INPUT);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(INPUT_BORDER, 1),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        return f;
    }

    private static JCheckBox styledCheck(String text, boolean selected) {
        JCheckBox cb = new JCheckBox(text, selected);
        cb.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        cb.setForeground(TEXT_PRIMARY);
        cb.setOpaque(false);
        cb.setFocusPainted(false);
        return cb;
    }

    private UiKit.ModernButton makeOutlineBtn(String text) {
        UiKit.ModernButton b = new UiKit.ModernButton(text);
        b.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        b.setCustomColors(CARD_BG, new Color(241, 245, 249), new Color(226, 232, 240));
        b.setBorderColor(INPUT_BORDER);
        return b;
    }

    private UiKit.ModernButton makeSmallBtn(String text) {
        UiKit.ModernButton b = new UiKit.ModernButton(text);
        b.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        b.setCustomColors(CARD_BG, new Color(241, 245, 249), new Color(226, 232, 240));
        b.setBorderColor(INPUT_BORDER);
        return b;
    }

    private static JPanel hbox(int gap, Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        for (Component c : comps) p.add(c);
        return p;
    }

    private static JPanel vbox(int gap, Component... comps) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (int i = 0; i < comps.length; i++) {
            p.add(comps[i]);
            if (i < comps.length - 1) p.add(Box.createVerticalStrut(gap));
        }
        return p;
    }

    // ========== 日期时间选择器 ==========

    private static class DateTimePicker extends JPanel {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        private static final String[] WEEKDAYS = {"一","二","三","四","五","六","日"};

        private LocalDateTime selected;
        private JLabel display;

        DateTimePicker(String preset) {
            this.selected = parseOrNow(preset);
            setLayout(new BorderLayout());
            setOpaque(false);

            display = new JLabel(selected.format(FMT));
            display.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
            display.setForeground(new Color(30, 41, 59));
            display.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225), 1),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
            display.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            display.setBackground(Color.WHITE);
            display.setOpaque(true);
            display.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent ev) { showPopup(); }
            });

            JLabel icon = new JLabel(">");
            icon.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            icon.setForeground(new Color(148, 163, 184));
            icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.add(display, BorderLayout.CENTER);
            row.add(icon, BorderLayout.EAST);
            add(row, BorderLayout.CENTER);
        }

        String getFormatted() { return selected.format(FMT); }

        private static LocalDateTime parseOrNow(String s) {
            if (s != null && !s.trim().isEmpty()) {
                try { return LocalDateTime.parse(s.trim(), FMT); } catch (Exception ignored) {}
            }
            return fetchNetworkTime();
        }

        private static LocalDateTime fetchNetworkTime() {
            return LocalDateTime.now();
        }

        private JPanel dayGridPanel;
        private JLabel monthLabel;
        private int calYear, calMonth, selDay;

        private void rebuildDayGrid() {
            monthLabel.setText(calYear + "年 " + calMonth + "月");
            if (dayGridPanel != null) {
                dayGridPanel.removeAll();
            }
            buildDayGridContent(dayGridPanel, calYear, calMonth, selDay);
            dayGridPanel.revalidate();
            dayGridPanel.repaint();
        }

        private void showPopup() {
            Window owner = SwingUtilities.getWindowAncestor(DateTimePicker.this);
            JDialog popup = new JDialog(owner, "选择时间", Dialog.ModalityType.APPLICATION_MODAL);
            popup.setUndecorated(true);
            popup.setBackground(new Color(0, 0, 0, 0));

            JPanel card = new JPanel(new BorderLayout(0, 8)) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Color.WHITE);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                    g2.setColor(new Color(203, 213, 225));
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                    g2.dispose();
                }
            };
            card.setOpaque(false);
            card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

            calYear = selected.getYear();
            calMonth = selected.getMonthValue();
            selDay = selected.getDayOfMonth();
            // 如果预设日期是过去，默认跳到今天
            if (selected.toLocalDate().isBefore(LocalDate.now())) {
                LocalDate today = LocalDate.now();
                calYear = today.getYear();
                calMonth = today.getMonthValue();
                selDay = today.getDayOfMonth();
            }

            monthLabel = new JLabel("", JLabel.CENTER);
            monthLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
            monthLabel.setForeground(new Color(30, 41, 59));

            // --- 时间微调 ---
            SpinnerNumberModel hourModel = new SpinnerNumberModel(selected.getHour(), 0, 23, 1);
            SpinnerNumberModel minModel = new SpinnerNumberModel(selected.getMinute(), 0, 59, 1);
            JSpinner hSpin = new JSpinner(hourModel);
            JSpinner mSpin = new JSpinner(minModel);
            hSpin.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
            mSpin.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));

            dayGridPanel = new JPanel(new GridLayout(0, 7, 2, 2));
            dayGridPanel.setOpaque(false);
            rebuildDayGrid();

            // --- 日历头部 ---
            JButton prevM = new JButton("←"); prevM.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            prevM.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            prevM.setFocusPainted(false);
            prevM.addActionListener(e -> { if (--calMonth < 1) { calMonth = 12; calYear--; } rebuildDayGrid(); });

            JButton nextM = new JButton("→"); nextM.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            nextM.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            nextM.setFocusPainted(false);
            nextM.addActionListener(e -> { if (++calMonth > 12) { calMonth = 1; calYear++; } rebuildDayGrid(); });

            JPanel nav = new JPanel(new BorderLayout());
            nav.setOpaque(false);
            nav.add(prevM, BorderLayout.WEST);
            nav.add(monthLabel, BorderLayout.CENTER);
            nav.add(nextM, BorderLayout.EAST);

            JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
            timeRow.setOpaque(false);
            timeRow.add(new JLabel("时间:") {{ setFont(new Font("Microsoft YaHei", Font.PLAIN, 12)); }});
            timeRow.add(hSpin);
            timeRow.add(new JLabel(":") {{ setFont(new Font("Microsoft YaHei", Font.BOLD, 14)); }});
            timeRow.add(mSpin);

            // --- 按钮 ---
            JButton cancelBtn = new JButton("取消");
            cancelBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            cancelBtn.setFocusPainted(false);
            cancelBtn.setContentAreaFilled(false);
            cancelBtn.setOpaque(true);
            cancelBtn.setBackground(new Color(241, 245, 249));
            cancelBtn.setForeground(new Color(30, 41, 59));
            cancelBtn.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
            cancelBtn.addActionListener(e -> popup.dispose());

            JButton okBtn = new JButton("确定");
            okBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            okBtn.setBackground(new Color(59, 130, 246));
            okBtn.setForeground(Color.WHITE);
            okBtn.setFocusPainted(false);
            okBtn.setContentAreaFilled(false);
            okBtn.setOpaque(true);
            okBtn.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
            okBtn.addActionListener(e -> {
                selected = LocalDateTime.of(calYear, calMonth, selDay,
                        (int)hourModel.getValue(), (int)minModel.getValue());
                display.setText(selected.format(FMT));
                popup.dispose();
            });

            JPanel actRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
            actRow.setOpaque(false);
            actRow.add(cancelBtn); actRow.add(okBtn);

            JPanel bot = new JPanel(new BorderLayout(0, 10));
            bot.setOpaque(false);
            bot.add(timeRow, BorderLayout.NORTH);
            bot.add(actRow, BorderLayout.SOUTH);

            card.add(nav, BorderLayout.NORTH);
            card.add(dayGridPanel, BorderLayout.CENTER);
            card.add(bot, BorderLayout.SOUTH);

            popup.setContentPane(card);
            popup.pack();
            popup.setLocationRelativeTo(display);
            popup.setVisible(true);
        }

        private void buildDayGridContent(JPanel grid, int year, int month, int highlightDay) {
            for (String wd : WEEKDAYS) {
                JLabel wl = new JLabel(wd, JLabel.CENTER);
                wl.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
                wl.setForeground(wd.equals("六") || wd.equals("日") ? new Color(239, 68, 68) : new Color(100, 116, 139));
                grid.add(wl);
            }

            LocalDate firstDay = LocalDate.of(year, month, 1);
            int startDow = firstDay.getDayOfWeek().getValue();
            int daysInMonth = firstDay.lengthOfMonth();
            LocalDate today = LocalDate.now();

            for (int i = 1; i < startDow; i++) grid.add(new JLabel());

            for (int d = 1; d <= daysInMonth; d++) {
                final int day = d;
                LocalDate cellDate = LocalDate.of(year, month, d);
                boolean isPast = cellDate.isBefore(today);
                boolean isSelected = (day == highlightDay && !isPast);
                boolean isToday = cellDate.equals(today);

                JButton db = new JButton(String.valueOf(d));
                db.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                db.setFocusPainted(false);
                db.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                db.setContentAreaFilled(false);
                db.setOpaque(true);

                if (isPast) {
                    db.setBackground(Color.WHITE);
                    db.setForeground(new Color(203, 213, 225));
                    db.setEnabled(false);
                } else if (isSelected) {
                    db.setBackground(new Color(59, 130, 246));
                    db.setForeground(Color.WHITE);
                } else if (isToday) {
                    db.setBackground(new Color(219, 234, 254));
                    db.setForeground(new Color(30, 64, 175));
                } else {
                    db.setBackground(Color.WHITE);
                    db.setForeground(new Color(30, 41, 59));
                }
                if (!isPast) {
                    db.addActionListener(e -> {
                        selDay = day;
                        rebuildDayGrid();
                    });
                }
                grid.add(db);
            }
        }
    }
}
