package com.botstation.features;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.botstation.ui.DesignTokens;
import com.botstation.ui.UiKit;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class SongLibraryPanel extends JPanel {
    private static final List<String> PRIORITY = java.util.Arrays.asList(
        "song_name", "author", "charter", "bpm", "duration", "id", "album", "album_ids",
        "album_date", "album_image_path", "song_nickname", "song_nickname2", "song_nickname3",
        "song_nickname4", "song_nickname5", "song_nickname6", "artist_nickname");
    private final SongLibraryRepository repository;
    private final Path database;
    private final LogBus log;
    private final TaskRunner tasks;
    private final SongAssetService assets;
    private final JTable table = new JTable();
    private final JTextField search = UiKit.field(28);
    private final JLabel count = UiKit.muted("尚未加载");
    private SongLibraryRepository.Snapshot snapshot;
    private List<String> visibleColumns = new ArrayList<>();
    private boolean loading;
    private long knownDatabaseStamp;

    public SongLibraryPanel(BotPaths paths, LogBus log, TaskRunner tasks) {
        super(new BorderLayout(0, 18));
        this.database = paths.songDatabase;
        this.repository = new SongLibraryRepository(database); this.log = log; this.tasks = tasks;
        this.assets = new SongAssetService(paths, log);
        setBackground(DesignTokens.PAPER); setBorder(BorderFactory.createEmptyBorder(24, 26, 20, 26));
        JButton reload = UiKit.button("重新读取"); reload.addActionListener(event -> reload());
        add(UiKit.pageHeader("歌曲信息", "点击保存修改，将直接提交至Bot", reload), BorderLayout.NORTH);

        JPanel main = new JPanel(new BorderLayout(0, 10)); main.setOpaque(false);
        JPanel toolbar = new JPanel(new BorderLayout(12, 0)); toolbar.setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "搜索歌曲名、作者、谱师、ID 或任意字段");
        search.getDocument().addDocumentListener((SimpleDocumentListener) event -> applyFilter());
        JButton edit = UiKit.primaryButton("编辑所选歌曲"); edit.addActionListener(event -> editSelected());
        toolbar.add(search, BorderLayout.CENTER); toolbar.add(UiKit.flow(count, edit), BorderLayout.EAST);
        main.add(toolbar, BorderLayout.NORTH); main.add(UiKit.tableScroll(table), BorderLayout.CENTER);
        add(main, BorderLayout.CENTER); reload();
        javax.swing.Timer cloudRefresh = new javax.swing.Timer(2_500, event -> {
            if (!loading && isShowing() && databaseStamp() > knownDatabaseStamp) reload();
        });
        cloudRefresh.setRepeats(true);
        cloudRefresh.start();
    }

    private void reload() {
        if (loading) return;
        loading = true;
        setBusy(true, "正在读取…");
        tasks.run(repository::load, loaded -> {
            snapshot = loaded; knownDatabaseStamp = databaseStamp(); populate();
            loading = false; setBusy(false, loaded.rows.size() + " 首歌曲");
        }, error -> { loading = false; setBusy(false, "读取失败"); showError("无法读取曲库", error); });
    }

    private long databaseStamp() {
        long stamp = lastModified(database);
        Path wal = database.resolveSibling(database.getFileName() + "-wal");
        return Math.max(stamp, lastModified(wal));
    }

    private static long lastModified(Path path) {
        try { return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0L; }
        catch (Exception ignored) { return 0L; }
    }

    private void populate() {
        visibleColumns = new ArrayList<>();
        for (String name : PRIORITY) if (findColumn(name) != null) visibleColumns.add(findColumn(name));
        for (String column : snapshot.columns) if (!visibleColumns.contains(column) && visibleColumns.size() < 12) visibleColumns.add(column);
        DefaultTableModel model = new DefaultTableModel(visibleColumns.toArray(), 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (Map<String, String> row : snapshot.rows) {
            Object[] values = new Object[visibleColumns.size()];
            for (int index = 0; index < values.length; index++) values[index] = row.get(visibleColumns.get(index));
            model.addRow(values);
        }
        table.setModel(model); table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        UiKit.applySemanticColumnWidths(table);
        applyFilter();
    }

    private String findColumn(String expected) {
        if (snapshot == null) return null;
        for (String column : snapshot.columns) if (column.equalsIgnoreCase(expected)) return column;
        return null;
    }

    private void applyFilter() {
        if (!(table.getRowSorter() instanceof TableRowSorter)) return;
        @SuppressWarnings("unchecked") TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) table.getRowSorter();
        String value = search.getText().trim();
        sorter.setRowFilter(value.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(value)));
        count.setText(table.getRowCount() + " 首歌曲");
    }

    private void editSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || snapshot == null) { JOptionPane.showMessageDialog(this, "请先选择一首歌曲"); return; }
        int modelRow = table.convertRowIndexToModel(viewRow);
        Map<String, String> selected = snapshot.rows.get(modelRow);
        showEditor(selected);
    }

    private void showEditor(Map<String, String> selected) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "编辑歌曲 · " + selected.getOrDefault(findColumn("song_name"), ""), Dialog.ModalityType.APPLICATION_MODAL);
        JPanel form = new JPanel(new GridBagLayout()); form.setBackground(DesignTokens.SURFACE);
        form.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        Map<String, JTextField> fields = new LinkedHashMap<>();
        String idColumn = findColumn("id") == null ? snapshot.columns.get(0) : findColumn("id");
        List<String> editorColumns = new ArrayList<>();
        for (String name : PRIORITY) if (findColumn(name) != null && !editorColumns.contains(findColumn(name))) editorColumns.add(findColumn(name));
        for (String column : snapshot.columns) if (!editorColumns.contains(column)) editorColumns.add(column);
        int row = 0;
        for (String column : editorColumns) {
            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0; labelConstraints.gridy = row; labelConstraints.anchor = GridBagConstraints.WEST;
            labelConstraints.insets = new Insets(5, 0, 5, 12);
            JLabel label = new JLabel(column); label.setFont(DesignTokens.CAPTION); label.setForeground(DesignTokens.MUTED);
            form.add(label, labelConstraints);
            JTextField field = UiKit.field(42); field.setText(selected.getOrDefault(column, "")); field.setEditable(!column.equals(idColumn));
            GridBagConstraints fieldConstraints = new GridBagConstraints();
            fieldConstraints.gridx = 1; fieldConstraints.gridy = row++; fieldConstraints.weightx = 1; fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
            fieldConstraints.insets = new Insets(5, 0, 5, 0); form.add(field, fieldConstraints); fields.put(column, field);
        }
        JScrollPane scroll = new JScrollPane(form); scroll.setBorder(null); scroll.getVerticalScrollBar().setUnitIncrement(18);
        JButton cancel = UiKit.button("取消"); cancel.addActionListener(event -> dialog.dispose());
        JButton cover = UiKit.button("更换歌曲图片");
        JButton audio = UiKit.button("更换歌曲音频");
        cover.addActionListener(event -> selectAsset(dialog, selected.get(idColumn), "image", cover, audio));
        audio.addActionListener(event -> selectAsset(dialog, selected.get(idColumn), "audio", cover, audio));
        JButton save = UiKit.primaryButton("保存修改");
        save.addActionListener(event -> {
            Map<String, String> values = new LinkedHashMap<>(); fields.forEach((key, field) -> values.put(key, field.getText()));
            save.setEnabled(false);
            tasks.run(() -> { repository.update(idColumn, selected.get(idColumn), values); return null; }, ignored -> {
                log.info("歌曲信息", "已更新 ID " + selected.get(idColumn) + " · " + values.getOrDefault(findColumn("song_name"), ""));
                dialog.dispose(); reload();
            }, error -> { save.setEnabled(true); showError("保存失败，数据库没有发生部分写入", error); });
        });
        JPanel buttons = editorActionBar(cover, audio, cancel, save);
        dialog.add(scroll, BorderLayout.CENTER); dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(new Dimension(700, 720)); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
    }

    private void selectAsset(JDialog dialog, String id, String type, JButton cover, JButton audio) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("image".equals(type) ? "选择歌曲图片" : "选择歌曲音频");
        chooser.setFileFilter("image".equals(type)
            ? new FileNameExtensionFilter("图片（JPG、PNG、WebP）", "jpg", "jpeg", "png", "webp")
            : new FileNameExtensionFilter("音频（MP3、WAV、FLAC、M4A、OGG）", "mp3", "wav", "flac", "m4a", "ogg"));
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) return;
        Path selected = chooser.getSelectedFile().toPath(); cover.setEnabled(false); audio.setEnabled(false);
        String original = "image".equals(type) ? "更换歌曲图片" : "更换歌曲音频";
        JButton active = "image".equals(type) ? cover : audio; active.setText("正在压缩并发布…");
        equalizeButtonWidths(cover, audio);
        tasks.run(() -> assets.publish(id, type, selected), saved -> {
            active.setText(original); equalizeButtonWidths(cover, audio); cover.setEnabled(true); audio.setEnabled(true);
            JOptionPane.showMessageDialog(dialog, "资源已压缩并发布，原歌曲 ID 不变。", "发布完成", JOptionPane.INFORMATION_MESSAGE);
            dialog.dispose(); reload();
        }, error -> {
            active.setText(original); equalizeButtonWidths(cover, audio); cover.setEnabled(true); audio.setEnabled(true); showError("资源发布失败，原文件已恢复", error);
        });
    }

    /* Hallmark · component: editor action bar · genre: modern-minimal · theme: Bot workstation
       States remain owned by UiKit and the existing async handlers; spacing follows the 8/16 rhythm. */
    static JPanel editorActionBar(JButton cover, JButton audio, JButton cancel, JButton save) {
        equalizeButtonWidths(cover, audio);
        equalizeButtonWidths(cancel, save);

        JPanel resources = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        resources.setOpaque(false);
        resources.add(cover);
        resources.add(audio);

        JPanel decisions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        decisions.setOpaque(false);
        decisions.add(cancel);
        decisions.add(save);

        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(12, 16, 14, 16));
        bar.add(resources, BorderLayout.WEST);
        bar.add(decisions, BorderLayout.EAST);
        return bar;
    }

    private static void equalizeButtonWidths(JButton... buttons) {
        int width = 0;
        int height = 0;
        for (JButton button : buttons) {
            button.setPreferredSize(null);
            Dimension preferred = button.getPreferredSize();
            width = Math.max(width, preferred.width);
            height = Math.max(height, preferred.height);
        }
        Dimension shared = new Dimension(width, height);
        for (JButton button : buttons) button.setPreferredSize(shared);
    }

    private void setBusy(boolean busy, String text) { search.setEnabled(!busy); table.setEnabled(!busy); count.setText(text); }
    private void showError(String title, Throwable error) {
        log.error("歌曲信息", title + "：" + error.getMessage());
        JOptionPane.showMessageDialog(this, title + "\n" + error.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
    }

    @FunctionalInterface interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void update(javax.swing.event.DocumentEvent event);
        @Override default void insertUpdate(javax.swing.event.DocumentEvent event) { update(event); }
        @Override default void removeUpdate(javax.swing.event.DocumentEvent event) { update(event); }
        @Override default void changedUpdate(javax.swing.event.DocumentEvent event) { update(event); }
    }
}
