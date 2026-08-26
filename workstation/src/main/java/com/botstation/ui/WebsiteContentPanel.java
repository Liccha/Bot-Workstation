package com.botstation.ui;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.botstation.security.AdminGate;
import com.mybot.WebsitePostBridge;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class WebsiteContentPanel extends JPanel {
    private final TaskRunner tasks;
    private final LogBus log;
    private final AdminGate.AdminSession session;
    private final WebsitePostBridge bridge;
    private final DefaultListModel<WebsitePostBridge.Summary> listModel = new DefaultListModel<>();
    private final JList<WebsitePostBridge.Summary> files = new JList<>(listModel);
    private final JTextField name = UiKit.field(28);
    private final JTextArea editor = new JTextArea();
    private final JLabel status = UiKit.muted("正在连接云端…");
    private Integer revision;
    private boolean loadingSelection;

    WebsiteContentPanel(BotPaths paths, LogBus log, TaskRunner tasks, AdminGate.AdminSession session) {
        super(new BorderLayout(0, 12));
        session.requireAuthorized();
        this.tasks = tasks; this.log = log;
        this.session = session;
        WebsitePostBridge configured = null;
        try { configured = new WebsitePostBridge(paths.songBot, session); }
        catch (Exception error) { log.error("网站内容", "云客户端不可用：" + error.getMessage()); }
        this.bridge = configured;
        setBackground(DesignTokens.PAPER);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton refresh = UiKit.button("刷新列表"); refresh.addActionListener(event -> loadList(null));
        JButton sync = UiKit.button("同步到本机博客"); sync.addActionListener(event -> syncMirror());
        add(UiKit.pageHeader("网站文章", "编辑 teacharm.moe 使用的 Markdown；云端为唯一真源，本机保留镜像与历史备份。", UiKit.flow(refresh, sync)), BorderLayout.NORTH);

        files.setFont(DesignTokens.BODY); files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); files.setFixedCellHeight(30);
        files.addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !loadingSelection) loadSelected(); });
        JPanel left = new JPanel(new BorderLayout(0, 8)); left.setOpaque(false);
        JButton create = UiKit.primaryButton("新建"); create.addActionListener(event -> clearEditor());
        JButton importFile = UiKit.button("导入 .md"); importFile.addActionListener(event -> importMarkdown());
        left.add(UiKit.flow(create, importFile), BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(files); listScroll.setBorder(BorderFactory.createLineBorder(DesignTokens.BORDER));
        left.add(listScroll, BorderLayout.CENTER); left.setMinimumSize(new Dimension(230, 200));

        JPanel right = new JPanel(new BorderLayout(0, 8)); right.setOpaque(false);
        name.putClientProperty("JTextField.placeholderText", "文件名，例如 2026-08-update.md");
        right.add(name, BorderLayout.NORTH);
        editor.setFont(DesignTokens.MONO.deriveFont(13f)); editor.setLineWrap(true); editor.setWrapStyleWord(true);
        editor.setTabSize(2); editor.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane editScroll = new JScrollPane(editor); editScroll.setBorder(BorderFactory.createLineBorder(DesignTokens.BORDER));
        right.add(editScroll, BorderLayout.CENTER);
        JButton delete = UiKit.button("删除文章"); delete.setForeground(DesignTokens.DANGER); delete.addActionListener(event -> deleteCurrent());
        JButton save = UiKit.primaryButton("保存到云端"); save.addActionListener(event -> saveCurrent());
        right.add(new JPanel(new BorderLayout()) {{ setOpaque(false); add(status, BorderLayout.CENTER); add(UiKit.flow(delete, save), BorderLayout.EAST); }}, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right); split.setDividerLocation(260); split.setResizeWeight(0.22);
        split.setBorder(null); split.setOpaque(false); add(split, BorderLayout.CENTER);
        if (bridge == null) { setControls(false); status.setText("云端网站客户端未配置"); }
        else loadList(null);
    }

    private void loadList(String selectName) {
        session.requireAuthorized();
        if (bridge == null) return; status.setText("正在读取云端列表…");
        tasks.run(bridge::list, values -> {
            loadingSelection = true; listModel.clear(); for (WebsitePostBridge.Summary value : values) listModel.addElement(value);
            if (selectName != null) for (int i = 0; i < listModel.size(); i++) if (listModel.get(i).name.equals(selectName)) files.setSelectedIndex(i);
            loadingSelection = false; status.setText(values.size() + " 篇文章");
        }, error -> showError("网站文章列表读取失败", error));
    }

    private void loadSelected() {
        session.requireAuthorized();
        WebsitePostBridge.Summary selected = files.getSelectedValue(); if (selected == null || bridge == null) return;
        setControls(false); status.setText("正在读取 " + selected.name + "…");
        tasks.run(() -> bridge.read(selected.name), document -> {
            name.setText(document.name); editor.setText(document.content); editor.setCaretPosition(0); revision = document.revision;
            setControls(true); status.setText("版本 " + revision + " · " + document.size + " 字节");
        }, error -> { setControls(true); showError("文章读取失败", error); });
    }

    private void clearEditor() { session.requireAuthorized(); files.clearSelection(); name.setText(""); editor.setText(""); revision = null; status.setText("新文章"); name.requestFocusInWindow(); }

    private void importMarkdown() {
        session.requireAuthorized();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Markdown", "md"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String content = Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
            name.setText(chooser.getSelectedFile().getName()); editor.setText(content); revision = null; files.clearSelection();
            status.setText("已导入，尚未上传");
        } catch (Exception error) { showError("Markdown 必须是 UTF-8 编码", error); }
    }

    private void saveCurrent() {
        session.requireAuthorized();
        if (bridge == null) return;
        String fileName = name.getText().trim(); String content = editor.getText();
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".md") || fileName.contains("/") || fileName.contains("\\")) {
            JOptionPane.showMessageDialog(this, "文件名必须以 .md 结尾，且不能包含路径。", "文件名无效", JOptionPane.WARNING_MESSAGE); return;
        }
        setControls(false); status.setText("正在保存到云端…"); Integer expected = revision;
        tasks.run(() -> bridge.save(fileName, content, expected), saved -> {
            revision = saved.revision; setControls(true); status.setText("已保存 · 版本 " + revision);
            log.info("网站内容", "已保存 " + saved.name + " · 版本 " + saved.revision); loadList(saved.name);
        }, error -> { setControls(true); showError("保存失败，原云端内容未被本页面覆盖", error); });
    }

    private void deleteCurrent() {
        session.requireAuthorized();
        if (bridge == null || revision == null || name.getText().trim().isEmpty()) { JOptionPane.showMessageDialog(this, "当前不是已保存的云端文章"); return; }
        String fileName = name.getText().trim();
        int result = JOptionPane.showConfirmDialog(this, "确认删除云端文章“" + fileName + "”？\n本机镜像会移入备份目录。",
            "删除文章", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;
        int expected = revision; setControls(false); status.setText("正在删除…");
        tasks.run(() -> { bridge.delete(fileName, expected); return null; }, ignored -> {
            log.warn("网站内容", "已删除并归档 " + fileName); clearEditor(); setControls(true); loadList(null);
        }, error -> { setControls(true); showError("删除失败", error); });
    }

    private void syncMirror() {
        session.requireAuthorized();
        if (bridge == null) return; status.setText("正在同步本机镜像…");
        tasks.run(() -> { bridge.syncMirror(); return bridge.mirrorDirectory(); }, path -> {
            status.setText("本机镜像已同步 · " + path); log.info("网站内容", "已同步本机镜像 " + path);
        }, error -> showError("本机镜像同步未完成", error));
    }

    private void setControls(boolean enabled) { name.setEnabled(enabled); editor.setEnabled(enabled); files.setEnabled(enabled); }
    private void showError(String title, Throwable error) {
        status.setText(title); log.error("网站内容", title + "：" + error.getMessage());
        JOptionPane.showMessageDialog(this, title + "\n" + error.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
    }
}
