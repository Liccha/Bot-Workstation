package com.mcz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds an atomic chart-column plan when adding charts to an existing song. */
final class CatchChartRowMerger {
    private static final String EMPTY_SLOT = "0-0";
    private static final int[][] BLOCK_COLUMNS = {
            {19, 20, 21, 22, 31},
            {23, 24, 25, 26, 32},
            {27, 28, 29, 30, 33}
    };
    private static final Pattern CATCH_VALUE = Pattern.compile("^\\s*(.+?)\\s+Lv\\.\\s*(\\d+)\\s*$");

    private CatchChartRowMerger() {
    }

    /**
     * Merges incoming normal charts without overwriting existing charts, then
     * places the combined existing/incoming Catch set into one complete block.
     * The input arrays are never modified.
     */
    static String[] merge(String[] existing, String[] incoming,
                          List<Map<String, String>> incomingCatchCharts) {
        if (existing == null || incoming == null || existing.length <= 33 || incoming.length <= 33) {
            throw new IllegalArgumentException("歌曲表列数不足，无法安全补录 Catch 谱面");
        }

        String[] result = Arrays.copyOf(existing, existing.length);
        for (int i = 0; i < result.length; i++) if (result[i] == null) result[i] = "";

        List<Map<String, String>> existingCatchCharts = new ArrayList<>();
        int existingCatchBlock = -1;
        for (int block = 0; block < BLOCK_COLUMNS.length; block++) {
            boolean blockHasCatch = false;
            for (int physicalColumn : BLOCK_COLUMNS[block]) {
                Matcher matcher = catchMatcher(result[physicalColumn]);
                if (matcher == null) continue;
                if (existingCatchBlock >= 0 && existingCatchBlock != block) {
                    throw new IllegalStateException("原歌曲的 Catch 谱面已分散在多个键位块，已停止补录以避免继续破坏数据");
                }
                existingCatchBlock = block;
                blockHasCatch = true;
                existingCatchCharts.add(chart((block + 4) + "K", matcher.group(1).trim(), matcher.group(2)));
                result[physicalColumn] = EMPTY_SLOT;
            }
            // Historical rows used 1516 as an empty MX placeholder. Once a
            // Catch row is touched, normalize it so it cannot occupy the block.
            if (blockHasCatch) {
                for (int physicalColumn : BLOCK_COLUMNS[block]) {
                    if ("1516".equals(safeTrim(result[physicalColumn]))) result[physicalColumn] = EMPTY_SLOT;
                }
            }
        }

        // Add only normal chart values. Catch values are rebuilt as one block below.
        for (int column = 19; column <= 33; column++) {
            String newValue = safeTrim(incoming[column]);
            if (newValue.isEmpty() || EMPTY_SLOT.equals(newValue) || catchMatcher(newValue) != null) continue;
            String oldValue = safeTrim(result[column]);
            if (oldValue.isEmpty() || EMPTY_SLOT.equals(oldValue)) result[column] = newValue;
        }

        LinkedHashMap<String, Map<String, String>> combined = new LinkedHashMap<>();
        for (Map<String, String> chart : existingCatchCharts) putIfAbsent(combined, chart);

        if (incomingCatchCharts != null) {
            for (Map<String, String> chart : incomingCatchCharts) {
                Map<String, String> copy = new HashMap<>(chart);
                if (existingCatchBlock >= 0) copy.put("kMode", (existingCatchBlock + 4) + "K");
                putIfAbsent(combined, copy);
            }
        }

        if (!combined.isEmpty()) {
            CatchChartBlockAllocator.placeIntoNextFreeBlock(result, new ArrayList<>(combined.values()));
        }
        return result;
    }

    private static void putIfAbsent(LinkedHashMap<String, Map<String, String>> charts,
                                    Map<String, String> chart) {
        String difficulty = safeTrim(chart.get("diffName"));
        String key = difficulty.toLowerCase(Locale.ROOT);
        if (!charts.containsKey(key)) charts.put(key, chart);
    }

    private static Matcher catchMatcher(String value) {
        Matcher matcher = CATCH_VALUE.matcher(safeTrim(value));
        return matcher.matches() ? matcher : null;
    }

    private static Map<String, String> chart(String keyMode, String difficulty, String level) {
        Map<String, String> chart = new HashMap<>();
        chart.put("kMode", keyMode);
        chart.put("diffName", difficulty);
        chart.put("level", level);
        return chart;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
