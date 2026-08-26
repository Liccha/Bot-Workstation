package com.botstation.features;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SongLibraryRepository {
    private final Path database;
    private final Path csv;

    SongLibraryRepository(Path database) {
        this.database = database;
        this.csv = database.getParent() == null ? null : database.getParent().resolve("songs.csv");
    }

    Snapshot load() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT * FROM songs ORDER BY id")) {
            ResultSetMetaData metadata = results.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) columns.add(metadata.getColumnName(index));
            List<Map<String, String>> rows = new ArrayList<>();
            while (results.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int index = 1; index <= columns.size(); index++) {
                    Object value = results.getObject(index);
                    row.put(columns.get(index - 1), value == null ? "" : String.valueOf(value));
                }
                rows.add(row);
            }
            return new Snapshot(columns, rows);
        }
    }

    void update(String idColumn, String id, Map<String, String> values) throws Exception {
        if (idColumn == null || idColumn.isBlank()) throw new IllegalArgumentException("歌曲表没有主键");
        List<String> writable = new ArrayList<>();
        for (String column : values.keySet()) if (!column.equalsIgnoreCase(idColumn)) writable.add(column);
        if (writable.isEmpty()) return;
        StringBuilder sql = new StringBuilder("UPDATE songs SET ");
        for (int i = 0; i < writable.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append(quote(writable.get(i))).append("=?");
        }
        sql.append(" WHERE ").append(quote(idColumn)).append("=?");
        byte[] csvBefore = csv != null && Files.isRegularFile(csv) ? Files.readAllBytes(csv) : null;
        if (csvBefore != null) updateCsv(csvBefore, idColumn, id, values);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                for (String column : writable) statement.setString(parameter++, values.get(column));
                statement.setString(parameter, id);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("歌曲记录已不存在或主键不唯一");
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                if (csvBefore != null) {
                    try { writeAtomic(csv, csvBefore); } catch (Exception restore) { error.addSuppressed(restore); }
                }
                throw error;
            } finally { connection.setAutoCommit(true); }
        }
    }

    private void updateCsv(byte[] original, String idColumn, String id, Map<String, String> values) throws Exception {
        boolean bom = original.length >= 3 && (original[0] & 0xff) == 0xef && (original[1] & 0xff) == 0xbb && (original[2] & 0xff) == 0xbf;
        String text = new String(original, bom ? 3 : 0, original.length - (bom ? 3 : 0), StandardCharsets.UTF_8);
        List<List<String>> records = parseCsv(text);
        if (records.isEmpty()) throw new IllegalStateException("songs.csv 为空，已取消保存");
        List<String> headers = records.get(0);
        int idIndex = indexIgnoreCase(headers, idColumn);
        if (idIndex < 0) throw new IllegalStateException("songs.csv 没有歌曲 ID 字段，已取消保存");
        for (String column : values.keySet()) if (indexIgnoreCase(headers, column) < 0) {
            headers.add(column); for (int row = 1; row < records.size(); row++) records.get(row).add("");
        }
        List<String> target = null;
        for (int row = 1; row < records.size(); row++) {
            List<String> record = records.get(row);
            if (idIndex < record.size() && id.equals(record.get(idIndex).trim())) { target = record; break; }
        }
        if (target == null) throw new IllegalStateException("songs.csv 中不存在歌曲 ID " + id + "，已取消保存");
        while (target.size() < headers.size()) target.add("");
        for (Map.Entry<String, String> entry : values.entrySet()) target.set(indexIgnoreCase(headers, entry.getKey()), entry.getValue() == null ? "" : entry.getValue());
        StringBuilder output = new StringBuilder(); if (bom) output.append('\ufeff');
        for (int row = 0; row < records.size(); row++) {
            List<String> record = records.get(row);
            while (record.size() < headers.size()) record.add("");
            for (int column = 0; column < headers.size(); column++) {
                if (column > 0) output.append(','); output.append(escapeCsv(record.get(column)));
            }
            output.append("\r\n");
        }
        writeAtomic(csv, output.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quoted) {
                if (value == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') { field.append('"'); index++; }
                else if (value == '"') quoted = false; else field.append(value);
            } else if (value == '"' && field.length() == 0) quoted = true;
            else if (value == ',') { row.add(field.toString()); field.setLength(0); }
            else if (value == '\r' || value == '\n') {
                if (value == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                row.add(field.toString()); field.setLength(0); records.add(row); row = new ArrayList<>();
            } else field.append(value);
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); records.add(row); }
        return records;
    }

    private static int indexIgnoreCase(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++) if (values.get(index).equalsIgnoreCase(expected)) return index;
        return -1;
    }
    private static String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        return safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0
            ? '"' + safe.replace("\"", "\"\"") + '"' : safe;
    }
    private static void writeAtomic(Path target, byte[] bytes) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=8000"); }
        return connection;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static final class Snapshot {
        final List<String> columns;
        final List<Map<String, String>> rows;
        Snapshot(List<String> columns, List<Map<String, String>> rows) { this.columns = columns; this.rows = rows; }
    }
}
