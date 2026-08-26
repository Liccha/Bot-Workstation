package com.mcz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Regression tests for whole-block Catch placement. */
public final class CatchChartBlockAllocatorTest {
    public static void main(String[] args) {
        catchMovesAsAWholePastPartiallyOccupiedBlocks();
        singleRainStillOwnsTheWholeBlock();
        normalSpAlsoOccupiesTheWholeBlock();
        fifthDifficultyUsesThePhysicalSpColumn();
        refusesToSplitWhenNoWholeBlockExists();
        refusesMoreThanFiveDifficulties();
        System.out.println("GREEN: Catch charts reserve exactly one complete key block.");
    }

    private static void catchMovesAsAWholePastPartiallyOccupiedBlocks() {
        String[] columns = blankRow();
        columns[19] = "3-300";  // normal 4K EZ
        columns[25] = "10-900"; // normal 5K HD

        int selected = CatchChartBlockAllocator.placeIntoNextFreeBlock(columns, Arrays.asList(
                chart("4K", "Salad", "4"),
                chart("4K", "Platter", "8"),
                chart("4K", "Rain", "14")
        ));

        assertEquals(6, selected, "Catch should skip both partially occupied blocks");
        assertEquals("3-300", columns[19], "normal 4K must stay untouched");
        assertEquals("10-900", columns[25], "normal 5K must stay untouched");
        assertBlock(columns, 6, "Salad Lv.4", "Platter Lv.8", "Rain Lv.14", "0-0", "0-0");
    }

    private static void singleRainStillOwnsTheWholeBlock() {
        String[] columns = blankRow();
        columns[19] = "3-300";

        int selected = CatchChartBlockAllocator.placeIntoNextFreeBlock(columns,
                Arrays.asList(chart("4K", "Rain", "14")));

        assertEquals(5, selected, "single Rain must leave an occupied 4K block");
        assertBlock(columns, 5, "0-0", "0-0", "Rain Lv.14", "0-0", "0-0");
    }

    private static void fifthDifficultyUsesThePhysicalSpColumn() {
        String[] columns = blankRow();
        List<Map<String, String>> charts = Arrays.asList(
                chart("4K", "Cup", "2"),
                chart("4K", "Salad", "4"),
                chart("4K", "Platter", "8"),
                chart("4K", "Rain", "14"),
                chart("4K", "Overdose", "18")
        );

        CatchChartBlockAllocator.placeIntoNextFreeBlock(columns, charts);

        assertBlock(columns, 4, "Cup Lv.2", "Salad Lv.4", "Platter Lv.8", "Rain Lv.14", "Overdose Lv.18");
        assertEquals("0-0", columns[23], "fifth difficulty must not spill into 5K EZ");
    }

    private static void normalSpAlsoOccupiesTheWholeBlock() {
        String[] columns = blankRow();
        columns[31] = "17-1700"; // normal 4K SP lives outside the contiguous 4K columns

        int selected = CatchChartBlockAllocator.placeIntoNextFreeBlock(columns,
                Arrays.asList(chart("4K", "Rain", "14")));

        assertEquals(5, selected, "a normal 4K SP must reserve the complete 4K block");
        assertEquals("17-1700", columns[31], "normal 4K SP must stay untouched");
        assertBlock(columns, 5, "0-0", "0-0", "Rain Lv.14", "0-0", "0-0");
    }

    private static void refusesToSplitWhenNoWholeBlockExists() {
        String[] columns = blankRow();
        columns[19] = "1-100";
        columns[23] = "2-200";
        columns[27] = "3-300";
        expectFailure("没有完整键位块", new Runnable() {
            @Override
            public void run() {
                CatchChartBlockAllocator.placeIntoNextFreeBlock(columns,
                        Arrays.asList(chart("4K", "Rain", "14")));
            }
        });
    }

    private static void refusesMoreThanFiveDifficulties() {
        String[] columns = blankRow();
        List<Map<String, String>> charts = new ArrayList<>();
        charts.add(chart("4K", "Cup", "2"));
        charts.add(chart("4K", "Salad", "4"));
        charts.add(chart("4K", "Platter", "8"));
        charts.add(chart("4K", "Rain", "14"));
        charts.add(chart("4K", "Overdose", "18"));
        charts.add(chart("4K", "Deluge", "20"));
        expectFailure("超过 5 个难度", new Runnable() {
            @Override
            public void run() {
                CatchChartBlockAllocator.placeIntoNextFreeBlock(columns, charts);
            }
        });
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

    private static void expectFailure(String expectedMessagePart, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure containing: " + expectedMessagePart);
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains(expectedMessagePart)) {
                throw new AssertionError("unexpected failure: " + expected.getMessage());
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
