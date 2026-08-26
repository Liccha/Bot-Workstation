package com.mcz;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** 活动排名管理器 */
public class EventRankManager {

    public static void show(Frame parent) {
        final File desktopDir;
        File tmp = javax.swing.filechooser.FileSystemView.getFileSystemView().getHomeDirectory();
        if (tmp == null || !tmp.exists()) tmp = new File(System.getProperty("user.home"));
        desktopDir = tmp;
        final File defaultExcel = new File(desktopDir, "event_rank.xlsx");

        Map<String, double[]> existingData = new LinkedHashMap<>();
        final int[] mCol = {0};
        if (defaultExcel.exists()) {
            try (FileInputStream fis = new FileInputStream(defaultExcel);
                 Workbook wb = new XSSFWorkbook(fis)) {
                Sheet sheet = wb.getSheetAt(0);
                Row header = sheet.getRow(0);
                if (header != null) {
                    for (Cell c : header) {
                        String h = c.getStringCellValue().trim();
                        if (h.startsWith("Event")) try {
                            mCol[0] = Math.max(mCol[0], Integer.parseInt(h.replace("Event", "").trim()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row r = sheet.getRow(i);
                    if (r == null) continue;
                    Cell nc = r.getCell(1);
                    if (nc == null) continue;
                    String name = nc.getStringCellValue().trim();
                    if (name.isEmpty()) continue;
                    double[] scores = new double[mCol[0] + 1];
                    for (int e = 0; e < mCol[0]; e++) {
                        Cell sc = r.getCell(3 + e);
                        if (sc != null && sc.getCellType() == CellType.NUMERIC) scores[e] = sc.getNumericCellValue();
                    }
                    Cell cc = r.getCell(3 + mCol[0]);
                    if (cc != null && cc.getCellType() == CellType.NUMERIC) scores[mCol[0]] = cc.getNumericCellValue();
                    existingData.put(name, scores);
                }
            } catch (Exception ex) { ex.printStackTrace(); }
        }

        DefaultTableModel tableModel = new DefaultTableModel(new String[]{"排名", "玩家", "总分", "带分"}, 0);
        JTable table = new JTable(tableModel);
        table.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Microsoft YaHei UI", Font.BOLD, 13));
        table.setRowHeight(24);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(650, 350));

        java.util.function.Consumer<Integer> refreshTable = (eventCount) -> {
            tableModel.setRowCount(0);
            int ec = Math.max(eventCount, mCol[0]);
            String[] cols = new String[4 + ec];
            cols[0] = "排名"; cols[1] = "玩家"; cols[2] = "总分";
            for (int e = 1; e <= ec; e++) cols[2 + e] = "Event" + e;
            cols[3 + ec] = "带分";
            tableModel.setColumnIdentifiers(cols);
            List<Map.Entry<String, double[]>> sorted = new ArrayList<>(existingData.entrySet());
            sorted.sort((a, b) -> {
                double ta = 0, tb = 0;
                for (double v : a.getValue()) ta += v;
                for (double v : b.getValue()) tb += v;
                return Double.compare(tb, ta);
            });
            int rank = 1;
            for (Map.Entry<String, double[]> en : sorted) {
                double[] sc = en.getValue();
                double total = 0;
                for (double v : sc) total += v;
                Object[] row = new Object[4 + ec];
                row[0] = rank++;
                row[1] = en.getKey();
                row[2] = total;
                for (int e = 0; e < ec; e++)
                    row[3 + e] = (e < sc.length - 1 && sc[e] != 0) ? sc[e] : "";
                row[3 + ec] = (sc.length > 0 && sc[sc.length - 1] != 0) ? sc[sc.length - 1] : "";
                tableModel.addRow(row);
            }
        };
        refreshTable.accept(mCol[0]);

        JTextField urlField = new JTextField("https://m.mugzone.net/score/event_rank/");
        urlField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        urlField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        final File[] htmlFile = {null};
        JLabel htmlLabel = new JLabel("或选择本地文件", JLabel.CENTER);
        htmlLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        htmlLabel.setForeground(Color.GRAY);
        UiKit.ModernButton selectHtmlBtn = new UiKit.ModernButton("浏览");
        selectHtmlBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        selectHtmlBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(desktopDir, "rank.html"));
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                htmlFile[0] = fc.getSelectedFile();
                htmlLabel.setText(htmlFile[0].getName());
                htmlLabel.setForeground(new Color(0, 130, 0));
            }
        });
        JPanel urlRow = new JPanel(new BorderLayout(5, 0));
        urlRow.setOpaque(false);
        urlRow.add(new JLabel("URL:") {{ setFont(new Font("Microsoft YaHei", Font.BOLD, 13)); }}, BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);
        JTextField eventIdField = new JTextField("1");
        eventIdField.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        eventIdField.setPreferredSize(new Dimension(50, 28));
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        fileRow.setOpaque(false);
        fileRow.add(selectHtmlBtn); fileRow.add(htmlLabel);
        fileRow.add(new JLabel("  Event ID:") {{ setFont(new Font("Microsoft YaHei", Font.BOLD, 13)); }});
        fileRow.add(eventIdField);
        JPanel sourcePanel = new JPanel();
        sourcePanel.setLayout(new BoxLayout(sourcePanel, BoxLayout.Y_AXIS));
        sourcePanel.setOpaque(false);
        sourcePanel.add(urlRow);
        sourcePanel.add(Box.createVerticalStrut(5));
        sourcePanel.add(fileRow);

