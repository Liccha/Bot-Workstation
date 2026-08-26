package com.botstation.ui;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;

/** Guards compact identifiers, visible separators, centering and multilingual glyphs. */
public final class UiTableRegressionTest {
    private UiTableRegressionTest() {}

    public static void main(String[] args) {
        FlatLightLaf.setup();
        JTable table = new JTable(new DefaultTableModel(
            new Object[][]{{"36004", "별바라기", "えんどろ～る！", "142", "03:20"}},
            new Object[]{"id", "song_name", "author", "bpm", "duration"}));
        UiKit.tableScroll(table);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        UiKit.applySemanticColumnWidths(table);

        require(table.getColumnModel().getColumn(0).getPreferredWidth() <= 72, "id column is still too wide");
        require(table.getShowVerticalLines() && table.getShowHorizontalLines(), "table separators are hidden");
        verifyCell(table, 0, 1, "별바라기");
        verifyCell(table, 0, 2, "えんどろ～る！");
        System.out.println("UI_TABLE_GREEN");
    }

    private static void verifyCell(JTable table, int row, int column, String value) {
        Component component = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
        require(component.getFont().canDisplayUpTo(value) < 0, "font cannot display " + value + ": " + component.getFont());
        require(component instanceof JLabel && ((JLabel) component).getHorizontalAlignment() == JLabel.CENTER,
            "cell is not centered: " + value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
