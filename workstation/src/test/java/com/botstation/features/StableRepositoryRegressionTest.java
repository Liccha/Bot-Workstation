package com.botstation.features;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** An empty Stable editor table must never replace the usable database. */
public final class StableRepositoryRegressionTest {
    private StableRepositoryRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Method validate = StableRepository.class.getDeclaredMethod("validate", java.util.List.class, java.util.List.class);
        validate.setAccessible(true);
        boolean rejected = false;
        try {
            validate.invoke(null,
                Arrays.asList("sid", "title", "artist", "bpm", "length", "creator", "update_time", "cover"),
                new ArrayList<>());
        } catch (InvocationTargetException error) {
            rejected = error.getCause() instanceof IllegalArgumentException;
        }
        require(rejected, "empty Stable dataset is rejected before any database or workbook write");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Cell cell = workbook.createSheet().createRow(0).createCell(0);
            cell.setCellValue(LocalDateTime.of(2026, 7, 27, 20, 28));
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("m/d/yy h:mm"));
            cell.setCellStyle(style);
            String displayed = StableRepository.displayValue(cell, "update_time", new DataFormatter(Locale.US));
            require("2026-07-27".equals(displayed), "Stable date is not normalized: " + displayed);

            Cell cover = workbook.getSheetAt(0).createRow(1).createCell(0);
            cover.setCellFormula("\"C:/stable_cover/\" & A2 & \".webp\"");
            require("AUTO".equals(StableRepository.displayValue(cover, "cover", new DataFormatter(Locale.US))),
                "Stable formula cover should be presented as AUTO in the editor");
        }
        Path coverDirectory = Path.of("C:/fixture/stable_cover");
        String legacy = "\"C:/fixture/stable_cover/\"&A2&\".webp\"";
        String persisted = StableRepository.canonicalCoverForPersistence(legacy, "12518", coverDirectory);
        require(persisted.replace('\\', '/').endsWith("/stable_cover/12518.webp"),
            "Stable CSV retained an uncalculated spreadsheet formula: " + persisted);
        System.out.println("STABLE_REPOSITORY_GREEN");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
