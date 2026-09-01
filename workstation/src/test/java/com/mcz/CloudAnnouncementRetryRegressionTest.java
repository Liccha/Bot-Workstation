package com.mcz;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** A one-off reset during a read must recover without polling forever. */
public final class CloudAnnouncementRetryRegressionTest {
    private CloudAnnouncementRetryRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path songBot = Files.createTempDirectory("announcement-retry-");
        Files.createDirectories(songBot.resolve("data"));
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announcements", exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.close();
                return;
            }
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Files.writeString(songBot.resolve("data").resolve("cloud-announcement.properties"),
            "backend=cloud\napi=http://127.0.0.1:" + server.getAddress().getPort()
                + "/announcements\ndesktopToken=test-token\n", StandardCharsets.UTF_8);
        try {
            require(CloudAnnouncementStore.fromSongBot(songBot.toFile()).load().isEmpty(),
                "retry response was not loaded");
            require(requests.get() == 2, "expected one bounded retry, got " + requests.get() + " requests");
            System.out.println("CLOUD_ANNOUNCEMENT_RETRY_GREEN");
        } finally {
            server.stop(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
