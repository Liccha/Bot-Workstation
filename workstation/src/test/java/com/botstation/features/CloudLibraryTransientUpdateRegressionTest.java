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
import java.util.concurrent.atomic.AtomicReference;

/** Transient 503 responses must be reconciled without unbounded duplicate writes. */
public final class CloudLibraryTransientUpdateRegressionTest {
    private CloudLibraryTransientUpdateRegressionTest() {}

    public static void main(String[] args) throws Exception {
        AtomicReference<String> albumId = new AtomicReference<>("208");
        AtomicReference<String> mode = new AtomicReference<>("committed-503");
        AtomicReference<JSONObject> lastSubmittedValues = new AtomicReference<>();
        AtomicInteger updateCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        AtomicInteger legacyReadCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/mobile-data",
            exchange -> handle(exchange, albumId, mode, updateCalls, readCalls,
                legacyReadCalls, lastSubmittedValues));
        server.start();
        Path root = Files.createTempDirectory("cloud-transient-update-");
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/mobile-data");
            CloudLibraryClient cloud = new CloudLibraryClient(endpoint, root.resolve("state"));

            Map<String, String> committed = new LinkedHashMap<>();
            committed.put("album_ids", "209");
            committed.put("image_path", "C:\\Users\\someone\\Desktop\\cover.png");
            cloud.updateSong("1273", committed);
            require("209".equals(albumId.get()), "the simulated write was not committed");
            require(updateCalls.get() == 1, "a committed 503 response caused a duplicate POST");
            require(readCalls.get() == 1, "a committed 503 response was not checked exactly once");
            require(legacyReadCalls.get() == 0, "the client ignored the exact song-item endpoint");
            require(lastSubmittedValues.get() != null
                    && lastSubmittedValues.get().keySet().equals(java.util.Set.of("album_ids")),
                "the client submitted unchanged or backend-managed fields: " + lastSubmittedValues.get());

            mode.set("committed-write-busy");
            int busyUpdatesBefore = updateCalls.get();
            int busyReadsBefore = readCalls.get();
            cloud.updateSong("1273", Map.of("album_ids", "210"));
            require("210".equals(albumId.get()), "the structured write_busy update was not committed");
            require(updateCalls.get() - busyUpdatesBefore == 1, "a committed write_busy response caused a duplicate POST");
            require(readCalls.get() - busyReadsBefore == 1, "a committed write_busy response was not checked exactly once");

            mode.set("committed-500");
            int serverErrorUpdatesBefore = updateCalls.get();
            int serverErrorReadsBefore = readCalls.get();
            cloud.updateSong("1273", Map.of("album_ids", "211"));
            require("211".equals(albumId.get()), "the HTTP 500 update was not committed");
            require(updateCalls.get() - serverErrorUpdatesBefore == 1,
                "a committed HTTP 500 response caused a duplicate POST");
            require(readCalls.get() - serverErrorReadsBefore == 1,
                "a committed HTTP 500 response was not checked exactly once");

            int exactReadsBeforeMissing = readCalls.get();
            require(!cloud.hasPublishedAsset("9999", "image"), "a song-item 404 was not treated as absent");
            require(readCalls.get() - exactReadsBeforeMissing == 1,
                "a missing song was not checked with exactly one song-item request");
            require(legacyReadCalls.get() == 0, "a song-item 404 incorrectly fell back to the legacy list API");

            mode.set("always-403");
            int forbiddenUpdatesBefore = updateCalls.get();
            int forbiddenReadsBefore = readCalls.get();
            boolean forbidden = false;
            try {
                cloud.updateSong("1273", Map.of("album_ids", "212"));
            } catch (IOException expected) {
                forbidden = expected.getMessage() != null && expected.getMessage().contains("HTTP 403");
            }
            require(forbidden, "an HTTP 403 update was not rejected");
            require(updateCalls.get() - forbiddenUpdatesBefore == 1, "an HTTP 403 update was retried");
            require(readCalls.get() - forbiddenReadsBefore == 0, "an HTTP 403 update triggered a write reconciliation");

            mode.set("always-503");
            int updatesBefore = updateCalls.get();
            int readsBefore = readCalls.get();
            boolean failed = false;
            try {
                cloud.updateSong("1273", Map.of("album_ids", "213"));
            } catch (IOException expected) {
                failed = expected.getMessage() != null && expected.getMessage().contains("HTTP 503");
            }
            require(failed, "an uncommitted repeated 503 was incorrectly reported as success");
            require(updateCalls.get() - updatesBefore == 2, "an uncommitted update was not limited to one retry");
            require(readCalls.get() - readsBefore == 2, "an uncommitted update performed an unexpected number of read-backs");
            System.out.println("CLOUD_LIBRARY_TRANSIENT_UPDATE_GREEN");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> albumId,
                               AtomicReference<String> mode, AtomicInteger updateCalls,
                               AtomicInteger readCalls,
                               AtomicInteger legacyReadCalls,
                               AtomicReference<JSONObject> lastSubmittedValues) throws IOException {
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
        } else if ("song".equals(action)) {
            updateCalls.incrementAndGet();
            JSONObject input = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            JSONObject submittedValues = input.getJSONObject("values");
            lastSubmittedValues.set(submittedValues);
            if (mode.get().startsWith("committed-")) albumId.set(submittedValues.getString("album_ids"));
            if ("always-403".equals(mode.get())) {
                status = 403;
                response.put("error", "forbidden");
            } else if ("committed-write-busy".equals(mode.get())) {
                status = 409;
                response.put("error", "announcement store is busy").put("code", "write_busy");
            } else if ("committed-500".equals(mode.get())) {
                status = 500;
                response.put("error", "post-commit marker failed");
            } else {
                status = 503;
                response.put("error", "cloud data not initialized"); // Legacy backend has no structured code.
            }
        } else if ("song-item".equals(action)) {
            readCalls.incrementAndGet();
            String requestedId = query(exchange.getRequestURI().getRawQuery(), "id");
            if (!"1273".equals(requestedId)) {
                status = 404;
                response.put("error", "record not found");
            } else {
                response.put("id", "1273").put("album_ids", albumId.get())
                    .put("image_path", "cloud-object:mobile-library/assets/image/1273/current.webp")
                    .put("audio_path", "cloud-object:mobile-library/assets/audio/1273/current.ogg");
            }
        } else if ("songs".equals(action)) {
            legacyReadCalls.incrementAndGet();
            JSONObject item = new JSONObject().put("id", "1273").put("album_ids", albumId.get());
            response.put("revision", 15)
                .put("columns", new JSONArray().put("id").put("album_ids").put("image_path").put("audio_path"))
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
