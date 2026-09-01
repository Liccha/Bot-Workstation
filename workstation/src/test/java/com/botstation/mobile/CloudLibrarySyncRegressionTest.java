package com.botstation.mobile;

import com.botstation.core.BotPaths;
import com.botstation.core.LogBus;
import com.botstation.features.MobileDataService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** Proves that a phone cloud edit reaches both SongBot's DB and CSV mirror. */
public final class CloudLibrarySyncRegressionTest {
    private CloudLibrarySyncRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bot-cloud-library-sync-");
        Path workstation = root.resolve("workstation");
        Path songBot = root.resolve("SongBot");
        Files.createDirectories(workstation);
        Files.createDirectories(songBot);
        Path database = songBot.resolve("song_data.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE songs (id TEXT PRIMARY KEY, song_name TEXT, author TEXT, \"4k_ez\" TEXT)");
            statement.execute("INSERT INTO songs VALUES ('3','旧名称','B','0-0')");
            statement.execute("INSERT INTO songs VALUES ('4','待删除','D','0-0')");
        }
        Files.writeString(songBot.resolve("songs.csv"),
            "id,song_name,author,4k_ez\r\n"
                + "3,旧名称,B,0-0\r\n"
                + "4,待删除,D,0-0\r\n"
                // A full-snapshot refresh can reach CSV before the incremental event reaches SQLite.
                // Replaying created=true must reconcile this partial mirror instead of duplicating it.
                + "1273,云端新录入,C,0-0\r\n",
            StandardCharsets.UTF_8);

        System.setProperty("botstation.home", workstation.toString());
        System.setProperty("botstation.songbot.home", songBot.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/mobile-data", CloudLibrarySyncRegressionTest::changes);
        server.start();
        try {
            BotPaths paths = BotPaths.detect();
            LogBus log = new LogBus(paths.logs());
            AtomicInteger publications = new AtomicInteger();
            CloudLibrarySync sync = CloudLibrarySync.forTest(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/mobile-data"),
                "desktop-test-token", new MobileDataService(paths),
                paths.config().resolve("mobile-library-sync.json"), log,
                publications::incrementAndGet);
            sync.syncOnce();
            // Simulate a crash after local commit but before the revision cursor is persisted.
            // The same created event must be safe to replay.
            new MobileDataService(paths).createSong("1273", new JSONObject()
                .put("song_name", "云端新录入").put("author", "C").put("4k_ez", "0-0"));

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT song_name FROM songs WHERE id='3'")) {
                require(result.next() && "手机已修改".equals(result.getString(1)), "cloud edit did not reach song_data.db");
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*), MAX(song_name) FROM songs WHERE id='1273'")) {
                require(result.next() && result.getInt(1) == 1 && "云端新录入".equals(result.getString(2)),
                    "cloud create was not idempotent in song_data.db");
            }
            String csv = Files.readString(songBot.resolve("songs.csv"), StandardCharsets.UTF_8);
            require(csv.contains("3,手机已修改,B,0-0"), "cloud edit did not reach songs.csv");
            require(csv.contains("1273,云端新录入,C,0-0"), "cloud create did not reach songs.csv");
            require(occurrences(csv, "1273,云端新录入,C,0-0") == 1,
                "cloud create duplicated an existing full-snapshot CSV row");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM songs WHERE id='4'")) {
                require(result.next() && result.getInt(1) == 0, "cloud delete did not release the local song ID");
            }
            require(!csv.contains("4,待删除,D,0-0"), "cloud delete did not remove the CSV row");
            JSONObject state = new JSONObject(Files.readString(paths.config().resolve("mobile-library-sync.json")));
            require(state.getLong("songs") == 4L, "song revision was not persisted");
            require(state.getLong("publishedSongs") == 4L, "published song revision was not persisted");
            require(publications.get() == 1, "song changes did not trigger exactly one publication");
            sync.syncOnce();
            require(publications.get() == 1, "unchanged song revision was published again");
            System.out.println("CLOUD_LIBRARY_SYNC_GREEN");
        } finally {
            server.stop(0);
            System.clearProperty("botstation.home");
            System.clearProperty("botstation.songbot.home");
        }
    }

    private static void changes(HttpExchange exchange) throws java.io.IOException {
        require("Desktop desktop-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")),
            "desktop token missing from sync request");
        String query = exchange.getRequestURI().getRawQuery();
        boolean songs = query != null && query.contains("dataset=songs");
        JSONObject response = songs
            ? new JSONObject().put("items", new JSONArray().put(new JSONObject()
                .put("revision", 2).put("id", "3")
                .put("values", new JSONObject().put("song_name", "手机已修改")))
                .put(new JSONObject().put("revision", 3).put("id", "1273").put("created", true)
                .put("values", new JSONObject().put("song_name", "云端新录入")
                    .put("author", "C").put("4k_ez", "0-0")))
                .put(new JSONObject().put("revision", 4).put("id", "4").put("deleted", true)
                    .put("values", new JSONObject())))
                .put("revision", 4).put("nextRevision", 4).put("hasMore", false)
            : new JSONObject().put("items", new JSONArray())
                .put("revision", 0).put("nextRevision", 0).put("hasMore", false);
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int occurrences(String text, String expected) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(expected, index)) >= 0; index += expected.length()) count++;
        return count;
    }
}