        JLabel statusLabel = new JLabel(" ", JLabel.CENTER);
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(100, 100, 100));

        UiKit.ModernButton parseBtn = new UiKit.ModernButton("解析并导入");
        parseBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        parseBtn.setCustomColors(new Color(220, 235, 255), new Color(180, 210, 250), new Color(150, 190, 240));
        UiKit.ModernButton saveBtn = new UiKit.ModernButton("保存 Excel");
        saveBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        saveBtn.setCustomColors(new Color(220, 245, 220), new Color(180, 225, 180), new Color(150, 210, 150));
        UiKit.ModernButton closeBtn = new UiKit.ModernButton("关闭");
        closeBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));

        parseBtn.addActionListener(e -> {
            parseBtn.setEnabled(false);
            final Document[] docRef = {null};
            final String url = urlField.getText().trim();
            if (htmlFile[0] != null) {
                statusLabel.setText("📄 正在解析本地文件...");
                try { docRef[0] = Jsoup.parse(htmlFile[0], "UTF-8"); }
                catch (Exception ex) { statusLabel.setText("❌ 解析失败"); parseBtn.setEnabled(true); return; }
            } else if (!url.isEmpty() && url.startsWith("http")) {
                statusLabel.setText("⏳ 正在请求排名数据...");
                try {
                    String apiUrl;
                    if (url.contains("/event_rank/"))
                        apiUrl = url.replace("/score/event_rank/", "/api/score/event_rank/");
                    else if (url.contains("/event?eid=") || url.contains("/score/event")) {
                        String eid = ""; Matcher em = Pattern.compile("eid=(\\d+)").matcher(url);
                        if (em.find()) eid = em.group(1);
                        apiUrl = "https://m.mugzone.net/api/score/event/rank?eid=" + eid;
                    } else apiUrl = url;
                    URL u = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                    conn.setRequestProperty("Accept", "application/json, text/html");
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15000); conn.setReadTimeout(15000);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream is = conn.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) != -1) baos.write(buf, 0, n); }
                    String resp = baos.toString("UTF-8");
                    if (resp.trim().startsWith("{") || resp.trim().startsWith("[")) {
                        StringBuilder html = new StringBuilder("<html><body><ul class='list'>");
                        Pattern nameP = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
                        Pattern scoreP = Pattern.compile("\"score\"\\s*:\\s*([0-9.]+)");
                        Matcher nm = nameP.matcher(resp); Matcher sm = scoreP.matcher(resp);
                        List<String> names = new ArrayList<>(); List<String> scores = new ArrayList<>();
                        while (nm.find()) names.add(nm.group(1));
                        while (sm.find()) scores.add(sm.group(1));
                        int maxN = Math.min(names.size(), scores.size());
                        for (int i = 0; i < maxN; i++)
                            html.append("<li><span class='name'><a href='/accounts/user/0'>").append(names.get(i))
                                .append("</a></span><span class='score'>").append(scores.get(i)).append("</span></li>");
                        html.append("</ul></body></html>");
                        docRef[0] = Jsoup.parse(html.toString());
                    } else {
                        File savedHtml = new File(desktopDir, "rank_download.html");
                        try (FileOutputStream fos = new FileOutputStream(savedHtml)) { fos.write(baos.toByteArray()); }
                        docRef[0] = Jsoup.parse(savedHtml, "UTF-8");
                    }
                    statusLabel.setText("📄 数据获取完成，正在解析...");
                } catch (Exception ex) { statusLabel.setText("❌ 请求失败: " + ex.getMessage()); parseBtn.setEnabled(true); return; }
            } else { parseBtn.setEnabled(true); JOptionPane.showMessageDialog(null, "请输入网址或选择本地文件！"); return; }

            final int evId;
            try { evId = Integer.parseInt(eventIdField.getText().trim()); }
            catch (NumberFormatException ex) { JOptionPane.showMessageDialog(null, "Event ID 必须是数字！"); parseBtn.setEnabled(true); return; }

            java.util.function.Supplier<Integer> parseDoc = () -> {
                int count = 0;
                Elements rows = docRef[0].select("ul.list li");
                for (Element li : rows) {
                    Element nameLink = li.select("span.name a[href*=/accounts/user/]").first();
                    Element scoreSpan = li.select("span.score").first();
                    if (nameLink == null || scoreSpan == null) continue;
                    String name = nameLink.text().trim();
                    if (name.isEmpty()) continue;
                    try {
                        double score = Double.parseDouble(scoreSpan.text().replaceAll(",", "").trim());
                        if (score > 0) {
                            double[] scores = existingData.computeIfAbsent(name, k -> new double[Math.max(mCol[0], evId) + 1]);
                            if (scores.length <= evId) scores = Arrays.copyOf(scores, evId + 1);
                            scores[evId - 1] = score; existingData.put(name, scores); count++;
                        }
                    } catch (NumberFormatException ignored) {}
                }
                if (count == 0) {
                    Element contentDiv = docRef[0].getElementById("content");
                    if (contentDiv == null) contentDiv = docRef[0].body();
                    Elements links = contentDiv.select("a[href*=/accounts/user/]");
                    for (Element link : links) {
                        if (link.text().trim().isEmpty()) continue;
                        String name = link.text().trim();
                        Element rc = link.parent();
                        if (rc.parent() != null && rc.tagName().equals("span")) rc = rc.parent();
                        Element clone = rc.clone(); clone.select("a").remove(); clone.select("img").remove();
                        String text = clone.text();
                        Pattern pp = Pattern.compile("(\\d+(\\.\\d+)?)");
                        Matcher mm = pp.matcher(text.replaceAll(",", ""));
                        double maxVal = 0;
                        while (mm.find()) try { double v = Double.parseDouble(mm.group(1)); if (v > maxVal) maxVal = v; } catch (NumberFormatException ignored) {}
                        if (maxVal > 0) {
                            double[] scores = existingData.computeIfAbsent(name, k -> new double[Math.max(mCol[0], evId) + 1]);
                            if (scores.length <= evId) scores = Arrays.copyOf(scores, evId + 1);
                            scores[evId - 1] = maxVal; existingData.put(name, scores); count++;
                        }
                    }
                }
                if (count == 0) {
                    String all = docRef[0].body() != null ? docRef[0].body().text() : docRef[0].text();
                    if (all.length() > 100) {
                        Pattern pairP = Pattern.compile("([^\\d\\s]{2,40})\\s+(\\d{3,7}(?:\\.\\d+)?)");
                        Matcher pm = pairP.matcher(all.replaceAll(",", ""));
                        Set<String> seen = new HashSet<>();
                        while (pm.find()) {
                            String name = pm.group(1).trim();
                            if (name.length() < 2 || name.length() > 40 || name.matches(".*[<>\"'&=].*") || seen.contains(name)) continue;
                            try {
                                double score = Double.parseDouble(pm.group(2));
                                if (score > 0 && score < 10000000) {
                                    seen.add(name);
                                    double[] scores = existingData.computeIfAbsent(name, k -> new double[Math.max(mCol[0], evId) + 1]);
                                    if (scores.length <= evId) scores = Arrays.copyOf(scores, evId + 1);
                                    scores[evId - 1] = score; existingData.put(name, scores); count++;
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                return count;
            };
            try {
                int count = parseDoc.get();
                if (evId > mCol[0]) mCol[0] = evId;
                refreshTable.accept(mCol[0]);
                statusLabel.setText("🎉 导入完成！共 " + count + " 名玩家");
                parseBtn.setEnabled(true);
                JOptionPane.showMessageDialog(null, "导入完成！共 " + count + " 名玩家", "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) { statusLabel.setText("❌ 解析失败: " + ex.getMessage()); parseBtn.setEnabled(true); }
        });

        saveBtn.addActionListener(e -> {
            try (Workbook wb = new XSSFWorkbook()) {
                Sheet sheet = wb.createSheet("Sheet1");
                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("排名"); header.createCell(1).setCellValue("玩家"); header.createCell(2).setCellValue("总分");
                for (int i = 1; i <= mCol[0]; i++) header.createCell(2 + i).setCellValue("Event" + i);
                header.createCell(3 + mCol[0]).setCellValue("带分");
                int rowIdx = 1;
                for (int r = 0; r < tableModel.getRowCount(); r++) {
                    Row xlRow = sheet.createRow(rowIdx++);
                    for (int c = 0; c < tableModel.getColumnCount(); c++) {
                        Object v = tableModel.getValueAt(r, c);
                        if (v != null && !v.toString().isEmpty()) {
                            Cell cell = xlRow.createCell(c);
                            if (v instanceof Number) cell.setCellValue(((Number) v).doubleValue());
                            else try { cell.setCellValue(Double.parseDouble(v.toString())); } catch (NumberFormatException ignored) { cell.setCellValue(v.toString()); }
                        }
                    }
                }
                for (int i = 0; i <= 3 + mCol[0]; i++) sheet.setColumnWidth(i, 4000);
                try (FileOutputStream fos = new FileOutputStream(defaultExcel)) { wb.write(fos); }
                JOptionPane.showMessageDialog(null, "已保存至 " + defaultExcel.getAbsolutePath(), "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) { JOptionPane.showMessageDialog(null, "保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE); }
        });
        closeBtn.addActionListener(e -> SwingUtilities.getWindowAncestor(tableScroll).dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        btnPanel.add(parseBtn); btnPanel.add(saveBtn); btnPanel.add(closeBtn);
        JPanel centerPanel = new JPanel(new BorderLayout(0, 5));
        centerPanel.setOpaque(false);
        centerPanel.add(statusLabel, BorderLayout.NORTH);
        centerPanel.add(tableScroll, BorderLayout.CENTER);
        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        mainPanel.add(sourcePanel, BorderLayout.NORTH);
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        mainPanel.add(btnPanel, BorderLayout.SOUTH);

        JDialog dialog = new JDialog(parent, "活动排名管理", true);
        dialog.setContentPane(mainPanel);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
