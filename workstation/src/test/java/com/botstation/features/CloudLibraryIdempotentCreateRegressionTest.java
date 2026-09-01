package com.botstation.features;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A retry after a committed create must resume instead of failing with HTTP 409. */
public final class CloudLibraryIdempotentCreateRegressionTest {
    private CloudLibraryIdempotentCreateRegressionTest() {}

    public static void main(String[] args) throws Exception {
        AtomicInteger createCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/mobile-data", exchange -> handle(exchange, createCalls));
        server.start();
        Path root = Files.createTempDirectory("cloud-create-resume-");
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/mobile-data");
            CloudLibraryClient cloud = new CloudLibraryClient(endpoint, root.resolve("state"));
            Map<String, String> same = new LinkedHashMap<>();
            same.put("song_name", "I Want You");
            same.put("author", "Lin-G");

            cloud.createSong("1273", same);
            require(createCalls.get() == 1, "an idempotent retry submitted song-create more than once");
            require(cloud.hasPublishedAsset("1273", "image"), "an already committed image was not recognized");
            require(!cloud.hasPublishedAsset("1273", "audio"), "a leaked local audio path was mistaken for a cloud asset");

            boolean conflict = false;
            try {
                cloud.createSong("1273", Map.of("song_name", "另一首歌", "author", "Lin-G"));
            } catch (IOException expected) {
                conflict = expected.getMessage() != null && expected.getMessage().contains("HTTP 409");
            }
            require(conflict, "a genuinely different record was incorrectly treated as an idempotent retry");
            System.out.println("CLOUD_LIBRARY_IDEMPOTENT_CREATE_GREEN");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void handle(HttpExchange exchange, AtomicInteger createCalls) throws IOException {
        String action = query(exchange.getRequestURI().getRawQuery(), "action");
        JSONObject response = new JSONObject();
        int status = 200;
        if ("enroll-editor".equals(action)) {
            JSONObject input = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            response.put("id", "00000000-0000-0000-0000-000000000001")
                .put("token", "00000000-0000-0000-0000-000000000001." + input.getString("secret"));
            status = 201;
        } else if (!String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Device ")) {
            status = 401;
            response.put("error", "not authorized");
        } else if ("song-create".equals(action)) {
            createCalls.incrementAndGet();
            status = 409;
            response.put("error", "record already exists").put("code", "record_exists");
        } else if ("songs".equals(action)) {
            JSONObject item = new JSONObject()
                .put("id", "1273")
                .put("song_name", "I Want You")
                .put("author", "Lin-G")
                .put("image_path", "cloud-object:mobile-library/assets/image/1273/existing.webp")
                .put("audio_path", "C:\\Users\\someone\\Desktop\\preview\\1273.mp3");
            response.put("revision", 13)
                .put("columns", new JSONArray().put("id").put("song_name").put("author").put("image_path").put("audio_path"))
                .put("items", new JSONArray().put(item)).put("hasMore", false).put("nextOffset", 1).put("total", 1);
        } else {
            status = 400;
            response.put("error", "unexpected action");
        }
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String query(String raw, String key) {
        if (raw == null) return "";
        for (String part : raw.split("&")) {
            int equals = part.indexOf('=');
            if (equals > 0 && key.equals(part.substring(0, equals)))
                return java.net.URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
