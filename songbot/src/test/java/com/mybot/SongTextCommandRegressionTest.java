package com.mybot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

/** Locks down ID, title, artist, charter and nickname command lookup behavior. */
public final class SongTextCommandRegressionTest {
    private SongTextCommandRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("song-text-command-");
        Path database = root.resolve("song_data.db");
        createFixture(database);
        System.setProperty("songbot.home", root.toString());
        System.setProperty("songbot.database", database.toString());
        try {
            Class<?> bot = Class.forName("com.mybot.SongBot");
            Method extract = bot.getDeclaredMethod("extractSongQuery", String.class);
            extract.setAccessible(true);
            require("1001".equals(extract.invoke(null, "！1001")), "numeric ID command changed");
            require("Target Song".equals(extract.invoke(null, "！Target Song")), "text command changed");

            Method variants = bot.getDeclaredMethod("generateSearchVariants", String.class);
            variants.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> generated = (Set<String>) variants.invoke(null, "Target Song");
            require(generated.contains("Target Song"), "text variant generation lost the original query");

            Field databaseField = bot.getDeclaredField("dbService");
            databaseField.setAccessible(true);
            DatabaseService service = (DatabaseService) databaseField.get(null);
            verify(service, "Target Song", "song name");
            verify(service, "Target Artist", "author");
            verify(service, "Target Charter", "charter");
            verify(service, "Target Nick", "song nickname");
            verify(service, "Target Alias", "artist nickname");
            verify(service, "Second Nick", "additional nickname");
            verify(service, "Third Nick", "third nickname");
            verify(service, "Fourth Nick", "fourth nickname");
            verify(service, "Fifth Nick", "fifth nickname");
            verify(service, "Sixth Nick", "sixth nickname");
            System.out.println("SONG_TEXT_COMMAND_GREEN");
        } finally {
            System.clearProperty("songbot.home");
            System.clearProperty("songbot.database");
        }
    }

    private static void verify(DatabaseService service, String keyword, String label) {
        List<Song> values = service.searchByKeywordFuzzy(keyword);
        require(values.stream().anyMatch(song -> song.getId() == 1001), label + " lookup failed");
    }

    private static void createFixture(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE songs (" +
                "id INTEGER PRIMARY KEY, song_name TEXT, author TEXT, charter TEXT, bpm TEXT, duration TEXT," +
                "album TEXT, album_ids TEXT, song_nickname TEXT, artist_nickname TEXT," +
                "song_nickname2 TEXT, song_nickname3 TEXT, song_nickname4 TEXT, song_nickname5 TEXT, song_nickname6 TEXT," +
                "album_date TEXT, album_image_path TEXT," +
                "`4k_ez` TEXT, `4k_nm` TEXT, `4k_hd` TEXT, `4k_mx` TEXT, `4k_sp` TEXT," +
                "`5k_ez` TEXT, `5k_nm` TEXT, `5k_hd` TEXT, `5k_mx` TEXT, `5k_sp` TEXT," +
                "`6k_ez` TEXT, `6k_nm` TEXT, `6k_hd` TEXT, `6k_mx` TEXT, `6k_sp` TEXT," +
                "`7k_ez` TEXT, `7k_nm` TEXT, `7k_hd` TEXT, `7k_mx` TEXT, `7k_sp` TEXT," +
                "`8k_ez` TEXT, `8k_nm` TEXT, `8k_hd` TEXT, `8k_mx` TEXT, `8k_sp` TEXT," +
                "image_path TEXT, audio_path TEXT)");
            statement.execute("INSERT INTO songs (id,song_name,author,charter,bpm,duration,album,album_ids," +
                "song_nickname,artist_nickname,song_nickname2,song_nickname3,song_nickname4," +
                "song_nickname5,song_nickname6,album_date) VALUES " +
                "(1001,'Target Song','Target Artist','Target Charter','132.2','01:27','Target Album',',1,'," +
                "'Target Nick','Target Alias','Second Nick','Third Nick','Fourth Nick'," +
                "'Fifth Nick','Sixth Nick','2020-01-01')");
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
