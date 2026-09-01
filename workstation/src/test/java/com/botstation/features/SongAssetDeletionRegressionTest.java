package com.botstation.features;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Deleting a song must release its row and remove only media owned by that exact ID. */
public final class SongAssetDeletionRegressionTest {
    private SongAssetDeletionRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("song-asset-delete-");
        Path desktop = root.resolve("Desktop");
        Path workstation = desktop.resolve("Bot工作站");
        Path songBot = desktop.resolve("SongBot");
        Files.createDirectories(workstation);
        Files.createDirectories(songBot);
        String oldHome = System.getProperty("user.home");
        String oldStation = System.getProperty("botstation.home");
        String oldSongBot = System.getProperty("botstation.songbot.home");
        try {
            System.setProperty("user.home", root.toString());
            System.setProperty("botstation.home", workstation.toString());
            System.setProperty("botstation.songbot.home", songBot.toString());
            BotPaths paths = BotPaths.detect();
            Path csv = songBot.resolve("songs.csv");
            Files.writeString(csv, "id,song_name,image_path,audio_path\r\n1273,删除测试,1273.webp,1273.mp3\r\n",
                StandardCharsets.UTF_8);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + paths.songDatabase);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE songs (id TEXT PRIMARY KEY, song_name TEXT, image_path TEXT, audio_path TEXT)");
                statement.execute("INSERT INTO songs VALUES ('1273','删除测试','1273.webp','1273.mp3')");
            }
            Path cover = desktop.resolve("合集").resolve("1273.webp");
            Path audio = desktop.resolve("preview").resolve("1273.mp3");
            Path otherCover = desktop.resolve("合集").resolve("12730.webp");
            Files.createDirectories(cover.getParent()); Files.createDirectories(audio.getParent());
            Files.writeString(cover, "cover"); Files.writeString(audio, "audio"); Files.writeString(otherCover, "other");

            SongLibraryRepository repository = new SongLibraryRepository(paths.songDatabase);
            new SongAssetService(paths, new LogBus(paths.logs()), repository).deleteSong("1273");

            require(repository.load().rows.stream().noneMatch(row -> "1273".equals(row.get("id"))),
                "song row still exists");
            require(!Files.exists(cover), "cover still exists");
            require(!Files.exists(audio), "audio still exists");
            require(Files.isRegularFile(otherCover), "another ID's cover was deleted");
            System.out.println("SONG_ASSET_DELETE_GREEN");
        } finally {
            restore("user.home", oldHome); restore("botstation.home", oldStation);
            restore("botstation.songbot.home", oldSongBot);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
