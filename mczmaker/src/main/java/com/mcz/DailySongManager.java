package com.mcz;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.awt.event.ActionEvent;

/** 每日歌曲管理器：拖入MP3 + 自动编号 + CSV/XLSX 预览 */
public class DailySongManager {

    public static void showManager(Frame parent) {
        final File desktopDir;
        File tmp = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        if (tmp == null || !tmp.exists()) tmp = new File(System.getProperty("user.home"));
        desktopDir = tmp;
        File songBotDir = new File(desktopDir, "SongBot");
        File dailySongsDir = new File(desktopDir, "DailySongs");
        if (!dailySongsDir.exists()) dailySongsDir.mkdirs();
        File dailyCsv = new File(songBotDir, "daily_songs.csv");

        List<String[]> entries = new ArrayList<>();
        int nextId = 1;
        if (dailyCsv.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(dailyCsv), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                if (line != null && line.startsWith("﻿")) line = line.substring(1);
                while ((line = br.readLine()) != null) {
                    String[] parts = parseCsvLine(line, 3);
                    if (parts.length >= 3 && !parts[0].isEmpty()) {
                        entries.add(new String[]{parts[0], cleanField(parts[1]), cleanField(parts[2])});
                        try { nextId = Math.max(nextId, Integer.parseInt(parts[0]) + 1); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Exception ex) { ex.printStackTrace(); }
        }

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String[] e : entries) listModel.addElement(e[0] + ". " + e[1] + " — " + e[2]);
        JList<String> songList = new JList<>(listModel);
        songList.setFont(dailySongListFont());
        songList.setCellRenderer(new MultilingualListRenderer());
        JScrollPane listScroll = new JScrollPane(songList);
        listScroll.setPreferredSize(new Dimension(500, 200));

        JPanel dropZone = new JPanel(new BorderLayout());
        dropZone.setPreferredSize(new Dimension(0, 60));
        dropZone.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        dropZone.setBorder(BorderFactory.createTitledBorder("拖入 MP3 文件"));
        JLabel dropLabel = new JLabel("将 .mp3 文件拖入此区域", JLabel.CENTER);
        dropLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        dropLabel.setForeground(Color.GRAY);
        dropZone.add(dropLabel, BorderLayout.CENTER);
        final File[] droppedMp3 = {null};

        JTextField idField = new JTextField(String.valueOf(nextId));
        idField.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        idField.setPreferredSize(new Dimension(0, 32));
        idField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(0, 8, 0, 8)));
        JLabel idHint = new JLabel("最新ID: " + (nextId > 1 ? (nextId - 1) : 0));
        idHint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        idHint.setForeground(new Color(80, 150, 80));
        UiKit.ModernButton idPlusBtn = new UiKit.ModernButton("+1");
        idPlusBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 10));
        idPlusBtn.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        idPlusBtn.setPreferredSize(new Dimension(40, 20));
        idPlusBtn.setMaximumSize(new Dimension(40, 20));
        idPlusBtn.setCustomColors(new Color(220, 245, 220), new Color(180, 225, 180), new Color(150, 210, 150));
        idPlusBtn.addActionListener(ev -> {
            try { idField.setText(String.valueOf(Integer.parseInt(idField.getText().trim()) + 1)); }
            catch (NumberFormatException ignored) {}
        });

        JTextField nameField = new JTextField();
        nameField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        JTextField authorField = new JTextField();
        authorField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));

        JPanel idRow = new JPanel(new BorderLayout(4, 0)); idRow.setOpaque(false);
        JLabel idLB = new JLabel("编号:"); idLB.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        idLB.setPreferredSize(new Dimension(45, 25));
        idRow.add(idLB, BorderLayout.WEST); idRow.add(idField, BorderLayout.CENTER);
        JPanel idRight = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); idRight.setOpaque(false);
        idRight.add(idHint); idRight.add(idPlusBtn); idRow.add(idRight, BorderLayout.EAST);

        JPanel nameRow = new JPanel(new BorderLayout(8, 0)); nameRow.setOpaque(false);
        JLabel nmL = new JLabel("歌名:"); nmL.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        nmL.setPreferredSize(new Dimension(45, 25));
        nameRow.add(nmL, BorderLayout.WEST); nameRow.add(nameField, BorderLayout.CENTER);
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));

        JPanel authorRow = new JPanel(new BorderLayout(8, 0)); authorRow.setOpaque(false);
        JLabel auL = new JLabel("作者:"); auL.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        auL.setPreferredSize(new Dimension(45, 25));
        authorRow.add(auL, BorderLayout.WEST); authorRow.add(authorField, BorderLayout.CENTER);
        authorRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));

        UiKit.ModernButton addBtn = new UiKit.ModernButton("添加并保存");
        addBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        addBtn.setCustomColors(new Color(220, 245, 220), new Color(180, 225, 180), new Color(150, 210, 150));
        UiKit.ModernButton closeBtn = new UiKit.ModernButton("关闭");
        closeBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));

        addBtn.addActionListener(ev -> {
            String name = nameField.getText().trim();
            String author = authorField.getText().trim();
            if (name.isEmpty() || author.isEmpty()) { JOptionPane.showMessageDialog(null, "歌名和作者不能为空！"); return; }
            String dupId = null;
            for (String[] e : entries) { if (e[1].equalsIgnoreCase(name)) { dupId = e[0]; break; } }
            if (dupId != null) {
                if (JOptionPane.showConfirmDialog(null, "歌名《"+name+"》已存在（编号 "+dupId+"）\n是否仍要添加？", "重复歌名", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            }
            if (droppedMp3[0] == null) { JOptionPane.showMessageDialog(null, "请先拖入 MP3 文件！"); return; }
            int thisId;
            try { thisId = Integer.parseInt(idField.getText().trim()); }
            catch (NumberFormatException ex) { JOptionPane.showMessageDialog(null, "编号必须是数字！"); return; }
            File destMp3 = new File(dailySongsDir, thisId + ".mp3");
            try { java.nio.file.Files.copy(droppedMp3[0].toPath(), destMp3.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception ex) { JOptionPane.showMessageDialog(null, "文件复制失败: " + ex.getMessage()); return; }
            entries.add(new String[]{String.valueOf(thisId), name, author});
            listModel.addElement(thisId + ". " + name + " — " + author);
            int newNext = Math.max(thisId + 1, Integer.parseInt(idField.getText().trim()) + 1);
            idField.setText(String.valueOf(newNext));
            idHint.setText("最新ID: " + (newNext - 1));
            nameField.setText(""); authorField.setText("");
            droppedMp3[0] = null; dropLabel.setText("将 .mp3 文件拖入此区域"); dropLabel.setForeground(Color.GRAY);
            saveCsv(dailyCsv, songBotDir, entries);
            try { saveXlsx(new File(desktopDir, "daily_songs.xlsx"), entries); } catch (Exception ignored) {}
            JOptionPane.showMessageDialog(null, "已保存！\n编号 " + thisId + " → DailySongs/" + thisId + ".mp3");
        });
        closeBtn.addActionListener(e -> SwingUtilities.getWindowAncestor(listScroll).dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        btnPanel.add(addBtn); btnPanel.add(closeBtn);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        JLabel lt = new JLabel("已有歌曲:", JLabel.CENTER); lt.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        lt.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainPanel.add(lt); mainPanel.add(Box.createVerticalStrut(5)); mainPanel.add(listScroll);
        mainPanel.add(Box.createVerticalStrut(10)); mainPanel.add(dropZone);
        mainPanel.add(Box.createVerticalStrut(8));
        JLabel it = new JLabel("歌曲信息:", JLabel.CENTER); it.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        it.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainPanel.add(it); mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(idRow); mainPanel.add(Box.createVerticalStrut(8));
        mainPanel.add(nameRow); mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(authorRow); mainPanel.add(Box.createVerticalStrut(10));
        mainPanel.add(btnPanel);

        JDialog dialog = new JDialog(parent, "每日歌曲管理 (支持任意位置拖入MP3)", true);
        dialog.setContentPane(mainPanel);
        new DropTarget(dialog, new DropTargetAdapter() {
            public void drop(DropTargetDropEvent e) {
                try { e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked") List<File> files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) { File f = files.get(0);
                        if (f.getName().toLowerCase().endsWith(".mp3")) {
                            droppedMp3[0] = f; dropLabel.setText(f.getName()); dropLabel.setForeground(new Color(0,130,0));
                            String fn = f.getName(); fn = fn.substring(0, fn.lastIndexOf('.'));
                            int di = fn.indexOf(" - "); if (di > 0) { authorField.setText(fn.substring(0,di).trim()); nameField.setText(fn.substring(di+3).trim()); }
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
        dialog.pack(); dialog.setLocationRelativeTo(parent); dialog.setVisible(true);
    }

    // ====== CSV 预览（复刻 songs 预览） ======

    public static void showPreview(Frame parent) {
        final File desktopDir;
        File tmp = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        if (tmp == null || !tmp.exists()) tmp = new File(System.getProperty("user.home"));
        desktopDir = tmp;
        File songBotDir = new File(desktopDir, "SongBot");
        if (!songBotDir.exists()) songBotDir.mkdirs();
        File dailyCsv = new File(songBotDir, "daily_songs.csv");

        List<String[]> rows = new ArrayList<>();
        String[] headers = {"id", "song_name", "author"};
        File desktopXlsx = new File(desktopDir, "daily_songs.xlsx");
        if (desktopXlsx.exists()) {
            try (FileInputStream fis = new FileInputStream(desktopXlsx);
                 Workbook wb = new XSSFWorkbook(fis)) {
                Sheet sheet = wb.getSheetAt(0);
                DataFormatter fmt = new DataFormatter();
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row r = sheet.getRow(i); if (r == null) continue;
                    String id = fmt.formatCellValue(r.getCell(0)).trim(); if (id.isEmpty()) continue;
                    rows.add(new String[]{id, fmt.formatCellValue(r.getCell(1)).trim(), fmt.formatCellValue(r.getCell(2)).trim()});
                }
            } catch (Exception ex) { ex.printStackTrace(); }
        } else if (dailyCsv.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(dailyCsv), StandardCharsets.UTF_8))) {
                String line = br.readLine(); if (line != null && line.startsWith("﻿")) line = line.substring(1);
                while ((line = br.readLine()) != null) {
                    String[] parts = parseCsvLine(line, 3);
                    if (parts.length >= 3 && !parts[0].isEmpty())
                        rows.add(new String[]{parts[0], cleanField(parts[1]), cleanField(parts[2])});
                }
            } catch (Exception ex) { ex.printStackTrace(); }
        }

        List<Object[][]> undoStack = new ArrayList<>();
        String[] hdrs = headers;
        DefaultTableModel model = new DefaultTableModel(hdrs, 0) {
            @Override public void setValueAt(Object val, int row, int col) {
                Object old = getValueAt(row, col);
                if (!Objects.equals(old, val)) { undoStack.add(snapshot()); if (undoStack.size() > 50) undoStack.remove(0); }
                super.setValueAt(val, row, col);
            }
            private Object[][] snapshot() { Object[][] s = new Object[getRowCount()][3]; for (int r=0;r<getRowCount();r++) for(int c=0;c<3;c++) s[r][c]=getValueAt(r,c); return s; }
        };
        Supplier<Object[][]> ts = () -> { Object[][] s=new Object[model.getRowCount()][3]; for(int r=0;r<model.getRowCount();r++) for(int c=0;c<3;c++) s[r][c]=model.getValueAt(r,c); return s; };
        Consumer<Object[][]> rs = (s) -> { model.setRowCount(0); for(Object[] row : s) model.addRow(row); };
        for (String[] r : rows) model.addRow(r);

        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        Font df = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
        table.setFont(df); table.getTableHeader().setFont(df.deriveFont(Font.BOLD));
        table.setDefaultRenderer(Object.class, new MultilingualTableRenderer());
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(400);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(780, 400));

        Consumer<String> saveToCsv = (actionLabel) -> {
            table.editCellAt(-1, -1);
            try (FileOutputStream fos = new FileOutputStream(dailyCsv);
                 BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
                fos.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});
                bw.write("id,song_name,author"); bw.newLine();
                for (int r=0; r<model.getRowCount(); r++) {
                    StringBuilder sb = new StringBuilder();
                    for (int c=0; c<3; c++) {
                        if(c>0)sb.append(","); String v = model.getValueAt(r,c)!=null?model.getValueAt(r,c).toString().trim():"";
                        if(v.contains(",")||v.contains("\"")||v.contains("\n")) v="\""+v.replace("\"","\"\"")+"\"";
                        sb.append(v);
                    }
                    bw.write(sb.toString()); bw.newLine();
                }
            } catch (Exception ex) { JOptionPane.showMessageDialog(tableScroll,"保存失败: "+ex.getMessage()); return; }
            undoStack.clear(); JOptionPane.showMessageDialog(tableScroll, actionLabel);
        };

        UiKit.ModernButton delBtn = new UiKit.ModernButton("删除选中行");
        delBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        delBtn.setCustomColors(new Color(255,225,225),new Color(245,195,195),new Color(235,170,170));
        delBtn.addActionListener(e -> { int[] sel=table.getSelectedRows(); if(sel.length==0){JOptionPane.showMessageDialog(tableScroll,"请先选中要删除的行");return;}
            if(JOptionPane.showConfirmDialog(tableScroll,"确认删除选中的 "+sel.length+" 行？","删除确认",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){
                undoStack.add(ts.get()); for(int i=sel.length-1;i>=0;i--) model.removeRow(sel[i]); } });

        UiKit.ModernButton saveBtn = new UiKit.ModernButton("保存修改到表格");
        saveBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        saveBtn.setCustomColors(new Color(220,235,255),new Color(180,210,250),new Color(150,190,240));
        saveBtn.addActionListener(e -> saveToCsv.accept("修改已保存"));

        UiKit.ModernButton exportBtn = new UiKit.ModernButton("<html><font face='Segoe UI Emoji'>📤</font> 导出 CSV</html>");
        exportBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        exportBtn.setCustomColors(new Color(220,245,220),new Color(180,225,180),new Color(150,210,150));
        exportBtn.addActionListener(e -> { JFileChooser fc=new JFileChooser(); fc.setSelectedFile(new File("daily_songs.csv"));
            if(fc.showSaveDialog(tableScroll)==JFileChooser.APPROVE_OPTION){ File d=fc.getSelectedFile(); if(!d.getName().toLowerCase().endsWith(".csv")) d=new File(d.getAbsolutePath()+".csv");
                try(FileOutputStream fos=new FileOutputStream(d); BufferedWriter bw=new BufferedWriter(new OutputStreamWriter(fos,StandardCharsets.UTF_8))){
                    fos.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF}); bw.write("id,song_name,author"); bw.newLine();
                    for(int r=0;r<model.getRowCount();r++){ StringBuilder sb=new StringBuilder(); for(int c=0;c<3;c++){ if(c>0)sb.append(",");
                        String v=model.getValueAt(r,c)!=null?model.getValueAt(r,c).toString().trim():"";
                        if(v.contains(",")||v.contains("\"")||v.contains("\n")) v="\""+v.replace("\"","\"\"")+"\""; sb.append(v); }
                        bw.write(sb.toString()); bw.newLine(); }
                    JOptionPane.showMessageDialog(tableScroll,"导出成功！\n"+d.getAbsolutePath());
                } catch(Exception ex){JOptionPane.showMessageDialog(tableScroll,"导出失败: "+ex.getMessage());} } });

        UiKit.ModernButton syncFileBtn = new UiKit.ModernButton("同步表格");
        syncFileBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        syncFileBtn.setCustomColors(new Color(255,245,220),new Color(250,230,190),new Color(240,215,170));
        syncFileBtn.addActionListener(e -> { File f = new File(desktopDir,"daily_songs.xlsx"); if(!f.exists()){JOptionPane.showMessageDialog(tableScroll,"桌面上未找到 daily_songs.xlsx！");return;}
            SwingUtilities.getWindowAncestor(tableScroll).dispose(); DailySongManager.showPreview(parent); });

        UiKit.ModernButton syncBotBtn = new UiKit.ModernButton("同步至Bot");
        syncBotBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        syncBotBtn.setCustomColors(new Color(240,230,255),new Color(220,205,250),new Color(200,180,240));
        syncBotBtn.addActionListener(e -> { saveToCsv.accept("修改已保存"); SwingUtilities.getWindowAncestor(tableScroll).dispose(); });

        UiKit.ModernButton closeBtn = new UiKit.ModernButton("关闭");
        closeBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        closeBtn.addActionListener(e -> SwingUtilities.getWindowAncestor(tableScroll).dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        btnPanel.add(delBtn); btnPanel.add(saveBtn); btnPanel.add(exportBtn);
        btnPanel.add(syncFileBtn); btnPanel.add(syncBotBtn); btnPanel.add(closeBtn);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(tableScroll, BorderLayout.CENTER); mainPanel.add(btnPanel, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(parent, "每日歌曲预览 (" + rows.size() + " 首)", true);
        dialog.setContentPane(mainPanel);

        javax.swing.Action ua = new AbstractAction(){ public void actionPerformed(ActionEvent e){ if(!undoStack.isEmpty()) rs.accept(undoStack.remove(undoStack.size()-1)); }};
        javax.swing.Action sa = new AbstractAction(){ public void actionPerformed(ActionEvent e){ saveToCsv.accept("修改已保存"); }};
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Z"),"undoD");
        table.getActionMap().put("undoD",ua);
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control S"),"saveD");
        table.getActionMap().put("saveD",sa);
        mainPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control Z"),"undoD");
        mainPanel.getActionMap().put("undoD",ua);
        mainPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control S"),"saveD");
        mainPanel.getActionMap().put("saveD",sa);

        dialog.pack(); dialog.setLocationRelativeTo(parent); dialog.setVisible(true);
    }

    // ====== helpers ======

    static Font dailySongListFont() {
        return new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    }

    static Font dailySongFontForCodePoint(int codePoint, int style, int size) {
        Font preferred = new Font("Microsoft YaHei UI", style, size);
        if (preferred.canDisplay(codePoint)) return preferred;
        Font fallback = new Font(Font.DIALOG, style, size);
        return fallback.canDisplay(codePoint) ? fallback : preferred;
    }

    private static JPanel multilingualText(String text, Font base, Color foreground, Color background) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setOpaque(true);
        panel.setBackground(background);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        if (text == null) text = "";
        StringBuilder run = new StringBuilder();
        Font runFont = null;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            Font next = dailySongFontForCodePoint(codePoint, base.getStyle(), base.getSize());
            if (runFont != null && !runFont.getFontName().equals(next.getFontName())) {
                panel.add(runLabel(run.toString(), runFont, foreground));
                run.setLength(0);
            }
            runFont = next;
            run.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        if (run.length() > 0) panel.add(runLabel(run.toString(), runFont == null ? base : runFont, foreground));
        panel.getAccessibleContext().setAccessibleName(text);
        return panel;
    }

    private static JLabel runLabel(String text, Font font, Color foreground) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(foreground);
        label.setOpaque(false);
        return label;
    }

    private static final class MultilingualListRenderer implements ListCellRenderer<String> {
        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                                 boolean selected, boolean focused) {
            Color background = selected ? list.getSelectionBackground() : list.getBackground();
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            return multilingualText(value, dailySongListFont(), foreground, background);
        }
    }

    private static final class MultilingualTableRenderer implements javax.swing.table.TableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focused, int row, int column) {
            Color background = selected ? table.getSelectionBackground() : table.getBackground();
            Color foreground = selected ? table.getSelectionForeground() : table.getForeground();
            return multilingualText(value == null ? "" : String.valueOf(value), table.getFont(), foreground, background);
        }
    }

    private static void saveCsv(File csvFile, File botDir, List<String[]> entries) {
        try { if(!botDir.exists()) botDir.mkdirs();
            try(FileOutputStream fos=new FileOutputStream(csvFile); BufferedWriter bw=new BufferedWriter(new OutputStreamWriter(fos,StandardCharsets.UTF_8))){
                fos.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF}); bw.write("id,song_name,author"); bw.newLine();
                for(String[] e:entries){ StringBuilder sb=new StringBuilder(); sb.append(e[0]).append(",");
                    String n=e[1],a=e[2]; if(n.contains(",")||n.contains("\"")) n="\""+n.replace("\"","\"\"")+"\"";
                    if(a.contains(",")||a.contains("\"")) a="\""+a.replace("\"","\"\"")+"\"";
                    sb.append(n).append(",").append(a); bw.write(sb.toString()); bw.newLine(); } }
        } catch(Exception ignored){} }

    private static void saveXlsx(File xf, List<String[]> entries) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook(); Sheet s = wb.createSheet("daily_songs");
        Row h = s.createRow(0); h.createCell(0).setCellValue("id"); h.createCell(1).setCellValue("song_name"); h.createCell(2).setCellValue("author");
        for(int i=0;i<entries.size();i++){ Row r=s.createRow(i+1); r.createCell(0).setCellValue(entries.get(i)[0]); r.createCell(1).setCellValue(entries.get(i)[1]); r.createCell(2).setCellValue(entries.get(i)[2]); }
        s.setColumnWidth(0,2000); s.setColumnWidth(1,12000); s.setColumnWidth(2,10000);
        try(FileOutputStream fos=new FileOutputStream(xf)){ wb.write(fos); } wb.close(); }

    static String[] parseCsvLine(String line, int maxFields) {
        List<String> fields = new ArrayList<>(); boolean inQ=false; StringBuilder sb=new StringBuilder();
        for(int i=0;i<line.length();i++){ char ch=line.charAt(i);
            if(inQ){ if(ch=='"'){ if(i+1<line.length()&&line.charAt(i+1)=='"'){ sb.append('"'); i++; } else inQ=false; } else sb.append(ch); }
            else { if(ch=='"') inQ=true; else if(ch==','){ fields.add(sb.toString().trim()); sb.setLength(0); if(fields.size()>=maxFields-1){ sb.append(line.substring(i+1)); break; } } else sb.append(ch); }
        } fields.add(sb.toString().trim()); return fields.toArray(new String[0]); }

    static String cleanField(String s){ if(s==null||s.isEmpty()) return ""; while(s.startsWith("\"")&&s.endsWith("\"")&&s.length()>=2){ s=s.substring(1,s.length()-1); s=s.replace("\"\"","\""); } return s; }
}
