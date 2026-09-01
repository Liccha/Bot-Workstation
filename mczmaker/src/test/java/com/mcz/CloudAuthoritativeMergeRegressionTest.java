package com.mcz;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Locks down cloud deletions so released IDs do not survive in the embedded editor workbook. */
public final class CloudAuthoritativeMergeRegressionTest {
    private CloudAuthoritativeMergeRegressionTest() { }

    public static void main(String[] args) throws Exception {
        MczTool tool = allocateWithoutOpeningWindow();
        tool.currentWorkbook = workbookWithSongs("1272", "1273");

        Map<String, String> retained = new LinkedHashMap<>();
        retained.put("id", "1272");
        retained.put("song_name", "テート・ア・ライブ");
        int max = ExcelManager.mergeCloudSongs(tool,
            List.of("id", "song_name"), List.of(retained));

        require(max == 1272, "cloud-deleted ID 1273 still controls the next song ID");
        require(!containsId(tool.currentWorkbook, "1273"),
            "cloud-deleted ID 1273 still occupies the embedded workbook");
        require(containsId(tool.currentWorkbook, "1272"),
            "authoritative cloud merge removed a retained song");
        tool.currentWorkbook.close();
        System.out.println("CLOUD_AUTHORITATIVE_MERGE_GREEN");
    }

    private static MczTool allocateWithoutOpeningWindow() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (MczTool) ((Unsafe) field.get(null)).allocateInstance(MczTool.class);
    }

    private static XSSFWorkbook workbookWithSongs(String... ids) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("songs");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("id");
        header.createCell(1).setCellValue("song_name");
        for (int index = 0; index < ids.length; index++) {
            Row row = sheet.createRow(index + 1);
            row.createCell(0).setCellValue(ids[index]);
            row.createCell(1).setCellValue("song-" + ids[index]);
        }
        return workbook;
    }

    private static boolean containsId(XSSFWorkbook workbook, String expected) {
        Sheet sheet = workbook.getSheetAt(0);
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row != null && expected.equals(row.getCell(0).getStringCellValue())) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
