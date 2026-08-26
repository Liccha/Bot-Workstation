package com.botstation.features;

import java.nio.file.Path;
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

    SongLibraryRepository(Path database) { this.database = database; }

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
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int parameter = 1;
                for (String column : writable) statement.setString(parameter++, values.get(column));
                statement.setString(parameter, id);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("歌曲记录已不存在或主键不唯一");
                connection.commit();
            } catch (Exception error) {
                connection.rollback(); throw error;
            } finally { connection.setAutoCommit(true); }
        }
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
