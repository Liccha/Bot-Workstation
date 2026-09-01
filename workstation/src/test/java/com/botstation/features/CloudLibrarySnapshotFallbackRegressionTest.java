package com.botstation.features;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/** Reproduces the production HTTP 500 and proves the client can bypass the failed Vercel-to-OSS read. */
public final class CloudLibrarySnapshotFallbackRegressionTest {
    private CloudLibrarySnapshotFallbackRegressionTest() {}

    public static void main(String[] args) throws Exception {
        JSONObject snapshot = new JSONObject()
            .put("dataset", "songs")
            .put("revision", 71)
            .put("columns", new JSONArray().put("id").put("song_name"))
            .put("items", new JSONArray().put(new JSONObject().put("id", "1286").put("song_name", "直连快照歌曲")));
        byte[] compressed;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            compressed = bytes.toByteArray();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/mobile-data", exchange -> api(exchange, server.getAddress().getPort()));
        server.createContext("/snapshot.gz", exchange -> send(exchange, 200, "application/gzip", compressed));
        server.start();
        Path state = Files.createTempDirectory("cloud-snapshot-fallback-");
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/mobile-data");
            CloudLibraryClient client = new CloudLibraryClient(endpoint, state);
            SongLibraryRepository.Snapshot result = client.loadSongs();
            require(result.rows.size() == 1, "signed compact snapshot was not loaded");
            require("1286".equals(result.rows.get(0).get("id")), "snapshot ID changed during fallback");
            require("直连快照歌曲".equals(result.rows.get(0).get("song_name")), "snapshot text changed during fallback");
            System.out.println("CLOUD_LIBRARY_SNAPSHOT_FALLBACK_GREEN");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void api(HttpExchange exchange, int port) throws java.io.IOException {
        String action = query(exchange.getRequestURI().getRawQuery(), "action");
        JSONObject response = new JSONObject();
        int status = 200;
        if ("enroll-editor".equals(action)) {
            JSONObject input = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            response.put("id", "00000000-0000-0000-0000-000000000002")
                .put("token", "00000000-0000-0000-0000-000000000002." + input.getString("secret"));
            status = 201;
        } else if (!String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Device ")) {
            status = 401;
            response.put("error", "not authorized");
        } else if ("status".equals(action)) {
            response.put("songs", new JSONObject().put("revision", 71).put("total", 1));
        } else if ("songs".equals(action)) {
            status = 500;
            response.put("error", "internal");
        } else if ("snapshot-ticket".equals(action)) {
            response.put("dataset", "songs").put("encoding", "gzip-json")
                .put("url", "http://127.0.0.1:" + port + "/snapshot.gz");
        } else {
            status = 400;
            response.put("error", "unexpected");
        }
        send(exchange, status, "application/json; charset=utf-8", response.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
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
