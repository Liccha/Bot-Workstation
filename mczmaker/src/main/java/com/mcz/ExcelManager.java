package com.mcz;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.Color;
import java.awt.Font;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Excel 表格管理 — 读写、ID自动生成、Bot同步、表格预览
 */
public class ExcelManager {

    static File getDesktopExcelFile() {
        File tmp = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        if (tmp == null || !tmp.exists()) tmp = new File(System.getProperty("user.home"));
        return new File(tmp, "songs.xlsx");
    }
public static void initExcel(MczTool parent) {
    File localFile = getDesktopExcelFile();
    XSSFWorkbook localWorkbook = null;
    XSSFWorkbook bundledWorkbook = null;
    if (localFile.exists()) {
        try (FileInputStream fis = new FileInputStream(localFile)) {
            localWorkbook = new XSSFWorkbook(fis);
        } catch (Exception e) {
            System.err.println("警告：读取本地 Excel 失败，尝试内嵌备份: " + e.getMessage());
        }
    }
    try (java.io.InputStream is = MczTool.class.getResourceAsStream("/songs.xlsx")) {
        if (is != null) {
            bundledWorkbook = new XSSFWorkbook(is);
        }
    } catch (Exception e) {
        System.err.println("警告：加载内嵌 Excel 失败: " + e.getMessage());
    }
    parent.currentWorkbook = chooseNewestWorkbook(localWorkbook, bundledWorkbook);
    closeIfUnused(localWorkbook, parent.currentWorkbook);
    closeIfUnused(bundledWorkbook, parent.currentWorkbook);
    if (parent.currentWorkbook == null) {
        parent.currentWorkbook = new XSSFWorkbook();
        parent.currentWorkbook.createSheet("Sheet0");
    }
}

static XSSFWorkbook chooseNewestWorkbook(XSSFWorkbook localWorkbook, XSSFWorkbook bundledWorkbook) {
    if (localWorkbook == null) return bundledWorkbook;
    if (bundledWorkbook == null) return localWorkbook;
    return readMaxId(localWorkbook) >= readMaxId(bundledWorkbook) ? localWorkbook : bundledWorkbook;
}

private static void closeIfUnused(XSSFWorkbook candidate, XSSFWorkbook selected) {
    if (candidate == null || candidate == selected) return;
    try { candidate.close(); } catch (Exception ignored) { }
}

static int readMaxId(XSSFWorkbook workbook) {
    if (workbook == null || workbook.getNumberOfSheets() == 0) return 0;
    int maxId = 0;
    Sheet sheet = workbook.getSheetAt(0);
    DataFormatter fmt = new DataFormatter();
    for (int i = 0; i <= sheet.getLastRowNum(); i++) {
        Row row = sheet.getRow(i);
        if (row == null) continue;
        String value = fmt.formatCellValue(row.getCell(0)).trim();
        try { maxId = Math.max(maxId, Integer.parseInt(value)); }
        catch (NumberFormatException ignored) { }
    }
    return maxId;
}

// 【新增】读取 songs.xlsx 第 0 列中最大的纯数字 ID
public static int readMaxIdFromExcel(MczTool parent) {
    if (parent.currentWorkbook == null) return 0;
    try {
        return readMaxId(parent.currentWorkbook);
    } catch (Exception e) {
        System.err.println("警告：读取 Excel 最大 ID 失败: " + e.getMessage());
    }
    return 0;
}

public static void refreshIdHint(MczTool parent) {
    parent.taskQueue.submit(() -> {
        int maxId = readMaxIdFromExcel(parent);
        SwingUtilities.invokeLater(() -> {
            parent.currentMaxId = maxId;
            if (maxId > 0) {
                parent.idHintLabel.setText("最新ID: " + maxId + "  →  新歌ID: " + (maxId + 1));
            } else {
                parent.idHintLabel.setText("未找到 Excel 或暂无数据");
            }
        });
    });
}

/** Convert a generated row to the workbook's real column names. */
public static Map<String, String> rowValues(MczTool parent, String[] values) {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    if (parent.currentWorkbook == null || parent.currentWorkbook.getNumberOfSheets() == 0) return result;
    Row header = parent.currentWorkbook.getSheetAt(0).getRow(0);
    if (header == null) return result;
    DataFormatter formatter = new DataFormatter();
    int count = Math.min(values == null ? 0 : values.length, Math.max(0, header.getLastCellNum()));
    for (int column = 0; column < count; column++) {
        String name = formatter.formatCellValue(header.getCell(column)).trim();
        if (!name.isEmpty() && !"id".equalsIgnoreCase(name))
            result.put(name, values[column] == null ? "" : values[column]);
    }
    return result;
}

/** Convert an existing workbook row to a cloud update document. */
public static Map<String, String> rowValues(MczTool parent, Row row) {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    if (row == null || parent.currentWorkbook == null || parent.currentWorkbook.getNumberOfSheets() == 0) return result;
    Row header = parent.currentWorkbook.getSheetAt(0).getRow(0);
    if (header == null) return result;
    DataFormatter formatter = new DataFormatter();
    for (int column = 0; column < Math.max(0, header.getLastCellNum()); column++) {
        String name = formatter.formatCellValue(header.getCell(column)).trim();
        if (!name.isEmpty() && !"id".equalsIgnoreCase(name))
            result.put(name, formatter.formatCellValue(row.getCell(column)).trim());
    }
    return result;
}

/**
 * Merge the authoritative cloud snapshot into the in-memory workbook. Local-only
 * rows are preserved so a temporary network failure never destroys unfinished work.
 */
public static int mergeCloudSongs(MczTool parent, List<String> cloudColumns,
                                  List<Map<String, String>> cloudRows) {
    if (parent.currentWorkbook == null || parent.currentWorkbook.getNumberOfSheets() == 0
            || cloudColumns == null || cloudRows == null) return readMaxIdFromExcel(parent);
    Sheet sheet = parent.currentWorkbook.getSheetAt(0);
    Row header = sheet.getRow(0);
    if (header == null) header = sheet.createRow(0);
    DataFormatter formatter = new DataFormatter();
    LinkedHashMap<String, Integer> workbookColumns = new LinkedHashMap<>();
    for (int column = 0; column < Math.max(0, header.getLastCellNum()); column++) {
        String name = formatter.formatCellValue(header.getCell(column)).trim();
        if (!name.isEmpty()) workbookColumns.put(name.toLowerCase(Locale.ROOT), column);
    }
    for (String cloudColumn : cloudColumns) {
        String key = cloudColumn == null ? "" : cloudColumn.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || workbookColumns.containsKey(key)) continue;
        int column = Math.max(0, header.getLastCellNum());
        header.createCell(column).setCellValue(cloudColumn);
        workbookColumns.put(key, column);
    }
    Integer idColumn = workbookColumns.get("id");
    if (idColumn == null) return readMaxIdFromExcel(parent);

