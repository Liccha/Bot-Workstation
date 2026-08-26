package com.mybot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class CloudWebsitePostClientRegressionTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("songbot-website-cloud-");
        Path posts = root.resolve("posts"); Files.createDirectories(posts);
        AtomicReference<String> cloudContent = new AtomicReference<>("云端第一版");
        AtomicLong cloudModified = new AtomicLong(System.currentTimeMillis());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api", exchange -> {
                require("Desktop test-desktop-token".equals(exchange.getRequestHeaders().getFirst("Authorization")), "desktop authorization missing");
                String query = exchange.getRequestURI().getRawQuery();
                if (query.contains("action=website-list")) {
                    reply(exchange, new JSONArray().put(summary(cloudContent.get(), cloudModified.get())).toString());
                } else if (query.contains("action=website-read")) {
                    require(queryName(query).equals("测试文章.md"), "post name was not encoded safely");
                    reply(exchange, summary(cloudContent.get(), cloudModified.get()).put("content", cloudContent.get()).toString());
                } else if (query.contains("action=website-save")) {
                    JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    require(body.optInt("revision") == 1, "save revision was not forwarded");
                    cloudContent.set(body.getString("content")); cloudModified.set(System.currentTimeMillis());
                    reply(exchange, summary(cloudContent.get(), cloudModified.get()).put("content", cloudContent.get()).put("revision", 2).toString());
                } else reply(exchange, new JSONObject().put("ok", true).toString());
            });
            server.start();
            Path data = root.resolve("data"); Files.createDirectories(data);
            Files.writeString(data.resolve("cloud-announcement.properties"),
                "backend=cloud\napi=http://127.0.0.1:" + server.getAddress().getPort() + "/api\ndesktopToken=test-desktop-token\n");
            CloudWebsitePostClient client = CloudWebsitePostClient.fromEnvironment(root.toFile());
            client.syncTo(posts.toFile());
            Path local = posts.resolve("测试文章.md");
            require(Files.readString(local).equals("云端第一版"), "cloud post was not mirrored locally");

            JSONObject saved = client.save("测试文章.md", "云端第二版", 1);
            client.mirrorSavedPost(posts.toFile(), saved);
            require(Files.readString(local).equals("云端第二版"), "saved cloud post was not mirrored locally");

            Files.writeString(local, "本机更新版本"); local.toFile().setLastModified(System.currentTimeMillis() + 60_000L);
            cloudContent.set("较旧云端版本"); cloudModified.set(System.currentTimeMillis());
            client.syncTo(posts.toFile());
            require(Files.readString(local).equals("本机更新版本"), "newer local edit was overwritten");
            System.out.println("CloudWebsitePostClient regression test passed");
        } finally {
            server.stop(0);
            if (Files.exists(root)) Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }

    private static JSONObject summary(String content, long modified) {
        return new JSONObject().put("name", "测试文章.md").put("size", content.getBytes(StandardCharsets.UTF_8).length)
            .put("modified", modified).put("revision", 1).put("sha256", "test");
    }
    private static String queryName(String query) {
        for (String part : query.split("&")) if (part.startsWith("name=")) return URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8);
        return "";
    }
    private static void reply(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length); try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
