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
        }
        Files.writeString(songBot.resolve("songs.csv"),
            "id,song_name,author,4k_ez\r\n3,旧名称,B,0-0\r\n", StandardCharsets.UTF_8);

        System.setProperty("botstation.home", workstation.toString());
        System.setProperty("botstation.songbot.home", songBot.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/mobile-data", CloudLibrarySyncRegressionTest::changes);
        server.start();
        try {
            BotPaths paths = BotPaths.detect();
            LogBus log = new LogBus(paths.logs());
            CloudLibrarySync sync = CloudLibrarySync.forTest(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/mobile-data"),
                "desktop-test-token", new MobileDataService(paths),
                paths.config().resolve("mobile-library-sync.json"), log);
            sync.syncOnce();

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT song_name FROM songs WHERE id='3'")) {
                require(result.next() && "手机已修改".equals(result.getString(1)), "cloud edit did not reach song_data.db");
            }
            String csv = Files.readString(songBot.resolve("songs.csv"), StandardCharsets.UTF_8);
            require(csv.contains("3,手机已修改,B,0-0"), "cloud edit did not reach songs.csv");
            JSONObject state = new JSONObject(Files.readString(paths.config().resolve("mobile-library-sync.json")));
            require(state.getLong("songs") == 2L, "song revision was not persisted");
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
                .put("values", new JSONObject().put("song_name", "手机已修改"))))
                .put("revision", 2).put("nextRevision", 2).put("hasMore", false)
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
}
