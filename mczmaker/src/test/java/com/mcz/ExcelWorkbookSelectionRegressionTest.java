package com.mcz;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** A stale per-user workbook must not hide a newer workbook bundled in an update. */
public final class ExcelWorkbookSelectionRegressionTest {
    private ExcelWorkbookSelectionRegressionTest() {}

    public static void main(String[] args) throws Exception {
        try (XSSFWorkbook local = workbookWithLastId(1166);
             XSSFWorkbook bundled = workbookWithLastId(1272)) {
            require(ExcelManager.readMaxId(local) == 1166, "local fixture max ID is wrong");
            require(ExcelManager.readMaxId(bundled) == 1272, "bundled fixture max ID is wrong");
            require(ExcelManager.chooseNewestWorkbook(local, bundled) == bundled,
                "stale local songs.xlsx still shadows the newer bundled workbook");
        }
        System.out.println("EXCEL_WORKBOOK_SELECTION_GREEN");
    }

    private static XSSFWorkbook workbookWithLastId(int id) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        workbook.createSheet("songs").createRow(0).createCell(0).setCellValue("id");
        workbook.getSheetAt(0).createRow(1).createCell(0).setCellValue(id);
        return workbook;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
