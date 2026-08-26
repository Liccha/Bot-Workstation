package com.mybot;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Stable imports must never replace a usable table with an empty data set. */
public final class StableImportRegressionTest {
    private StableImportRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("stable-import-regression-");
        Path database = directory.resolve("songs.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            seedExistingRow(connection);
            Stable stable = new Stable(connection, null);
            Method importMethod;
            try {
                importMethod = Stable.class.getDeclaredMethod("initDatabase", Path.class);
            } catch (NoSuchMethodException error) {
                throw new AssertionError("safe Stable import seam is missing", error);
            }
            importMethod.setAccessible(true);

            boolean missingAccepted = (Boolean) importMethod.invoke(stable, directory.resolve("missing.csv"));
            require(!missingAccepted, "missing CSV is rejected");
            require(count(connection) == 1 && hasSid(connection, "old"), "missing CSV preserves existing Stable rows");

            Path headerOnly = directory.resolve("header-only.csv");
            Files.writeString(headerOnly,
                "sid,title,artist,bpm,length,creator,update_time,cover\n", StandardCharsets.UTF_8);
            boolean emptyAccepted = (Boolean) importMethod.invoke(stable, headerOnly);
            require(!emptyAccepted, "empty CSV is rejected");
            require(count(connection) == 1 && hasSid(connection, "old"), "empty CSV preserves existing Stable rows");

            Path valid = directory.resolve("valid.csv");
            Files.writeString(valid,
                "sid,title,artist,bpm,length,creator,update_time,cover\n"
                    + "101,First,Artist,120,100,Creator,2026/8/26 10:00,C:/covers/101.webp\n"
                    + "102,Second,Artist,140,110,Creator,2026/8/26 10:01,C:/covers/102.webp\n",
                StandardCharsets.UTF_8);
            boolean imported = (Boolean) importMethod.invoke(stable, valid);
            require(imported, "valid CSV is imported");
            require(count(connection) == 2 && hasSid(connection, "101") && hasSid(connection, "102"),
                "valid import atomically replaces Stable rows");
        }
        System.out.println("STABLE_IMPORT_GREEN");
    }

    private static void seedExistingRow(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stable_info (sid TEXT PRIMARY KEY,title TEXT,artist TEXT,bpm TEXT,length TEXT,creator TEXT,update_time TEXT,cover TEXT)");
            statement.execute("INSERT INTO stable_info VALUES ('old','Existing','Artist','100','90','Creator','2026/8/1','old.webp')");
        }
    }

    private static int count(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM stable_info")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static boolean hasSid(Connection connection, String sid) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM stable_info WHERE sid=?")) {
            statement.setString(1, sid);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
