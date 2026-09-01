package com.botstation.features;

import com.botstation.core.BotPaths;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class StableRepository {
    private static final List<String> REQUIRED = java.util.Arrays.asList("sid", "title", "artist", "bpm", "length", "creator", "update_time", "cover");
    private final BotPaths paths;
    private final CloudLibraryClient cloud;

    StableRepository(BotPaths paths) {
        this.paths = paths;
        this.cloud = localWorkbookAvailable(paths.stableWorkbook)
            ? null : new CloudLibraryClient(paths.userState().resolve("library"));
    }

    Snapshot load() throws Exception {
        if (cloud != null) return cloud.loadStable();
        try (InputStream input = Files.newInputStream(paths.stableWorkbook); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0); DataFormatter formatter = new DataFormatter(Locale.CHINA);
            Row headerRow = sheet.getRow(0); if (headerRow == null) throw new IllegalStateException("Stable 工作簿没有表头");
            int width = Math.max(1, headerRow.getLastCellNum());
            List<String> headers = new ArrayList<>();
            for (int column = 0; column < width; column++) headers.add(formatter.formatCellValue(headerRow.getCell(column)).trim());
            List<List<String>> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index); if (row == null) continue;
                List<String> values = new ArrayList<>(); boolean any = false;
                for (int column = 0; column < width; column++) {
                    String value = displayValue(row.getCell(column), headers.get(column), formatter);
                    values.add(value); any |= !value.isEmpty();
                }
                if (any) rows.add(values);
            }
            return new Snapshot(headers, rows, sheet.getSheetName());
        }
    }

    SaveResult save(List<String> headers, List<List<String>> rows) throws Exception {
        validate(headers, rows);
        if (cloud != null) return saveCloud(headers, rows);
        Files.createDirectories(paths.workstation.resolve("backups"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backupXlsx = paths.workstation.resolve("backups").resolve("stable_info-" + stamp + ".xlsx");
        Path backupCsv = paths.workstation.resolve("backups").resolve("stable_info-" + stamp + ".csv");
        Files.copy(paths.stableWorkbook, backupXlsx, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(paths.stableCsv)) Files.copy(paths.stableCsv, backupCsv, StandardCopyOption.REPLACE_EXISTING);
        Path tempXlsx = paths.stableWorkbook.resolveSibling(paths.stableWorkbook.getFileName() + ".botstation.tmp");
        Path tempCsv = paths.stableCsv.resolveSibling(paths.stableCsv.getFileName() + ".botstation.tmp");
        List<List<String>> persistedRows = rowsForPersistence(headers, rows, paths.desktop.resolve("stable_cover"));
        writeWorkbook(tempXlsx, headers, rows); writeCsv(tempCsv, headers, persistedRows);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.songDatabase.toAbsolutePath())) {
            connection.setAutoCommit(false);
            try {
                try (Statement pragma = connection.createStatement()) { pragma.execute("PRAGMA busy_timeout=8000"); }
                try (Statement clear = connection.createStatement()) { clear.executeUpdate("DELETE FROM stable_info"); }
                String sql = "INSERT INTO stable_info (sid,title,artist,bpm,length,creator,update_time,cover) VALUES (?,?,?,?,?,?,?,?)";
                try (PreparedStatement insert = connection.prepareStatement(sql)) {
                    for (List<String> row : persistedRows) {
                        for (int i = 0; i < REQUIRED.size(); i++) insert.setString(i + 1, value(row, indexIgnoreCase(headers, REQUIRED.get(i))));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                replace(tempXlsx, paths.stableWorkbook); replace(tempCsv, paths.stableCsv);
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                if (Files.exists(backupXlsx)) Files.copy(backupXlsx, paths.stableWorkbook, StandardCopyOption.REPLACE_EXISTING);
                if (Files.exists(backupCsv)) Files.copy(backupCsv, paths.stableCsv, StandardCopyOption.REPLACE_EXISTING);
                throw error;
            } finally { connection.setAutoCommit(true); Files.deleteIfExists(tempXlsx); Files.deleteIfExists(tempCsv); }
        }
        return new SaveResult(rows.size(), backupXlsx);
    }

    boolean cloudMode() { return cloud != null; }

    private SaveResult saveCloud(List<String> headers, List<List<String>> rows) throws Exception {
        Snapshot current = cloud.loadStable();
        int sidColumn = indexIgnoreCase(headers, "sid");
        int currentSidColumn = indexIgnoreCase(current.headers, "sid");
        java.util.Map<String, List<String>> currentById = new java.util.LinkedHashMap<>();
        for (List<String> row : current.rows) currentById.put(value(row, currentSidColumn).trim(), row);
        for (List<String> row : rows) {
            String sid = value(row, sidColumn).trim();
            if (!currentById.containsKey(sid)) {
                throw new IllegalArgumentException("云端 Stable 暂不支持新增 SID " + sid + "；现有记录仍可编辑");
            }
        }
        if (rows.size() != current.rows.size()) {
            throw new IllegalArgumentException("云端 Stable 暂不支持删除记录；现有记录仍可编辑");
        }
        for (List<String> row : rows) {
            String sid = value(row, sidColumn).trim();
            List<String> before = currentById.get(sid);
            java.util.Map<String, String> changed = new java.util.LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String header = headers.get(column);
                if (header.equalsIgnoreCase("sid")) continue;
                int oldColumn = indexIgnoreCase(current.headers, header);
                if (oldColumn < 0) continue;
                String next = value(row, column);
                if (!next.equals(value(before, oldColumn))) changed.put(header, next);
            }
            if (!changed.isEmpty()) cloud.updateStable(sid, changed);
        }
        return new SaveResult(rows.size(), paths.userState().resolve("library").resolve("cloud-version"));
    }

    private static boolean localWorkbookAvailable(Path workbook) {
        try { return Files.isRegularFile(workbook) && Files.size(workbook) > 0; }
        catch (Exception ignored) { return false; }
    }

    private void writeWorkbook(Path output, List<String> headers, List<List<String>> rows) throws Exception {
        try (InputStream input = Files.newInputStream(paths.stableWorkbook); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0); DataFormatter formatter = new DataFormatter(Locale.CHINA);
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.getRow(rowIndex + 1);
                if (row == null) {
                    row = sheet.createRow(rowIndex + 1);
                    Row template = sheet.getRow(Math.max(1, rowIndex));
                    if (template != null) row.setHeight(template.getHeight());
                }
                for (int column = 0; column < headers.size(); column++) {
                    String desired = value(rows.get(rowIndex), column);
                    Cell cell = row.getCell(column); if (cell == null) cell = row.createCell(column);
                    String current = displayValue(cell, headers.get(column), formatter);
                    if (desired.equals(current)) continue;
                    setCell(cell, headers.get(column), desired, rowIndex + 2);
                }
            }
            try (OutputStream stream = Files.newOutputStream(output)) { workbook.write(stream); }
        }
    }

    private void setCell(Cell cell, String header, String value, int excelRow) {
        String normalized = header.toLowerCase(Locale.ROOT);
        if (value.isBlank()) { cell.setBlank(); return; }
        try {
            if (normalized.equals("sid")) { cell.setCellValue(Long.parseLong(value)); return; }
            if (normalized.equals("bpm") || normalized.equals("length")) { cell.setCellValue(Double.parseDouble(value)); return; }
        } catch (NumberFormatException ignored) { }
        if (normalized.equals("cover") && value.equalsIgnoreCase("AUTO")) {
            String coverDirectory = paths.desktop.resolve("stable_cover").toString() + java.io.File.separator;
            cell.setCellFormula("\"" + coverDirectory.replace("\"", "\"\"") + "\" & A" + excelRow + " & \".webp\"");
            return;
        }
        cell.setCellValue(value);
    }

    private static void writeCsv(Path output, List<String> headers, List<List<String>> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write('\ufeff'); writer.write(csvLine(headers)); writer.newLine();
            for (List<String> row : rows) { writer.write(csvLine(row)); writer.newLine(); }
        }
    }

    private static String csvLine(List<String> values) {
        List<String> escaped = new ArrayList<>();
        for (String value : values) escaped.add('"' + String.valueOf(value).replace("\"", "\"\"") + '"');
        return String.join(",", escaped);
    }

    private static List<List<String>> rowsForPersistence(List<String> headers, List<List<String>> rows, Path coverDirectory) {
        int sidColumn = indexIgnoreCase(headers, "sid");
        int coverColumn = indexIgnoreCase(headers, "cover");
        List<List<String>> result = new ArrayList<>();
        for (List<String> source : rows) {
            List<String> row = new ArrayList<>(source);
            while (row.size() < headers.size()) row.add("");
            if (sidColumn >= 0 && coverColumn >= 0) {
                row.set(coverColumn, canonicalCoverForPersistence(
                    value(source, coverColumn), value(source, sidColumn), coverDirectory));
            }
            result.add(row);
        }
        return result;
    }

    static String canonicalCoverForPersistence(String current, String sid, Path coverDirectory) {
        String raw = current == null ? "" : current.trim();
        if (raw.isEmpty() || sid == null || sid.trim().isEmpty()) return raw;
        String lower = raw.toLowerCase(Locale.ROOT);
        boolean generated = raw.equalsIgnoreCase("AUTO")
            || (lower.contains("stable_cover") && lower.contains(".webp") && lower.matches(".*&a\\d+&.*"));
        if (!generated) return raw;
        return coverDirectory.resolve(sid.trim() + ".webp").toAbsolutePath().normalize().toString();
    }

    private static void validate(List<String> headers, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Stable 曲库不能为空；已取消保存并保留现有数据");
        }
        for (String required : REQUIRED) if (headers.stream().noneMatch(header -> header.equalsIgnoreCase(required)))
            throw new IllegalArgumentException("缺少必要列：" + required);
        int sidIndex = indexIgnoreCase(headers, "sid"); int bpmIndex = indexIgnoreCase(headers, "bpm"); int lengthIndex = indexIgnoreCase(headers, "length");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String sid = value(rows.get(i), sidIndex).trim();
            if (sid.isEmpty()) throw new IllegalArgumentException("第 " + (i + 2) + " 行 SID 为空");
            if (!ids.add(sid)) throw new IllegalArgumentException("SID 重复：" + sid);
            parseNumber(value(rows.get(i), bpmIndex), "BPM", i + 2);
            parseNumber(value(rows.get(i), lengthIndex), "时长", i + 2);
        }
    }

    private static void parseNumber(String text, String label, int row) {
        try { Double.parseDouble(text.trim()); }
        catch (Exception error) { throw new IllegalArgumentException("第 " + row + " 行" + label + "不是数字：" + text); }
    }
    static String displayValue(Cell cell, String header, DataFormatter formatter) {
        if (cell == null) return "";
        if ("cover".equalsIgnoreCase(String.valueOf(header)) && cell.getCellType() == CellType.FORMULA) {
            return "AUTO";
        }
        if ("update_time".equalsIgnoreCase(String.valueOf(header))
            && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return formatter.formatCellValue(cell).trim();
    }
    private static int indexIgnoreCase(List<String> values, String expected) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).equalsIgnoreCase(expected)) return i;
        return -1;
    }
    private static String value(List<String> row, int index) { return index >= 0 && index < row.size() ? String.valueOf(row.get(index)) : ""; }
    private static void replace(Path from, Path to) throws Exception {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    static final class Snapshot {
        final List<String> headers; final List<List<String>> rows; final String sheetName;
        Snapshot(List<String> headers, List<List<String>> rows, String sheetName) { this.headers = headers; this.rows = rows; this.sheetName = sheetName; }
    }
    static final class SaveResult {
        final int rows; final Path backup;
        SaveResult(int rows, Path backup) { this.rows = rows; this.backup = backup; }
    }
}
