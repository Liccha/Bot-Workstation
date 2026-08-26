package com.mcz;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.List;
import java.util.*;

/**
 * Mode3 / Catch 模式信息面板工厂 — 生成谱面难度矩阵卡片
 */
public class Mode3PanelFactory {

    public static JPanel createMode3InfoPanel(MczTool parent) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(new Color(245, 248, 255));
        col.setPreferredSize(new Dimension(260, 140));

        TitledBorder m3Border = BorderFactory.createTitledBorder(
                new UiKit.ModernRoundedBorder(new Color(180, 200, 230), 1, 10), "Catch模式");
        m3Border.setTitleFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        m3Border.setTitleJustification(TitledBorder.CENTER);
        col.setBorder(m3Border);

        List<Map<String, String>> m3 = new ArrayList<>();
        if (parent.currentCharts != null)
            for (Map<String, String> c : parent.currentCharts)
                if ("true".equals(c.get("isMode3"))) m3.add(c);
        m3.sort((a, b) -> {
            int la = Integer.parseInt(a.get("level"));
            int lb = Integer.parseInt(b.get("level"));
            return Integer.compare(la, lb);
        });

        int total = m3.size();
        Color[][] colorMap = {
            {new Color(230, 250, 230), new Color(0, 100, 0)},
            {new Color(255, 250, 210), new Color(130, 100, 0)},
            {new Color(255, 225, 225), new Color(180, 0, 0)},
        };

        col.add(Box.createVerticalGlue());
        for (int i = 0; i < total; i++) {
            Map<String, String> c = m3.get(i);
            String dn = c.get("diffName");
            String val = (dn != null ? dn : "") + " Lv." + c.get("level");
            boolean rainbow = i >= 3;
            Color bg, fg;
            if (rainbow) {
                bg = new Color(255, 235, 255); fg = new Color(100, 0, 130);
            } else {
                bg = colorMap[i][0]; fg = colorMap[i][1];
            }
            UiKit.CapsuleLabel lbl = new UiKit.CapsuleLabel("", val, bg, fg, rainbow, false, parent.fzCyFont);
            lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            col.add(lbl);
            if (i < total - 1) col.add(Box.createVerticalStrut(10));
        }
        col.add(Box.createVerticalGlue());
        return col;
    }

    public static JPanel createKModeColumn(MczTool parent, String k) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(new Color(245, 248, 255));
        col.setPreferredSize(new Dimension(215, 140));

        TitledBorder border = BorderFactory.createTitledBorder(
                new UiKit.ModernRoundedBorder(new Color(180, 200, 230), 1, 10), k);
        border.setTitleFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        border.setTitleJustification(TitledBorder.CENTER);
        col.setBorder(border);

        boolean hasGlobalMX = false;
        boolean hasGlobalSP = false;
        if (parent.currentCharts != null) {
            for (Map<String, String> c : parent.currentCharts) {
                String ver = c.get("version").toLowerCase();
                if (ver.contains("mx") || ver.contains("ms") || ver.contains("master")) hasGlobalMX = true;
                if (ver.contains("sp") || ver.contains("special")) hasGlobalSP = true;
            }
        }

        String[][] diffCheck = {
                {"简单", "ez", "easy"}, {"普通", "nm", "normal"}, {"困难", "hd", "hard"},
                {"大师", "mx", "master", "ms"}, {"特殊", "sp", "special"}
        };

        for (int idx = 0; idx < diffCheck.length; idx++) {
            String[] check = diffCheck[idx];
            String diffName = check[0];
            String value = "";

            for (Map<String, String> c : parent.currentCharts) {
                if (c.get("kMode").equals(k)) {
                    String ver = c.get("version").toLowerCase();
                    boolean match = false;
                    for(int i=1; i<check.length; i++) if(ver.contains(check[i])) { match = true; break; }
                    if (match) { value = c.get("level") + "-" + c.get("combo"); break; }
                }
            }

            boolean isMX = diffName.equals("大师");
            boolean isSP = diffName.equals("特殊");
            if ((isMX && !hasGlobalMX) || (isSP && !hasGlobalSP)) continue;

            String level = "0";
            String combo = "0";
            if (!value.isEmpty()) {
                String[] parts = value.split("-");
                if (parts.length == 2) {
                    level = parts[0];
                    combo = parts[1];
                }
            }
            String content = level + "级  键数：" + combo;

            Color bg = Color.WHITE;
            Color fg = Color.BLACK;
            boolean isRainbow = false;

            switch (idx) {
                case 0: bg = new Color(230, 250, 230); fg = new Color(0, 100, 0); break;
                case 1: bg = new Color(255, 250, 210); fg = new Color(130, 100, 0); break;
                case 2: bg = new Color(255, 225, 225); fg = new Color(180, 0, 0); break;
                case 3: fg = new Color(80, 50, 100); isRainbow = true; break;
                case 4: fg = new Color(80, 50, 100); isRainbow = true; break;
            }

            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 1));
            rowPanel.setOpaque(false);
            rowPanel.add(new UiKit.CapsuleLabel(diffName + " ", content, bg, fg, isRainbow, false, parent.fzCyFont));
            col.add(rowPanel);
        }
        return col;
    }
}
