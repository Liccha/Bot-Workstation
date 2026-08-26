package com.mybot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CloudAnnouncementClientRegressionTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("songbot-cloud-client-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean completed = new AtomicBoolean();
        try {
            byte[] image = "fake-image".getBytes(StandardCharsets.UTF_8);
            byte[] attachmentA = "fake-attachment-a".getBytes(StandardCharsets.UTF_8);
            byte[] attachmentB = "fake-attachment-b".getBytes(StandardCharsets.UTF_8);
            server.createContext("/file/image", exchange -> reply(exchange, 200, image, "application/octet-stream"));
            server.createContext("/file/a", exchange -> reply(exchange, 200, attachmentA, "application/octet-stream"));
            server.createContext("/file/b", exchange -> reply(exchange, 200, attachmentB, "application/octet-stream"));
            server.createContext("/api", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                require("Bot test-bot-token".equals(exchange.getRequestHeaders().getFirst("Authorization")), "bot authorization missing");
                if (query.contains("action=bot-due")) {
                    if (completed.get()) replyJson(exchange, new JSONObject().put("items", new JSONArray()));
                    else replyJson(exchange, new JSONObject().put("items", new JSONArray().put(new JSONObject().put("id", "a1"))));
                } else if (query.contains("action=bot-claim")) {
                    String base = "http://127.0.0.1:" + server.getAddress().getPort();
                    replyJson(exchange, new JSONObject().put("id", "a1").put("claimToken", "claim-1")
                        .put("groupId", "2000000004").put("title", "cloud test").put("content", "body")
                        .put("time", "2026-08-20 10:00").put("image", "uploads/ann_test/image/test.png")
                        .put("imageUrl", base + "/file/image")
                        .put("attach", "uploads/ann_test/attach/6fd4312b-7ca9-41e8-86b5-fd84b4fa4727-次元音符 第6期（终稿）.pdf|uploads/ann_test/attach/7883dd3c-0349-4998-839f-4d0acddaa355-하루.txt")
                        .put("attachmentNames", new JSONArray().put("次元音符 第6期（终稿）.pdf"))
                        .put("attachmentUrls", new JSONObject()
                            .put("uploads/ann_test/attach/6fd4312b-7ca9-41e8-86b5-fd84b4fa4727-次元音符 第6期（终稿）.pdf", base + "/file/a")
                            .put("uploads/ann_test/attach/7883dd3c-0349-4998-839f-4d0acddaa355-하루.txt", base + "/file/b")));
                } else if (query.contains("action=bot-complete")) {
                    completed.set(true); replyJson(exchange, new JSONObject().put("ok", true));
                } else {
                    replyJson(exchange, new JSONObject().put("error", "unexpected"));
                }
            });
            server.start();
            Path data = root.resolve("data"); Files.createDirectories(data);
            Files.writeString(data.resolve("cloud-announcement.properties"),
                "backend=cloud\napi=http://127.0.0.1:" + server.getAddress().getPort() + "/api\nbotToken=test-bot-token\nbotId=test-host\n");
            CloudAnnouncementClient client = CloudAnnouncementClient.fromEnvironment(root.toFile());
            int sent = client.processDue(java.time.LocalDateTime.of(2026, 8, 25, 10, 0), announcement -> {
                File localImage = new File(new File(root.toFile(), "announce_files"), announcement.getString("image"));
                require(localImage.isFile(), "cloud image was not cached locally");
                String[] localAttachments = announcement.getString("attach").split("\\|");
                require(localAttachments.length == 2, "cloud attachment list changed length");
                JSONArray localNames = announcement.optJSONArray("attachmentNames");
                require(localNames != null && localNames.length() == 2, "attachment names were not preserved in parallel");
                require("次元音符 第6期（终稿）.pdf".equals(localNames.optString(0)), "explicit Unicode filename changed");
                require("하루.txt".equals(localNames.optString(1)), "legacy cloud filename was not recovered from object key");
                for (String localAttachment : localAttachments) {
                    File file = new File(new File(root.toFile(), "announce_files"), localAttachment);
                    require(file.isFile() && file.length() > 0, "cloud attachment was not cached locally");
                }
                return AnnouncementStore.SendResult.success("mocked");
            });
            require(sent == 1 && completed.get(), "cloud task was not completed exactly once");
            require(client.processDue(java.time.LocalDateTime.of(2026, 8, 25, 10, 1), item -> AnnouncementStore.SendResult.success("unexpected")) == 0,
                "completed task was offered again");
            System.out.println("CloudAnnouncementClient regression test passed");
        } finally {
            server.stop(0);
            if (Files.exists(root)) Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }

    private static void replyJson(HttpExchange exchange, JSONObject json) throws java.io.IOException {
        reply(exchange, 200, json.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }
    private static void reply(HttpExchange exchange, int status, byte[] body, String type) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(status, body.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