    // The cloud snapshot is authoritative. Song creation is cloud-first, so a
    // row which disappeared from the snapshot is a confirmed deletion rather
    // than an unsaved local draft. Remove it before rebuilding the ID index;
    // otherwise a deleted highest ID remains in the embedded workbook and the
    // duplicate guard incorrectly keeps that ID occupied forever.
    Set<String> cloudIds = new HashSet<>();
    for (Map<String, String> cloudRow : cloudRows) {
        String id = valueIgnoreCase(cloudRow, "id").trim();
        if (!id.isEmpty()) cloudIds.add(id);
    }
    for (int rowIndex = sheet.getLastRowNum(); rowIndex >= 1; rowIndex--) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) continue;
        String localId = formatter.formatCellValue(row.getCell(idColumn)).trim();
        if (!localId.isEmpty() && !cloudIds.contains(localId)) sheet.removeRow(row);
    }

    LinkedHashMap<String, Row> byId = new LinkedHashMap<>();
    int lastDataRow = 0;
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) continue;
        String id = formatter.formatCellValue(row.getCell(idColumn)).trim();
        if (!id.isEmpty()) { byId.put(id, row); lastDataRow = Math.max(lastDataRow, rowIndex); }
    }
    for (Map<String, String> cloudRow : cloudRows) {
        String id = valueIgnoreCase(cloudRow, "id").trim();
        if (id.isEmpty()) continue;
        Row row = byId.get(id);
        if (row == null) {
            row = sheet.createRow(++lastDataRow);
            byId.put(id, row);
        }
        for (String cloudColumn : cloudColumns) {
            Integer column = workbookColumns.get(cloudColumn.toLowerCase(Locale.ROOT));
            if (column == null) continue;
            Cell cell = row.getCell(column);
            if (cell == null) cell = row.createCell(column);
            cell.setCellValue(valueIgnoreCase(cloudRow, cloudColumn));
        }
    }
    normalizeRowHeights(parent);
    int max = readMaxIdFromExcel(parent);
    parent.currentMaxId = max;
    final int finalMax = max;
    SwingUtilities.invokeLater(() -> {
        if (parent.idHintLabel != null) parent.idHintLabel.setText(finalMax > 0
                ? "最新ID: " + finalMax + "  →  新歌ID: " + (finalMax + 1)
                : "云端曲库暂无数据");
    });
    return max;
}

