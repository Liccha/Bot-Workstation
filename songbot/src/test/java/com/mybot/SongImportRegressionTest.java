package com.mybot;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Startup CSV imports must preserve the last usable data when an export is empty. */
public final class SongImportRegressionTest {
    private SongImportRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("song-import-regression-");
        Path database = directory.resolve("song_data.db");
        Constructor<DatabaseService> constructor;
        try {
            constructor = DatabaseService.class.getDeclaredConstructor(Path.class);
        } catch (NoSuchMethodException error) {
            throw new AssertionError("explicit database path seam is missing", error);
        }
        constructor.setAccessible(true);
        DatabaseService service = constructor.newInstance(database);
        Connection connection = service.getConnection();
        try {
            seedSongs(connection);
            Path emptySongs = directory.resolve("songs-empty.csv");
            Files.writeString(emptySongs,
                "id,song_name,author,bpm,duration,image_path,audio_path,album,album_ids\n",
                StandardCharsets.UTF_8);
            service.importCsv(emptySongs.toString());
            require(count(connection, "songs") == 1 && hasValue(connection, "songs", "id", "9001"),
                "header-only songs CSV preserves existing songs");

            Path validSongs = directory.resolve("songs-valid.csv");
            Files.writeString(validSongs,
                "id,song_name,author,bpm,duration,image_path,audio_path,album,album_ids\n"
                    + "1,First,Artist,120,01:00,image.webp,audio.mp3,Album,1\n"
                    + "2,Second,Artist,140,01:10,image2.webp,audio2.mp3,Album,1\n"
                    + "3,,Reserved,0,,,,,\n",
                StandardCharsets.UTF_8);
            service.importCsv(validSongs.toString());
            require(count(connection, "songs") == 3 && hasValue(connection, "songs", "id", "3"),
                "valid songs CSV replaces rows and preserves reserved IDs");

            seedDailySongs(connection);
            Path emptyDaily = directory.resolve("daily-empty.csv");
            Files.writeString(emptyDaily, "id,song_name,author\n", StandardCharsets.UTF_8);
            service.importDailySchedule(emptyDaily.toString());
            require(count(connection, "daily_songs") == 1 && hasValue(connection, "daily_songs", "id", "7001"),
                "header-only daily CSV preserves existing rows");

            Path validDaily = directory.resolve("daily-valid.csv");
            Files.writeString(validDaily,
                "id,song_name,author\n31,Daily One,Artist\n32,Daily Two,Artist\n", StandardCharsets.UTF_8);
            service.importDailySchedule(validDaily.toString());
            require(count(connection, "daily_songs") == 2 && hasValue(connection, "daily_songs", "id", "31"),
                "valid daily CSV replaces rows");
        } finally {
            connection.close();
        }
        System.out.println("SONG_IMPORT_GREEN");
    }

    private static void seedSongs(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM songs");
            statement.execute("INSERT INTO songs (id,song_name,author) VALUES (9001,'Existing','Artist')");
        }
    }

    private static void seedDailySongs(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM daily_songs");
            statement.execute("INSERT INTO daily_songs (id,song_name,author) VALUES (7001,'Existing Daily','Artist')");
        }
    }

    private static int count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static boolean hasValue(Connection connection, String table, String column, String value) throws Exception {
        String sql = "SELECT 1 FROM " + table + " WHERE " + column + "='" + value.replace("'", "''") + "'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next();
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
