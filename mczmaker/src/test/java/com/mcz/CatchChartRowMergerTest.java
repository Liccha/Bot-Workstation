package com.mcz;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Regression tests for Catch-aware chart backfilling. */
public final class CatchChartRowMergerTest {
    public static void main(String[] args) {
        movesExistingCatchAsAWholeWhenBackfillingNormalCharts();
        skipsEveryOccupiedBlockIncludingSp();
        placesIncomingCatchAgainstExistingNormalBlocks();
        preservesExistingNormalOnConflictingBackfill();
        refusesBackfillWhenNoWholeCatchBlockRemains();
        System.out.println("GREEN: Catch-aware backfill preserves whole key blocks.");
    }

    private static void movesExistingCatchAsAWholeWhenBackfillingNormalCharts() {
        String[] existing = blankRow();
        existing[19] = "Salad Lv.4";
        existing[20] = "Platter Lv.8";
        existing[21] = "Rain Lv.14";
        existing[22] = "1516";
        String[] incoming = blankRow();
        incoming[21] = "10-900";

        String[] merged = CatchChartRowMerger.merge(existing, incoming, null);

        assertBlock(merged, 4, "0-0", "0-0", "10-900", "0-0", "");
        assertBlock(merged, 5, "Salad Lv.4", "Platter Lv.8", "Rain Lv.14", "0-0", "0-0");
    }

    private static void skipsEveryOccupiedBlockIncludingSp() {
        String[] existing = blankRow();
        existing[19] = "Salad Lv.4";
        existing[20] = "Platter Lv.8";
        existing[21] = "Rain Lv.14";
        existing[32] = "17-1700"; // normal 5K SP
        String[] incoming = blankRow();
        incoming[19] = "3-300";

        String[] merged = CatchChartRowMerger.merge(existing, incoming, null);

        assertBlock(merged, 4, "3-300", "0-0", "0-0", "", "");
        assertEquals("17-1700", merged[32], "normal 5K SP must remain");
        assertBlock(merged, 6, "Salad Lv.4", "Platter Lv.8", "Rain Lv.14", "0-0", "0-0");
    }

    private static void placesIncomingCatchAgainstExistingNormalBlocks() {
        String[] existing = blankRow();
        existing[19] = "3-300";
        String[] incoming = blankRow();

        String[] merged = CatchChartRowMerger.merge(existing, incoming, Arrays.asList(
                chart("4K", "Salad", "4"),
                chart("4K", "Platter", "8"),
                chart("4K", "Rain", "14")
        ));

        assertEquals("3-300", merged[19], "existing normal must remain");
        assertBlock(merged, 5, "Salad Lv.4", "Platter Lv.8", "Rain Lv.14", "0-0", "0-0");
    }

    private static void preservesExistingNormalOnConflictingBackfill() {
        String[] existing = blankRow();
        existing[19] = "3-300";
        String[] incoming = blankRow();
        incoming[19] = "9-999";

        String[] merged = CatchChartRowMerger.merge(existing, incoming, null);

        assertEquals("3-300", merged[19], "backfill is add-only and must not overwrite an existing chart");
    }

    private static void refusesBackfillWhenNoWholeCatchBlockRemains() {
        String[] existing = blankRow();
        existing[19] = "Salad Lv.4";
        existing[20] = "Platter Lv.8";
        existing[21] = "Rain Lv.14";
        existing[23] = "5-500";
        existing[27] = "6-600";
        String[] incoming = blankRow();
        incoming[19] = "4-400";
        try {
            CatchChartRowMerger.merge(existing, incoming, null);
            throw new AssertionError("expected backfill to be refused");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("没有完整键位块")) {
                throw new AssertionError("unexpected failure: " + expected.getMessage());
            }
        }
    }

    private static String[] blankRow() {
        String[] columns = new String[34];
        Arrays.fill(columns, "");
        for (int column : new int[]{19, 20, 21, 23, 24, 25, 27, 28, 29}) columns[column] = "0-0";
        return columns;
    }

    private static Map<String, String> chart(String keyMode, String difficulty, String level) {
        Map<String, String> chart = new HashMap<>();
        chart.put("kMode", keyMode);
        chart.put("diffName", difficulty);
        chart.put("level", level);
        return chart;
    }

    private static void assertBlock(String[] columns, int key, String ez, String nm, String hd, String mx, String sp) {
        int base = key == 4 ? 19 : key == 5 ? 23 : 27;
        int spColumn = key == 4 ? 31 : key == 5 ? 32 : 33;
        assertEquals(ez, columns[base], key + "K EZ");
        assertEquals(nm, columns[base + 1], key + "K NM");
        assertEquals(hd, columns[base + 2], key + "K HD");
        assertEquals(mx, columns[base + 3], key + "K MX");
        assertEquals(sp, columns[spColumn], key + "K SP");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
