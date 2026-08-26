package com.mcz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Places one Catch chart set into one complete logical key block.
 *
 * <p>The workbook stores EZ/NM/HD/MX contiguously, but stores each key mode's
 * SP column later in the row. Treating the fifth slot as {@code base + 4}
 * therefore crosses into another key block. This allocator keeps the physical
 * column mapping explicit and reserves the whole logical block atomically.</p>
 */
final class CatchChartBlockAllocator {
    private static final String EMPTY_SLOT = "0-0";

    // Logical order inside a block: EZ, NM, HD, MX, SP.
    private static final int[][] BLOCK_COLUMNS = {
            {19, 20, 21, 22, 31}, // 4K
            {23, 24, 25, 26, 32}, // 5K
            {27, 28, 29, 30, 33}  // 6K
    };

    private CatchChartBlockAllocator() {
    }

    /**
     * Places all Catch difficulties into the first completely free key block,
     * beginning with their original key mode and then moving 4K -> 5K -> 6K.
     * Returns the selected key number, or {@code -1} when there is no Catch data.
     */
    static int placeIntoNextFreeBlock(String[] columns, List<Map<String, String>> catchCharts) {
        if (catchCharts == null || catchCharts.isEmpty()) return -1;
        if (columns == null || columns.length <= 33) {
            throw new IllegalArgumentException("歌曲表列数不足，无法安全写入 Catch 谱面");
        }
        if (catchCharts.size() > 5) {
            throw new IllegalStateException("Catch 谱面超过 5 个难度，当前歌曲表无法在一个键位块内完整保存");
        }

        int preferredBlock = blockIndex(catchCharts.get(0).get("kMode"));
        for (Map<String, String> chart : catchCharts) {
            if (blockIndex(chart.get("kMode")) != preferredBlock) {
                throw new IllegalStateException("同一组 Catch 谱面包含多个原始键位，已停止写入以避免乱行");
            }
        }

        List<Map<String, String>> sorted = new ArrayList<>(catchCharts);
        Collections.sort(sorted, new Comparator<Map<String, String>>() {
            @Override
            public int compare(Map<String, String> a, Map<String, String> b) {
                return Integer.compare(parseLevel(a), parseLevel(b));
            }
        });

        // Validate and prepare everything before touching the output row.
        int[] targetSlots = new int[sorted.size()];
        String[] targetValues = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            Map<String, String> chart = sorted.get(i);
            String difficulty = safeTrim(chart.get("diffName"));
            String level = safeTrim(chart.get("level"));
            if (difficulty.isEmpty() || level.isEmpty()) {
                throw new IllegalArgumentException("Catch 谱面缺少可识别的难度名称或等级，已停止写入");
            }
            parseLevel(chart); // Give a clear error before mutating the row.
            targetSlots[i] = sorted.size() == 1 && "Rain".equals(difficulty) ? 2 : i;
            targetValues[i] = difficulty + " Lv." + level;
        }

        int selectedBlock = -1;
        for (int offset = 0; offset < BLOCK_COLUMNS.length; offset++) {
            int candidate = (preferredBlock + offset) % BLOCK_COLUMNS.length;
            if (isCompletelyFree(columns, candidate)) {
                selectedBlock = candidate;
                break;
            }
        }
        if (selectedBlock < 0) {
            throw new IllegalStateException("4K、5K、6K 均已有普通谱面，没有完整键位块可保存 Catch 谱面");
        }

        int[] physicalColumns = BLOCK_COLUMNS[selectedBlock];
        for (int physicalColumn : physicalColumns) columns[physicalColumn] = EMPTY_SLOT;
        for (int i = 0; i < targetValues.length; i++) {
            columns[physicalColumns[targetSlots[i]]] = targetValues[i];
        }
        return selectedBlock + 4;
    }

    private static boolean isCompletelyFree(String[] columns, int blockIndex) {
        for (int physicalColumn : BLOCK_COLUMNS[blockIndex]) {
            String value = safeTrim(columns[physicalColumn]);
            if (!value.isEmpty() && !EMPTY_SLOT.equals(value)) return false;
        }
        return true;
    }

    private static int blockIndex(String keyMode) {
        String normalized = safeTrim(keyMode).toUpperCase();
        if ("4K".equals(normalized)) return 0;
        if ("5K".equals(normalized)) return 1;
        if ("6K".equals(normalized)) return 2;
        throw new IllegalArgumentException("无法识别 Catch 谱面的原始键位：" + safeTrim(keyMode));
    }

    private static int parseLevel(Map<String, String> chart) {
        String level = safeTrim(chart.get("level"));
        try {
            return Integer.parseInt(level);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Catch 谱面等级不是整数：" + level, ex);
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