private static String valueIgnoreCase(Map<String, String> row, String expected) {
    for (Map.Entry<String, String> entry : row.entrySet())
        if (entry.getKey().equalsIgnoreCase(expected)) return entry.getValue() == null ? "" : entry.getValue();
    return "";
}

// 【新增】读取 Excel 最后一行的专辑编号（第 7 列）
public static String readLastAlbumIdFromExcel(MczTool parent) {
    if (parent.currentWorkbook == null) return "";
    String lastId = "";
    try {
        Sheet sheet = parent.currentWorkbook.getSheetAt(0);
        DataFormatter fmt = new DataFormatter();
        for (int i = sheet.getLastRowNum(); i >= 0; i--) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            String val = fmt.formatCellValue(r.getCell(7)).trim();
            if (!val.isEmpty()) { lastId = val; break; }
        }
    } catch (Exception e) {
        System.err.println("警告：读取 Excel 专辑编号失败: " + e.getMessage());
    }
    return lastId;
}

public static void refreshAlbumIdHint(MczTool parent) {
    parent.taskQueue.submit(() -> {
        String lastId = readLastAlbumIdFromExcel(parent);
        SwingUtilities.invokeLater(() -> {
            parent.currentLastAlbumId = lastId;
            if (!lastId.isEmpty()) {
                parent.albumIdHintLabel.setText("最新: " + lastId);
            } else {
                parent.albumIdHintLabel.setText("暂无数据");
            }
        });
    });
}

// 【新增】为菜单添加弹出渐显效果（可复用于所有菜单）
public static void addPopupFadeIn(MczTool parent, JMenu menu) {
    JPopupMenu popup = menu.getPopupMenu();
    popup.setLightWeightPopupEnabled(false); // 强制重量级弹窗，Window.setOpacity 才生效
    popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
        javax.swing.Timer fadeTimer;
        Window popupWindow;
        public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            // 延迟到弹窗 Window 创建完成后再设透明度
            SwingUtilities.invokeLater(() -> {
                popupWindow = SwingUtilities.getWindowAncestor(popup);
                if (popupWindow != null) {
                    try {
                        popupWindow.setOpacity(0.08f);
                        if (fadeTimer != null) fadeTimer.stop();
                        fadeTimer = new javax.swing.Timer(16, null);
                        fadeTimer.addActionListener(evt -> {
                            float op = Math.min(1f, popupWindow.getOpacity() + 0.2f);
                            popupWindow.setOpacity(op);
                            if (op >= 1f) fadeTimer.stop();
                        });
                        fadeTimer.start();
                    } catch (Exception ignored) {
                        // 系统不支持窗口透明度时静默回退
                    }
                }
            });
        }
        public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            if (fadeTimer != null) { fadeTimer.stop(); fadeTimer = null; }
            popupWindow = null;
        }
        public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            if (fadeTimer != null) { fadeTimer.stop(); fadeTimer = null; }
            popupWindow = null;
        }
    });
}

