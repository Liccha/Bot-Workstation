package com.botstation.features;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A public installation must remain useful when no local SongBot database exists. */
public final class CloudLibraryFallbackRegressionTest {
    private CloudLibraryFallbackRegressionTest() {}

    public static void main(String[] args) throws Exception {
        AtomicReference<String> name = new AtomicReference<>("云端歌曲");
        AtomicReference<String> createdName = new AtomicReference<>("");
        AtomicInteger enrollment = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/mobile-data", exchange -> handle(exchange, name, createdName, enrollment));
        server.start();
        Path root = Files.createTempDirectory("cloud-library-fallback-");
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/mobile-data");
            CloudLibraryClient cloud = new CloudLibraryClient(endpoint, root.resolve("state"));
            SongLibraryRepository repository = new SongLibraryRepository(root.resolve("missing").resolve("song_data.db"), cloud);
            require(repository.cloudMode(), "missing local DB did not select cloud mode");
            SongLibraryRepository.Snapshot snapshot = repository.load();
            require(snapshot.rows.size() == 1, "cloud row was not loaded");
            require("云端歌曲".equals(snapshot.rows.get(0).get("song_name")), "cloud row was corrupted");
            Map<String, String> values = new LinkedHashMap<>(); values.put("song_name", "外部用户修改");
            repository.update("id", "1", values);
            require("外部用户修改".equals(repository.load().rows.get(0).get("song_name")), "cloud update was not visible");
            System.setProperty("botstation.cloud.library.readTimeoutMs", "100");
            Map<String, String> delayed = new LinkedHashMap<>(); delayed.put("song_name", "写入后响应超时");
            repository.update("id", "1", delayed);
            require("写入后响应超时".equals(repository.load().rows.get(0).get("song_name")),
                "a committed cloud update was reported as failed after response timeout");
            System.clearProperty("botstation.cloud.library.readTimeoutMs");
            Map<String, String> newSong = new LinkedHashMap<>(); newSong.put("song_name", "外部新录入");
            repository.create("id", "1273", newSong);
            require(repository.load().rows.stream().anyMatch(row -> "1273".equals(row.get("id"))
                && "外部新录入".equals(row.get("song_name"))),
                "cloud create was not visible without a local database");
            repository.delete("id", "1273");
            require(repository.load().rows.stream().noneMatch(row -> "1273".equals(row.get("id"))),
                "cloud delete did not release the song ID");
            repository.create("id", "1273", Map.of("song_name", "释放后复用"));
            Path owner = root.resolve("owner"); Files.createDirectories(owner);
            Path ownerDb = owner.resolve("song_data.db");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + ownerDb);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE songs (id TEXT PRIMARY KEY, song_name TEXT)");
                statement.execute("INSERT INTO songs VALUES ('1','本地旧值')");
                statement.execute("INSERT INTO songs VALUES ('1273','释放后复用')");
            }
            Files.writeString(owner.resolve("songs.csv"), "id,song_name\r\n1,本地旧值\r\n1273,释放后复用\r\n", StandardCharsets.UTF_8);
            SongLibraryRepository hybrid = new SongLibraryRepository(ownerDb, cloud);
            require(!hybrid.cloudMode(), "owner installation stopped reading its local database");
            hybrid.update("id", "1", Map.of("song_name", "主电脑同步修改"));
            require("主电脑同步修改".equals(name.get()), "owner edit was not written to cloud");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + ownerDb);
                 Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT song_name FROM songs WHERE id='1'")) {
                require(row.next() && "主电脑同步修改".equals(row.getString(1)), "owner edit was not written locally");
            }
            hybrid.delete("id", "1273");
            require(createdName.get().isEmpty(), "owner delete did not release the cloud ID");
            require(hybrid.load().rows.stream().noneMatch(row -> "1273".equals(row.get("id"))),
                "owner delete did not release the local ID");
            require(enrollment.get() == 1, "installation identity was not reused");
            require(Files.isRegularFile(root.resolve("state").resolve("library-editor-device.json")), "device identity was not persisted");
            require(!Files.exists(root.resolve("missing").resolve("song_data.db")), "fallback created a fake local database");
            System.out.println("CLOUD_LIBRARY_FALLBACK_GREEN");
        } finally { System.clearProperty("botstation.cloud.library.readTimeoutMs"); server.stop(0); executor.shutdownNow(); }
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> name,
                               AtomicReference<String> createdName, AtomicInteger enrollment) throws java.io.IOException {
        String action = query(exchange.getRequestURI().getRawQuery(), "action");
        JSONObject response = new JSONObject(); int status = 200;
        if ("enroll-editor".equals(action)) {
            JSONObject input = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            enrollment.incrementAndGet();
            response.put("id", "00000000-0000-0000-0000-000000000001")
                .put("token", "00000000-0000-0000-0000-000000000001." + input.getString("secret"));
            status = 201;
        } else if (!String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Device ")) {
            status = 401; response.put("error", "not authorized");
        } else if ("status".equals(action)) {
            int revision = createdName.get().isEmpty() ? (name.get().equals("云端歌曲") ? 1 : 2) : 3;
            response.put("songs", new JSONObject().put("revision", revision).put("total", createdName.get().isEmpty() ? 1 : 2));
            response.put("stable", new JSONObject().put("revision", 1).put("total", 1));
        } else if ("songs".equals(action)) {
            JSONArray items = new JSONArray().put(new JSONObject().put("id", "1").put("song_name", name.get()));
            if (!createdName.get().isEmpty()) items.put(new JSONObject().put("id", "1273").put("song_name", createdName.get()));
            response.put("revision", createdName.get().isEmpty() ? 2 : 3).put("columns", new JSONArray().put("id").put("song_name"))
                .put("items", items).put("hasMore", false).put("nextOffset", items.length()).put("total", items.length());
        } else if ("song".equals(action)) {
            JSONObject input = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            name.set(input.getJSONObject("values").getString("song_name")); response.put("ok", true).put("revision", 2);
            if ("写入后响应超时".equals(name.get())) {
                try { Thread.sleep(350); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        } else if ("song-create".equals(action)) {
            JSONObject input = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            createdName.set(input.getJSONObject("values").getString("song_name"));
            response.put("ok", true).put("revision", 3); status = 201;
        } else if ("song-delete".equals(action)) {
            createdName.set(""); response.put("ok", true).put("deleted", true).put("revision", 4);
        } else { status = 400; response.put("error", "unexpected"); }
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }

    private static String query(String raw, String key) {
        if (raw == null) return "";
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && key.equals(part.substring(0, equals))) return java.net.URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
