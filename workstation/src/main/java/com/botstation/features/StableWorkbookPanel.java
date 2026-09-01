package com.botstation.features;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.core.TaskRunner;
import com.botstation.ui.DesignTokens;
import com.botstation.ui.UiKit;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class StableWorkbookPanel extends JPanel {
    private final StableRepository repository;
    private final TaskRunner tasks;
    private final LogBus log;
    private final JTable table = new JTable();
    private final JTextField search = UiKit.field(24);
    private final JLabel status = UiKit.muted("尚未加载");

    public StableWorkbookPanel(BotPaths paths, LogBus log, TaskRunner tasks) {
        super(new BorderLayout(0, 18));
        this.repository = new StableRepository(paths); this.tasks = tasks; this.log = log;
        setBackground(DesignTokens.PAPER); setBorder(BorderFactory.createEmptyBorder(24, 26, 20, 26));
        JButton save = UiKit.primaryButton("保存并同步"); save.addActionListener(event -> save());
        add(UiKit.pageHeader("Stable 曲库", "一次保存同步 stable_info.xlsx、SongBot CSV 与数据库；修改前自动留档。", save), BorderLayout.NORTH);
        JPanel main = new JPanel(new BorderLayout(0, 10)); main.setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "筛选 SID、曲名、作者或谱师");
        search.getDocument().addDocumentListener((SongLibraryPanel.SimpleDocumentListener) event -> filter());
        JButton add = UiKit.button("新增一行"); add.addActionListener(event -> addRow());
        if (repository.cloudMode()) {
            add.setEnabled(false);
            add.setToolTipText("外部工作站可编辑现有云端记录，新增记录由主工作站完成");
        }
        main.add(UiKit.flow(search, status, add), BorderLayout.NORTH);
        main.add(UiKit.tableScroll(table), BorderLayout.CENTER); add(main, BorderLayout.CENTER); reload();
    }

    private void reload() {
        setBusy(true, "正在读取…");
        tasks.run(repository::load, snapshot -> {
            DefaultTableModel model = new DefaultTableModel(snapshot.headers.toArray(), 0);
            for (List<String> row : snapshot.rows) model.addRow(row.toArray());
            table.setModel(model); table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            UiKit.applySemanticColumnWidths(table);
            setBusy(false, snapshot.sheetName + " · " + snapshot.rows.size() + " 行");
        }, error -> { setBusy(false, "读取失败"); showError("无法读取 Stable 工作簿", error); });
    }

    private void addRow() {
        if (!(table.getModel() instanceof DefaultTableModel)) return;
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        Object[] values = new Object[model.getColumnCount()];
        for (int i = 0; i < values.length; i++) values[i] = model.getColumnName(i).equalsIgnoreCase("cover") ? "AUTO" : "";
        model.addRow(values); int view = table.convertRowIndexToView(model.getRowCount() - 1);
        if (view >= 0) { table.getSelectionModel().setSelectionInterval(view, view); table.scrollRectToVisible(table.getCellRect(view, 0, true)); }
    }

    private void save() {
        if (!(table.getModel() instanceof DefaultTableModel)) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < model.getColumnCount(); column++) headers.add(model.getColumnName(column));
        List<List<String>> rows = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            List<String> values = new ArrayList<>(); boolean any = false;
            for (int column = 0; column < model.getColumnCount(); column++) {
                String value = String.valueOf(model.getValueAt(row, column) == null ? "" : model.getValueAt(row, column)).trim();
                values.add(value); any |= !value.isEmpty();
            }
            if (any) rows.add(values);
        }
        setBusy(true, "正在校验和同步…");
        tasks.run(() -> repository.save(headers, rows), result -> {
            log.info("Stable", "已同步 " + result.rows + " 行；备份 " + result.backup);
            setBusy(false, "已同步 " + result.rows + " 行");
            JOptionPane.showMessageDialog(this, "保存完成\nXLSX、CSV 和数据库已同步。\n备份：" + result.backup, "Stable", JOptionPane.INFORMATION_MESSAGE);
            reload();
        }, error -> { setBusy(false, "保存失败"); showError("保存未完成，已尝试恢复原文件", error); });
    }

    private void filter() {
        if (!(table.getRowSorter() instanceof TableRowSorter)) return;
        @SuppressWarnings("unchecked") TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) table.getRowSorter();
        String query = search.getText().trim(); sorter.setRowFilter(query.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(query)));
    }

    private void setBusy(boolean busy, String text) { table.setEnabled(!busy); search.setEnabled(!busy); status.setText(text); }
    private void showError(String title, Throwable error) {
        log.error("Stable", title + "：" + error.getMessage());
        JOptionPane.showMessageDialog(this, title + "\n" + error.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
    }
}