// 【新增】同步至Bot：将 songs.xlsx 转为 songs.csv 放入桌面 SongBot 文件夹
public static void syncToBot(MczTool parent) {
    if (parent.currentWorkbook == null || parent.currentWorkbook.getNumberOfSheets() == 0) {
        JOptionPane.showMessageDialog(parent, "暂无表格数据，请先导入谱面！", "提示", JOptionPane.WARNING_MESSAGE);
        return;
    }
    try {
        File desktopDir = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        if (desktopDir == null || !desktopDir.exists())
            desktopDir = new File(System.getProperty("user.home"));
        File songBotDir = new File(desktopDir, "SongBot");
        if (!songBotDir.exists()) songBotDir.mkdirs();
        File csvFile = new File(songBotDir, "songs.csv");

        Sheet sheet = parent.currentWorkbook.getSheetAt(0);
        DataFormatter fmt = new DataFormatter();

        // xlsx 已含 44 列表头，与 CSV 一一对应，直接用第 0 行作表头
        Row xlHeader = sheet.getRow(0);
        int colCount = xlHeader == null ? 44 : Math.max(xlHeader.getLastCellNum(), 1);

        try (FileOutputStream fos = new FileOutputStream(csvFile);
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            fos.write(new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF}); // UTF-8 BOM
            // 写表头（直接从 xlsx 第 0 行读取）
            StringBuilder headerSB = new StringBuilder();
            for (int c = 0; c < colCount; c++) {
                if (c > 0) headerSB.append(",");
                Cell hc = xlHeader.getCell(c);
                headerSB.append(hc == null ? "" : fmt.formatCellValue(hc).trim());
            }
            bw.write(headerSB.toString());
            bw.newLine();
            // 写数据（xlsx 列与 CSV 列 1:1 直通）
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String idVal = fmt.formatCellValue(row.getCell(0)).trim();
                if (idVal.isEmpty()) continue;

                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < colCount; c++) {
                    if (c > 0) sb.append(",");
                    Cell cell = row.getCell(c);
                    String val = (cell == null) ? "" : fmt.formatCellValue(cell).trim();
                    // 歌名/谱师清理【xxx】前缀
                    if ((c == 1 || c == 3) && !val.isEmpty())
                        val = val.replaceAll("^【[^】]*】\\s*", "");
                    if (val.contains(",") || val.contains("\"") || val.contains("\n"))
                        val = "\"" + val.replace("\"", "\"\"") + "\"";
                    sb.append(val);
                }
                bw.write(sb.toString());
                bw.newLine();
            }
            bw.flush();
        }

        JOptionPane.showMessageDialog(parent,
                "同步完成！\nsongs.csv 已生成至：\n" + csvFile.getAbsolutePath(),
                "同步至Bot", JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
        JOptionPane.showMessageDialog(parent, "同步失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
    }
}

// 【新增】统一所有行高为 18
public static void normalizeRowHeights(MczTool parent) {
    if (parent.currentWorkbook == null) return;
    Sheet sheet = parent.currentWorkbook.getSheetAt(0);
    for (int i = 0; i <= sheet.getLastRowNum(); i++) {
        Row row = sheet.getRow(i);
        if (row != null) row.setHeight((short) 360); // 18pt = 360 twips
    }
}

// 【新增】弹出表格预览窗口，支持编辑、删行、导出
public static void showExcelPreview(MczTool parent) {
    if (parent.currentWorkbook == null || parent.currentWorkbook.getNumberOfSheets() == 0) {
        JOptionPane.showMessageDialog(parent, "暂无表格数据", "提示", JOptionPane.INFORMATION_MESSAGE);
        return;
    }
    Sheet sheet = parent.currentWorkbook.getSheetAt(0);
    DataFormatter fmt = new DataFormatter();

    // 读取表头（第 0 行）
    Row headerRow = sheet.getRow(0);
    int colCount = headerRow == null ? 34 : Math.max(headerRow.getLastCellNum(), 1);
    String[] headers = new String[colCount];
    if (headerRow != null) {
        for (int c = 0; c < colCount; c++) {
            Cell cell = headerRow.getCell(c);
            headers[c] = cell == null ? "" : fmt.formatCellValue(cell);
        }
    }

    // 撤销栈
    java.util.List<Object[][]> undoStack = new ArrayList<>();

    // 打破循环引用：数组持有 model，lambda 捕获数组
    final DefaultTableModel[] modelHolder = new DefaultTableModel[1];
    java.util.function.Supplier<Object[][]> tableSnapshot = () -> {
        DefaultTableModel m = modelHolder[0];
        Object[][] s = new Object[m.getRowCount()][colCount];
        for (int r = 0; r < m.getRowCount(); r++)
            for (int c = 0; c < colCount; c++)
                s[r][c] = m.getValueAt(r, c);
        return s;
    };
    Consumer<Object[][]> restoreSnapshot = (s) -> {
        DefaultTableModel m = modelHolder[0];
        m.setRowCount(0);
        for (Object[] row : s) m.addRow(row);
    };

    DefaultTableModel model = new DefaultTableModel(headers, 0) {
        @Override
        public void setValueAt(Object val, int row, int col) {
            Object old = getValueAt(row, col);
            if (!Objects.equals(old, val)) {
                undoStack.add(tableSnapshot.get());
                if (undoStack.size() > 50) undoStack.remove(0);
            }
            super.setValueAt(val, row, col);
        }
    };
    modelHolder[0] = model;
    int lastRow = sheet.getLastRowNum();
    for (int r = 1; r <= lastRow; r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;
        Object[] rowData = new Object[colCount];
        for (int c = 0; c < colCount; c++) {
            Cell cell = row.getCell(c);
            rowData[c] = cell == null ? "" : fmt.formatCellValue(cell);
        }
        model.addRow(rowData);
    }

    JTable table = new JTable(model);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
    table.getTableHeader().setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
    table.setRowHeight(22);

    JScrollPane tableScroll = new JScrollPane(table);
    tableScroll.setPreferredSize(new Dimension(950, 480));

    // 将 JTable 修改写回 parent.currentWorkbook
    java.util.function.Consumer<String> syncTableToWorkbook = (actionLabel) -> {
        table.editCellAt(-1, -1); // 提交正在编辑的单元格
        // 清空并重建 sheet（保留 0 行表头）
        for (int i = sheet.getLastRowNum(); i >= 1; i--) {
            Row r = sheet.getRow(i);
            if (r != null) sheet.removeRow(r);
        }
        for (int r = 0; r < model.getRowCount(); r++) {
            Row xlRow = sheet.createRow(r + 1);
            for (int c = 0; c < model.getColumnCount(); c++) {
                Object val = model.getValueAt(r, c);
                Cell cell = xlRow.createCell(c);
                cell.setCellValue(val != null ? val.toString() : "");
            }
        }
        undoStack.clear();
        JOptionPane.showMessageDialog(tableScroll, actionLabel, "完成", JOptionPane.INFORMATION_MESSAGE);
    };

    // 删除选中行
    UiKit.ModernButton deleteBtn = new UiKit.ModernButton("删除选中行");
    deleteBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
    deleteBtn.setCustomColors(new Color(255, 225, 225), new Color(245, 195, 195), new Color(235, 170, 170));
    deleteBtn.addActionListener(e -> {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(tableScroll, "请先选中要删除的行", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(tableScroll,
                "确认删除选中的 " + rows.length + " 行数据？", "删除确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            undoStack.add(tableSnapshot.get());
            for (int i = rows.length - 1; i >= 0; i--) model.removeRow(rows[i]);
        }
    });

    // 保存修改
    UiKit.ModernButton saveBtn = new UiKit.ModernButton("保存修改到表格");
    saveBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
    saveBtn.setCustomColors(new Color(220, 235, 255), new Color(180, 210, 250), new Color(150, 190, 240));
    saveBtn.addActionListener(e -> syncTableToWorkbook.accept("修改已保存"));

    // 导出
    UiKit.ModernButton exportBtn = new UiKit.ModernButton("<html><font face='Segoe UI Emoji'></font> 导出 Excel 文件</html>");
    exportBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
    exportBtn.setCustomColors(new Color(220, 245, 220), new Color(180, 225, 180), new Color(150, 210, 150));
    exportBtn.addActionListener(e -> {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("songs.xlsx"));
        if (chooser.showSaveDialog(tableScroll) == JFileChooser.APPROVE_OPTION) {
            File dest = chooser.getSelectedFile();
            if (!dest.getName().toLowerCase().endsWith(".xlsx")) {
                dest = new File(dest.getAbsolutePath() + ".xlsx");
            }
            normalizeRowHeights(parent);
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                parent.currentWorkbook.write(fos);
                JOptionPane.showMessageDialog(tableScroll, "导出成功！\n" + dest.getAbsolutePath(), "导出完成", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(tableScroll, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    });

    UiKit.ModernButton closeBtn = new UiKit.ModernButton("关闭");
    closeBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
    closeBtn.addActionListener(e -> SwingUtilities.getWindowAncestor(tableScroll).dispose());

    JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
    UiKit.ModernButton syncBotBtn = new UiKit.ModernButton("同步至Bot");
    syncBotBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
    syncBotBtn.setCustomColors(new Color(240, 230, 255), new Color(220, 205, 250), new Color(200, 180, 240));
    syncBotBtn.addActionListener(e -> {
        syncTableToWorkbook.accept("修改已保存"); // 复用已验证的保存逻辑
        SwingUtilities.getWindowAncestor(tableScroll).dispose();
        syncToBot(parent);
    });

    UiKit.ModernButton syncFileBtn1 = new UiKit.ModernButton("同步表格");
    syncFileBtn1.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
    syncFileBtn1.setCustomColors(new Color(255, 245, 220), new Color(250, 230, 190), new Color(240, 215, 170));
    syncFileBtn1.addActionListener(e -> {
        File f = getDesktopExcelFile();
        if (!f.exists()) { JOptionPane.showMessageDialog(tableScroll, "桌面上未找到 songs.xlsx！", "提示", JOptionPane.WARNING_MESSAGE); return; }
        try (FileInputStream fis = new FileInputStream(f)) {
            parent.currentWorkbook = new XSSFWorkbook(fis);
            SwingUtilities.getWindowAncestor(tableScroll).dispose();
            showExcelPreview(parent);
        } catch (Exception ex) { JOptionPane.showMessageDialog(tableScroll, "读取失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE); }
    });

    btnPanel.add(deleteBtn);
    btnPanel.add(saveBtn);
    btnPanel.add(exportBtn);
    btnPanel.add(syncBotBtn);
    btnPanel.add(syncFileBtn1);
    btnPanel.add(closeBtn);

    JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
    mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    mainPanel.add(tableScroll, BorderLayout.CENTER);
    mainPanel.add(btnPanel, BorderLayout.SOUTH);

    JDialog dialog = new JDialog(parent, "表格预览", true);
    dialog.setContentPane(mainPanel);

    // Ctrl+Z/Ctrl+S — 绑在 table 上避免被表格自身的 InputMap 拦截
    javax.swing.Action undoAction = new AbstractAction() {
        public void actionPerformed(ActionEvent e) {
            if (!undoStack.isEmpty()) restoreSnapshot.accept(undoStack.remove(undoStack.size() - 1));
        }
    };
    javax.swing.Action saveAction = new AbstractAction() {
        public void actionPerformed(ActionEvent e) { syncTableToWorkbook.accept("修改已保存"); }
    };
    // 绑定到 JTable（优先级最高）
    table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Z"), "undo");
    table.getActionMap().put("undo", undoAction);
    table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control S"), "save");
    table.getActionMap().put("save", saveAction);
    // 也绑到对话框根面板，确保焦点在按钮上时也能触发
    mainPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Z"), "undo");
    mainPanel.getActionMap().put("undo", undoAction);
    mainPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control S"), "save");
    mainPanel.getActionMap().put("save", saveAction);

    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
}

}
